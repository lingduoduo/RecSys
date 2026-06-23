package com.recsys.application.pagination;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL templates for million-row pagination.
 *
 * The generated SQL targets MySQL-style syntax because the optimization strategy depends on
 * composite B-tree indexes, LIMIT, and FORCE INDEX. Keep request values as bind parameters; this
 * class only validates SQL identifiers and emits placeholders.
 */
public final class MillionScalePaginationSql {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern QUALIFIED_IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private MillionScalePaginationSql() {
    }

    public static TableSpec table(
            String tableName,
            String idColumn,
            String sortColumn,
            SortDirection sortDirection,
            String coveringIndexName,
            List<String> selectColumns
    ) {
        return new TableSpec(
                qualifiedIdentifier(tableName),
                identifier(idColumn),
                identifier(sortColumn),
                sortDirection == null ? SortDirection.DESC : sortDirection,
                identifier(coveringIndexName),
                validateColumns(selectColumns, "selectColumns")
        );
    }

    public enum SortDirection {
        ASC("ASC", ">", "<"),
        DESC("DESC", "<", ">");

        private final String sql;
        private final String afterOperator;
        private final String beforeOperator;

        SortDirection(String sql, String afterOperator, String beforeOperator) {
            this.sql = sql;
            this.afterOperator = afterOperator;
            this.beforeOperator = beforeOperator;
        }

        public SortDirection reversed() {
            return this == ASC ? DESC : ASC;
        }
    }

    public record TableSpec(
            String tableName,
            String idColumn,
            String sortColumn,
            SortDirection sortDirection,
            String coveringIndexName,
            List<String> selectColumns
    ) {
        public TableSpec {
            selectColumns = List.copyOf(selectColumns);
        }
    }

    public record SeekCursor(String sortValue, long id) {
        public SeekCursor {
            if (sortValue == null || sortValue.isBlank()) {
                throw new IllegalArgumentException("sortValue must not be blank");
            }
        }

