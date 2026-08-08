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
    /**
     * Deliberately identical to {@code MySqlConnectionSettings.URL_SSL_MODE} /
     * {@code URL_USE_SSL}, <b>and kept in sync with them by hand</b> — see that class for why the
     * property name is matched case-sensitively (Connector/J drops {@code sslmode=} /
     * {@code SSLMODE=} silently and runs at {@code PREFERRED}) and why {@code ;} separates
     * nothing (Connector/J splits the property block on {@code &} alone).
     *
     * <p>That shared logic is the reason this test is not a safety net for the guard: it checks
     * the manifest against the same rules the guard applies, so a flaw in the rules is invisible
     * to both. Two divergences from the real driver survived independent reviews of each file
     * precisely this way. When either pattern changes, change the other in the same commit.
     */
    private static final Pattern SSL_MODE = Pattern.compile("[?&]sslMode=([^&]*)");
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
            problems.addAll(problemsIn(String.valueOf(value), nameOf(doc) + "." + KEY));
        }

        // A silently-empty scan would pass this test while proving nothing.
        assertThat(found)
                .as("no ConfigMap in %s defines %s — the scan found nothing to check", BASE, KEY)
                .isTrue();
        assertThat(problems)
                .as("every MYSQL_URL must set sslMode=%s", REQUIRED_MODE)
                .isEmpty();
    }

    /**
     * The scan above passes vacuously if {@link #problemsIn} cannot see a bad URL, and every
     * URL in {@code k8s/base} is currently correct — so nothing there exercises the rejection
     * side. These are the spellings measured against Connector/J 8.4 that resolve to
     * {@code PREFERRED} (or to a driver error) despite reading as a verified connection.
     */
    @Test
    void rejectsUrlsConnectorJWouldNotRunAtVerifyIdentity() {
        List<String> shouldFail = List.of(
                // No sslMode at all: the driver default is PREFERRED.
                "jdbc:mysql://db.prod/recsys",
                // Weaker modes.
                "jdbc:mysql://db.prod/recsys?sslMode=PREFERRED",
                "jdbc:mysql://db.prod/recsys?sslMode=REQUIRED",
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_CA",
                "jdbc:mysql://db.prod/recsys?sslMode=DISABLED",
                // Wrong-case property name: Connector/J drops it, effective PREFERRED.
                "jdbc:mysql://db.prod/recsys?sslmode=verify_identity",
                "jdbc:mysql://db.prod/recsys?SSLMODE=VERIFY_IDENTITY",
                // ';' separates nothing: this is one connectionAttributes property whose value
                // contains the text "sslMode=VERIFY_IDENTITY". Effective PREFERRED.
                "jdbc:mysql://db.prod/recsys?connectionAttributes=x;sslMode=VERIFY_IDENTITY",
                // Same reason, other order: the driver reads the value as
                // "VERIFY_IDENTITY;connectionAttributes=x" and rejects it outright.
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_IDENTITY;connectionAttributes=x",
                // Deprecated useSSL, which sslMode silently overrides.
                "jdbc:mysql://db.prod/recsys?useSSL=true&sslMode=VERIFY_IDENTITY",
                // Disagreeing duplicates.
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_IDENTITY&sslMode=DISABLED");

        for (String url : shouldFail) {
            assertThat(problemsIn(url, "fixture"))
                    .as("%s must be reported as a problem", url)
                    .isNotEmpty();
        }

        List<String> shouldPass = List.of(
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_IDENTITY",
                // The value is safe to compare case-insensitively: the driver resolves this to
                // the VERIFY_IDENTITY enum constant.
                "jdbc:mysql://db.prod/recsys?sslMode=verify_identity",
                "jdbc:mysql://mysql:3306/recsys?sslMode=VERIFY_IDENTITY&serverTimezone=UTC"
                        + "&connectTimeout=1000&socketTimeout=2000");

        for (String url : shouldPass) {
            assertThat(problemsIn(url, "fixture"))
                    .as("%s must be accepted", url)
                    .isEmpty();
        }
    }

    private static List<String> problemsIn(String url, String where) {
        List<String> problems = new ArrayList<>();
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
        return problems;
    }
}
