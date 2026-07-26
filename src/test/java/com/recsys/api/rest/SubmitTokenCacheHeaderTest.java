package com.recsys.api.rest;

import com.recsys.application.auth.SubmitTokenService;
import com.recsys.api.response.SubmitTokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubmitTokenCacheHeaderTest {

    @Test
    void getSubmitToken_isNeverCacheable() {
        SubmitTokenService tokens = mock(SubmitTokenService.class);
        when(tokens.createToken()).thenReturn("tok-1");
        when(tokens.ttlSeconds()).thenReturn(300);

        RecommendationController controller = new RecommendationController(
                null, null, null, null, null, tokens, null, null);

        ResponseEntity<SubmitTokenResponse> res = controller.getSubmitToken();

        assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(res.getBody()).isNotNull();
    }
}