        public String encode() {
            String raw = sortValue + "\n" + id;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        public static SeekCursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalArgumentException("cursor must not be blank");
            }
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('\n');
            if (separator <= 0 || separator == raw.length() - 1) {
                throw new IllegalArgumentException("cursor is malformed");
            }
            return new SeekCursor(raw.substring(0, separator), Long.parseLong(raw.substring(separator + 1)));
        }
    }

    public record SqlPlan(String sql, List<Object> bindValues) {
        public SqlPlan {
            bindValues = List.copyOf(bindValues);
        }
    }

    /**
     * Builds the covering index used by both seek pagination and delayed join pagination.
     *
     * Put equality-filter columns first, then sort column, then id. Extra projected columns can be
     * appended when the database can cover a lightweight list page without touching the base row.
     */
    public static String coveringIndexDdl(
            TableSpec table,
            List<String> equalityFilterColumns,
            List<String> extraCoveredColumns
    ) {
        Objects.requireNonNull(table, "table");
        Set<String> columns = new LinkedHashSet<>();
        validateOptionalColumns(equalityFilterColumns).forEach(columns::add);
        columns.add(table.sortColumn());
        columns.add(table.idColumn());
        validateOptionalColumns(extraCoveredColumns).forEach(columns::add);

        String indexedColumns = String.join(", ", columns.stream()
                .map(column -> column + orderSuffix(table, column))
                .toList());

        return "CREATE INDEX " + table.coveringIndexName()
                + " ON " + table.tableName() + " (" + indexedColumns + ")";
    }

    /**
     * COUNT(*) using the covering index so MySQL can answer via the smaller index tree instead
     * of doing a full clustered-index scan.
     *
     * Without FORCE INDEX, MySQL's query planner sometimes prefers the primary key even when a
     * narrower covering index would read far fewer bytes. For a 10 M-row table a covering index
     * COUNT can be 5–20× faster than the unforced equivalent.
     */
    public static SqlPlan countWithCoveringIndex(
            TableSpec table,
            List<String> wherePredicates,
            List<Object> whereBindValues
    ) {
        Objects.requireNonNull(table, "table");
        List<String> predicates = validatePredicates(wherePredicates);
        List<Object> bindValues = new ArrayList<>(safeBindValues(whereBindValues));

        return new SqlPlan(
                "SELECT COUNT(*) FROM " + table.tableName()
                        + " FORCE INDEX (" + table.coveringIndexName() + ")"
                        + whereClause(predicates),
                bindValues
        );
    }

    /**
     * Cursor/keyset pagination. This avoids OFFSET scans: every next page starts after the last
     * tuple from the previous page using the stable (sortColumn, id) ordering.
     */
    public static SqlPlan cursorPage(
            TableSpec table,
            List<String> wherePredicates,
            List<Object> whereBindValues,
            SeekCursor after,
            int pageSize
    ) {
        Objects.requireNonNull(table, "table");
        validatePageSize(pageSize);
        return cursorPageImpl(table, wherePredicates, whereBindValues, after,
                table.sortDirection().afterOperator, table.sortDirection(), pageSize);
    }

    /**
     * Delayed join pagination for deep random-access pages.
     *
     * The inner query only walks the covering index and returns page keys; the outer query joins
     * those keys back to the base table after the expensive offset work is already done.
     */
    public static SqlPlan delayedJoinPage(
            TableSpec table,
            List<String> wherePredicates,
            List<Object> whereBindValues,
            int offset,
            int pageSize
    ) {
        Objects.requireNonNull(table, "table");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        validatePageSize(pageSize);

        List<String> predicates = validatePredicates(wherePredicates);
        List<Object> bindValues = new ArrayList<>(safeBindValues(whereBindValues));
        bindValues.add(pageSize);
        bindValues.add(offset);

        String pageAlias = "page_keys";
        String rowAlias = "t";
        String inner = "SELECT " + table.idColumn() + ", " + table.sortColumn()
                + " FROM " + table.tableName() + " FORCE INDEX (" + table.coveringIndexName() + ")"
                + whereClause(predicates)
                + orderBy(null, table)
                + " LIMIT ? OFFSET ?";

        return new SqlPlan(
                "SELECT " + selectList(rowAlias, table.selectColumns())
                        + " FROM " + table.tableName() + " " + rowAlias
                        + " JOIN (" + inner + ") " + pageAlias
                        + " ON " + rowAlias + "." + table.idColumn() + " = " + pageAlias + "." + table.idColumn()
                        + orderBy(pageAlias, table),
                bindValues
        );
    }

    /**
     * Backward cursor page (previous page).
     *
     * Returns the rows that come immediately BEFORE {@code before} in the stable
     * (sortColumn, id) ordering, using the covering index to avoid a table scan.
     *
     * The ORDER BY is reversed relative to the table's declared direction so that only the
     * rows adjacent to the cursor boundary are fetched (LIMIT is applied closest-first).
     * Callers must reverse the result list before display to restore table-natural order.
     *
     * Example for a DESC table (newest first):
     *   SQL ORDER BY sortColumn ASC, id ASC   ← reversed
     *   Result list  [oldest-of-page, …, newest-of-page]   ← caller reverses to DESC
     */
    public static SqlPlan cursorPageBefore(
            TableSpec table,
            List<String> wherePredicates,
            List<Object> whereBindValues,
            SeekCursor before,
            int pageSize
    ) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(before, "before cursor is required for cursorPageBefore");
        validatePageSize(pageSize);
        return cursorPageImpl(table, wherePredicates, whereBindValues, before,
                table.sortDirection().beforeOperator, table.sortDirection().reversed(), pageSize);
    }

    // Shared implementation for cursorPage and cursorPageBefore.
    // cursor null → no seek predicate (first page); seekOperator and orderDir differ between the two.
    private static SqlPlan cursorPageImpl(
            TableSpec table,
            List<String> wherePredicates,
            List<Object> whereBindValues,
            SeekCursor cursor,
            String seekOperator,
            SortDirection orderDir,
            int pageSize
    ) {
        List<String> predicates = validatePredicates(wherePredicates);
        List<Object> bindValues = new ArrayList<>(safeBindValues(whereBindValues));
        if (cursor != null) {
            predicates.add("(" + table.sortColumn() + " " + seekOperator + " ? OR ("
                    + table.sortColumn() + " = ? AND " + table.idColumn() + " " + seekOperator + " ?))");
            bindValues.add(cursor.sortValue());
            bindValues.add(cursor.sortValue());
            bindValues.add(cursor.id());
        }
        bindValues.add(pageSize);

        return new SqlPlan(
                "SELECT " + selectList(null, table.selectColumns())
                        + " FROM " + table.tableName() + " FORCE INDEX (" + table.coveringIndexName() + ")"
                        + whereClause(predicates)
                        + orderBy(null, table, orderDir)
                        + " LIMIT ?",
                bindValues
        );
    }

    private static String whereClause(List<String> predicates) {
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    private static String orderBy(String alias, TableSpec table) {
        return orderBy(alias, table, table.sortDirection());
    }

    private static String orderBy(String alias, TableSpec table, SortDirection dir) {
        String prefix = aliasPrefix(alias);
        return " ORDER BY " + prefix + table.sortColumn() + " " + dir.sql
                + ", " + prefix + table.idColumn() + " " + dir.sql;
    }

    private static String selectList(String alias, List<String> columns) {
        String prefix = aliasPrefix(alias);
        return String.join(", ", columns.stream().map(c -> prefix + c).toList());
    }

    private static String aliasPrefix(String alias) {
        return alias == null || alias.isBlank() ? "" : alias + ".";
    }

    private static String orderSuffix(TableSpec table, String column) {
        if (column.equals(table.sortColumn()) || column.equals(table.idColumn())) {
            return " " + table.sortDirection().sql;
        }
        return "";
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize <= 0 || pageSize > 1_000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
    }

    private static List<String> validatePredicates(List<String> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(predicates.size());
        for (String predicate : predicates) {
            if (predicate == null || predicate.isBlank() || predicate.contains(";")) {
                throw new IllegalArgumentException("wherePredicates must be non-blank SQL fragments without semicolons");
            }
            out.add(predicate.trim());
        }
        return out;
    }

    private static List<Object> safeBindValues(List<Object> bindValues) {
        return bindValues == null ? List.of() : List.copyOf(bindValues);
    }

    private static List<String> validateColumns(List<String> columns, String fieldName) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return validateOptionalColumns(columns);
    }

    private static List<String> validateOptionalColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) return List.of();
        return columns.stream().map(MillionScalePaginationSql::identifier).toList();
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        }
        return value;
    }

    private static String qualifiedIdentifier(String value) {
        if (value == null || !QUALIFIED_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + value);
        }
        return value;
    }
}
