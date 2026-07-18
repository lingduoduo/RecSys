package com.recsys.infrastructure.persistence;

import com.recsys.application.pagination.MillionScalePaginationSql.SqlPlan;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class MySqlIndexContractAssertions {
    private MySqlIndexContractAssertions() {
    }

    static void assertContract(
            String migrationSql,
            SqlPlan plan,
            String indexName,
            List<String> indexColumns,
            String equalityPredicate,
            String orderBy
    ) {
        String normalizedMigration = normalize(migrationSql);
        String normalizedPlan = normalize(plan.sql());
        String columns = "(" + String.join(", ", indexColumns) + ")";
        String inlineDefinition = "INDEX " + indexName + " " + columns;
        String standaloneDefinition = "CREATE INDEX " + indexName + " ON movies " + columns;

        assertThat(occurrences(normalizedMigration, "\\b" + Pattern.quote(indexName) + "\\b"))
                .as("migration defines %s exactly once", indexName)
                .isEqualTo(1);
        assertThat(normalizedMigration.contains(inlineDefinition)
                || normalizedMigration.contains(standaloneDefinition))
                .as("migration defines %s with ordered columns %s", indexName, indexColumns)
                .isTrue();
        assertThat(normalizedPlan)
                .contains("FORCE INDEX (" + indexName + ")")
                .contains(normalize(equalityPredicate))
                .contains(normalize(orderBy));
    }

    private static int occurrences(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
