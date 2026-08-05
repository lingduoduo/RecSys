package com.recsys.application.gateway;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserScopedRoutesTest {

    @Test
    void lookup_findsQueryAndBodyRoutes() {
        assertEquals(UserIdSource.QUERY,
                UserScopedRoutes.lookup("recsys-catalog-serving", "/getuser"));
        assertEquals(UserIdSource.QUERY,
                UserScopedRoutes.lookup("recsys-online-serving", "/online/features"));
        assertEquals(UserIdSource.BODY,
                UserScopedRoutes.lookup("recsys-model-serving", "/v2/sequential/recommend"));
        assertEquals(UserIdSource.BODY_INSTANCES,
                UserScopedRoutes.lookup("recsys-catalog-serving", "/v1/models/recmodel:predict"));
    }

    @Test
    void lookup_isExactNeverPrefix() {
        // Prefix-with-boundary matching is what created the /api/catalog trap that
        // PROTECTED_PREFIXES exists to survive (20_AuthN_AuthZ §3). Not repeated here.
        assertNull(UserScopedRoutes.lookup("recsys-catalog-serving", "/getuserprofile"));
        assertNull(UserScopedRoutes.lookup("recsys-catalog-serving", "/getuser/extra"));
    }

    @Test
    void lookup_returnsNullForUnknownOrNullService() {
        assertNull(UserScopedRoutes.lookup("recsys-llm", "/getuser"));
        assertNull(UserScopedRoutes.lookup(null, "/getuser"));
    }

    @Test
    void pathWithoutQuery_splitsOnTheFirstQuestionMark() {
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser?userId=42"));
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser"));
        assertEquals("/getuser", UserScopedRoutes.pathWithoutQuery("/getuser?a=1?b=2"));
    }

    @Test
    void query_extractsFromTargetPathNotRequestHeaders() {
        // The request's own path is still the pre-rewrite gateway path; the rewritten query
        // lives in targetPath. Reading the wrong one silently extracts nothing.
        AggregatedHttpRequest request = AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser?userId=999"),
                HttpData.empty());
        assertEquals("42", UserIdSource.QUERY.extract("/getuser?userId=42", request));
    }

    @Test
    void query_blankWhenAbsent() {
        AggregatedHttpRequest request = AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/api/catalog/getuser"), HttpData.empty());
        assertEquals("", UserIdSource.QUERY.extract("/getuser", request));
        assertEquals("", UserIdSource.QUERY.extract("/getuser?limit=5", request));
    }

    @Test
    void body_extractsStringAndNumericUserId() {
        assertEquals("42", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":\"42\"}")));
        // 6010 and 7010 bind userId as an int, so the JSON may legitimately be a number.
        assertEquals("42", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":42}")));
    }

    @Test
    void body_blankWhenMissingUnparseableOrNotScalar() {
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("{\"limit\":5}")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("not json")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("[1,2]")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("{\"userId\":{\"id\":1}}")));
        assertEquals("", UserIdSource.BODY.extract("/v2/recommend", body("")));
    }

    // ---- BODY_INSTANCES: the TF-Serving predict batch ------------------------------

    private static final String PREDICT = "/v1/models/recmodel:predict";

    @Test
    void instances_extractsWhenEveryElementNamesTheSameUser() {
        assertEquals("42", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":42,\"movieId\":1},{\"userId\":42,\"movieId\":2}]}")));
    }

    @Test
    void instances_extractsFromASingleElement() {
        assertEquals("42", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":42,\"movieId\":1}]}")));
        // The backend binds userId as an int, but a client may still send it as a string.
        assertEquals("42", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":\"42\"}]}")));
    }

    @Test
    void instances_deniesAMixedUserBatch() {
        // The gateway forwards the whole request or none of it — it cannot allow a subset, so a
        // batch naming a second user must be denied outright rather than partially allowed.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":42},{\"userId\":43}]}")));
        // Including when the caller's own id is present but not alone.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":42},{\"userId\":42},{\"userId\":99}]}")));
    }

    @Test
    void instances_blankWhenTheBatchNamesNoUsableId() {
        // Empty array: nothing to authorize.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[]}")));
        // Absent instances.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"userId\":42}")));
        // instances present but not an array.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":{\"userId\":42}}")));
        // An element missing its userId, or carrying a container instead of a scalar.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"movieId\":1}]}")));
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":{\"id\":42}}]}")));
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[{\"userId\":42},{\"movieId\":2}]}")));
        // An element that is not an object at all.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT,
                body("{\"instances\":[42]}")));
        // Malformed, empty, and non-object bodies stay total.
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT, body("not json")));
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT, body("")));
        assertEquals("", UserIdSource.BODY_INSTANCES.extract(PREDICT, body("[1,2]")));
    }

    @Test
    void instances_isNotReadableByPlainBody() {
        // Why the new kind exists: BODY reads a top-level scalar, so on this shape it would
        // extract nothing and deny every legitimate call while looking like a working control.
        String batch = "{\"instances\":[{\"userId\":42,\"movieId\":1}]}";
        assertEquals("", UserIdSource.BODY.extract(PREDICT, body(batch)));
        assertEquals("42", UserIdSource.BODY_INSTANCES.extract(PREDICT, body(batch)));
    }

    private static AggregatedHttpRequest body(String json) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/api/recommend"), HttpData.ofUtf8(json));
    }
}
