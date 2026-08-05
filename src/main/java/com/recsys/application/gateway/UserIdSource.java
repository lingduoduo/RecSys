package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.QueryParams;

/**
 * Where a user-scoped backend route carries the {@code userId} it acts on.
 *
 * <p>Extraction is deliberately total: anything it cannot read as a scalar id — absent, blank,
 * malformed, an object, an array — comes back as {@code ""}, which the caller treats as a denial.
 * A request whose subject cannot be determined is a request that cannot be authorized.
 */
enum UserIdSource {

    QUERY {
        @Override
        String extract(String targetPath, AggregatedHttpRequest request) {
            int mark = targetPath.indexOf('?');
            if (mark < 0) {
                return "";
            }
            String value = QueryParams.fromQueryString(targetPath.substring(mark + 1)).get(PARAM);
            return value == null ? "" : value.trim();
        }
    },

    BODY {
        @Override
        String extract(String targetPath, AggregatedHttpRequest request) {
            try {
                JsonNode root = MAPPER.readTree(request.contentUtf8());
                if (root == null || !root.isObject()) {
                    return "";
                }
                JsonNode value = root.get(PARAM);
                if (value == null || value.isNull() || value.isContainerNode()) {
                    return "";
                }
                return value.asText("").trim();
            } catch (Exception e) {
                return "";
            }
        }
    };

    /** The parameter and JSON field name is `userId` on every route in the table. */
    static final String PARAM = "userId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param targetPath the rewritten backend path, including its query string
     * @param request    the already-aggregated request; reading it here costs no extra buffering
     */
    abstract String extract(String targetPath, AggregatedHttpRequest request);
}
