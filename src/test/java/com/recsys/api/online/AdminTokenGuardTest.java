package com.recsys.api.online;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenGuardTest {

    private static final String TOKEN = "operator-secret";

    // ── isAuthorized / isConfigured (pure) ───────────────────────────────────

    @Test
    void unconfigured_authorizesNobody() {
        AdminTokenGuard guard = new AdminTokenGuard(null);
        assertThat(guard.isConfigured()).isFalse();
        assertThat(guard.isAuthorized(null)).isFalse();
        assertThat(guard.isAuthorized("anything")).isFalse();

        AdminTokenGuard blank = new AdminTokenGuard("   ");
        assertThat(blank.isConfigured()).isFalse();
        assertThat(blank.isAuthorized("anything")).isFalse();
    }

    @Test
    void configured_authorizesOnlyExactMatch() {
        AdminTokenGuard guard = new AdminTokenGuard(TOKEN);
        assertThat(guard.isConfigured()).isTrue();
        assertThat(guard.isAuthorized(TOKEN)).isTrue();
        assertThat(guard.isAuthorized(null)).isFalse();
        assertThat(guard.isAuthorized("wrong")).isFalse();
        // Same length as TOKEN (15 chars) — the case a prefix comparison is most vulnerable to.
        assertThat(TOKEN.length()).isEqualTo(15);
        assertThat(guard.isAuthorized("xxxxxxxxxxxxxxx")).isFalse();
    }

    // ── decorator ────────────────────────────────────────────────────────────

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            AdminTokenGuard guard = new AdminTokenGuard(TOKEN);
            sb.service("/guarded",
                    ((com.linecorp.armeria.server.HttpService)
                            (ctx, req) -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "ok"))
                            .decorate(AdminTokenGuard.newDecorator(guard)));
        }
    };

    @Test
    void decorator_rejectsMissingToken_with403() {
        AggregatedHttpResponse r = server.blockingWebClient().get("/guarded");
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.contentUtf8()).contains("operator token required");
    }

    @Test
    void decorator_rejectsWrongToken_with403() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .prepare().get("/guarded").header(AdminTokenGuard.HEADER, "nope").execute();
        assertThat(r.status()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void decorator_allowsValidToken() {
        AggregatedHttpResponse r = server.blockingWebClient()
                .prepare().get("/guarded").header(AdminTokenGuard.HEADER, TOKEN).execute();
        assertThat(r.status()).isEqualTo(HttpStatus.OK);
        assertThat(r.contentUtf8()).isEqualTo("ok");
    }
}
