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
                return scalarText(root.get(PARAM));
            } catch (Exception e) {
                return "";
            }
        }
    },

    /**
     * A TF-Serving-shaped batch: {@code {"instances":[{"userId":1,"movieId":2}, ...]}}. The id is
     * inside the array elements, not at the top level, so {@link #BODY} would read {@code ""} here
     * and deny every legitimate call while looking like a working control.
     *
     * <p>Returns the id only when the batch names exactly one user: the array is non-empty and
     * every element carries the same scalar {@code userId}. A batch mixing users is denied rather
     * than partially allowed — the gateway authorizes the whole request or none of it, and it has
     * no way to forward a subset. `/v1/models/recmodel:predict` scores against
     * {@code u2vEmb:<userId>}, so a mixed batch is a read of someone else's embedding.
     */
    BODY_INSTANCES {
        @Override
        String extract(String targetPath, AggregatedHttpRequest request) {
            try {
                JsonNode root = MAPPER.readTree(request.contentUtf8());
                if (root == null || !root.isObject()) {
                    return "";
                }
                JsonNode instances = root.get(INSTANCES);
                if (instances == null || !instances.isArray() || instances.size() == 0) {
                    return "";
                }
                String agreed = null;
                for (JsonNode instance : instances) {
                    if (instance == null || !instance.isObject()) {
                        return "";
                    }
                    String id = scalarText(instance.get(PARAM));
                    if (id.isEmpty() || (agreed != null && !agreed.equals(id))) {
                        return "";
                    }
                    agreed = id;
                }
                return agreed;
            } catch (Exception e) {
                return "";
            }
        }
    };

    /** The parameter and JSON field name is `userId` on every route in the table. */
    static final String PARAM = "userId";

    /** The array field wrapping a TF-Serving predict batch. */
    private static final String INSTANCES = "instances";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The one place the scalar rule lives: a container, a null, or an absent field is not an id.
     * Jackson's {@code asText("")} would happily stringify an object, so the guard runs first.
     */
    private static String scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            return "";
        }
        return value.asText("").trim();
    }

    /**
     * @param targetPath the rewritten backend path, including its query string
     * @param request    the already-aggregated request; reading it here costs no extra buffering
     */
    abstract String extract(String targetPath, AggregatedHttpRequest request);
}
