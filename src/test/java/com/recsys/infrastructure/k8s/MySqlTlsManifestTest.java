package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.recsys.infrastructure.k8s.ManifestDocuments.allIn;
import static com.recsys.infrastructure.k8s.ManifestDocuments.mapAt;
import static com.recsys.infrastructure.k8s.ManifestDocuments.nameOf;
import static com.recsys.infrastructure.k8s.ManifestDocuments.ofKind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MySQL URL a cluster is given must require a verified TLS connection.
 *
 * <p>{@code MySqlConnectionSettings} refuses to construct without {@code sslMode=VERIFY_IDENTITY}
 * when MySQL is enabled, which protects a running service. This protects the deploy: a manifest
 * whose URL omits it fails at pod start rather than at review, and Connector/J's default
 * ({@code PREFERRED}) is exactly the value that would look fine and connect in plaintext.
 *
 * <p>The analogue of {@link RedisAuthManifestTest}'s opt-out check, for the other data tier.
 *
 * <p>Scope: reads {@code k8s/base}. Tests cannot render overlays, so an overlay that replaced
 * {@code MYSQL_URL} would not be caught here.
 */
class MySqlTlsManifestTest {

    private static final Path BASE = Path.of("k8s", "base");
    private static final String KEY = "MYSQL_URL";
    private static final String REQUIRED_MODE = "VERIFY_IDENTITY";
    private static final Pattern SSL_MODE = Pattern.compile("(?i)[?&;]sslMode=([^&;]*)");
    private static final Pattern USE_SSL = Pattern.compile("(?i)[?&;]useSSL=");

    @Test
    void everyMySqlUrlRequiresAVerifiedConnection() throws IOException {
        List<String> problems = new ArrayList<>();
        boolean found = false;

        for (Map<String, Object> doc : ofKind(allIn(BASE), "ConfigMap")) {
            Map<String, Object> data = mapAt(doc, "data");
            Object value = data == null ? null : data.get(KEY);
            if (value == null) {
                continue;
            }
            found = true;
            String url = String.valueOf(value);
            String where = nameOf(doc) + "." + KEY;

            if (USE_SSL.matcher(url).find()) {
                problems.add(where + " uses the deprecated useSSL property; sslMode overrides it "
                        + "silently, so the URL reads as one thing and behaves as another");
            }
            Matcher modes = SSL_MODE.matcher(url);
            boolean sawMode = false;
            while (modes.find()) {
                sawMode = true;
                if (!REQUIRED_MODE.equalsIgnoreCase(modes.group(1).trim())) {
                    problems.add(where + " sets sslMode=" + modes.group(1)
                            + ", which does not verify the server");
                }
            }
            if (!sawMode) {
                problems.add(where + " sets no sslMode; Connector/J then defaults to PREFERRED, "
                        + "which falls back to plaintext without error");
            }
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(found)
                .as("no ConfigMap in %s defines %s — the scan found nothing to check", BASE, KEY)
                .isTrue();
        assertThat(problems)
                .as("every MYSQL_URL must set sslMode=%s", REQUIRED_MODE)
                .isEmpty();
    }
}
