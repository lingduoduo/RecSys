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

    private static AggregatedHttpRequest body(String json) {
        return AggregatedHttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/api/recommend"), HttpData.ofUtf8(json));
    }
}
