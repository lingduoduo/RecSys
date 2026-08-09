package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final String SSL_MODE_PROPERTY = "sslMode";
    /**
     * Deliberately identical to {@code MySqlConnectionSettings.URL_USE_SSL}, and this file's
     * {@link #sslModeValues} is deliberately identical to that class's method of the same name —
     * <b>all kept in sync by hand</b>. See that class for why the property name is compared
     * case-sensitively (Connector/J drops {@code sslmode=} / {@code SSLMODE=} silently and runs at
     * {@code PREFERRED}), why neither {@code ;} nor a second {@code ?} separates anything
     * (Connector/J opens the property block at the first {@code ?} and splits it on {@code &}
     * alone), and which one step of the driver's parse is deliberately not reproduced.
     *
     * <p>That shared logic is the reason this test is not a safety net for the guard: it checks
     * the manifest against the same rules the guard applies, so a flaw in the rules is invisible
     * to both. Four divergences from the real driver survived independent reviews of each file
     * precisely this way — a case-insensitive property name, {@code ;} read as a separator,
     * {@code ?} read as a separator, and a {@code #} fragment not discarded. Every one of them made
     * the pair accept a URL the driver runs at {@code PREFERRED}. When either copy changes, change
     * the other in the same commit.
     */
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
                // A second '?' separates nothing either: only the first one opens the property
                // block. This is k8s/base's own URL with one '&' mistyped as '?' — one property
                // named serverTimezone, effective PREFERRED. Measured against 8.4.0.
                "jdbc:mysql://mysql:3306/recsys?serverTimezone=UTC?sslMode=VERIFY_IDENTITY",
                "jdbc:mysql://mysql:3306/recsys?connectionAttributes=a?sslMode=VERIFY_IDENTITY",
                // '#' opens a fragment the driver discards before it reads any property.
                "jdbc:mysql://db.prod/recsys?a=b#&sslMode=VERIFY_IDENTITY",
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
                // Both orders around a second property, and the correctly-typed twin of the
                // '?'-separated fixtures above: the driver resolves all three to VERIFY_IDENTITY.
                "jdbc:mysql://db.prod/recsys?a=b&sslMode=VERIFY_IDENTITY",
                "jdbc:mysql://db.prod/recsys?sslMode=VERIFY_IDENTITY&a=b",
                "jdbc:mysql://mysql:3306/recsys?serverTimezone=UTC&sslMode=VERIFY_IDENTITY",
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
        List<String> modes = sslModeValues(url);
        for (String mode : modes) {
            if (!REQUIRED_MODE.equalsIgnoreCase(mode)) {
                problems.add(where + " sets sslMode=" + mode + ", which does not verify the server");
            }
        }
        if (modes.isEmpty()) {
            problems.add(where + " sets no sslMode; Connector/J then defaults to PREFERRED, "
                    + "which falls back to plaintext without error");
        }
        return problems;
    }

    /** The hand-kept copy of {@code MySqlConnectionSettings.sslModeValues} — see {@link #USE_SSL}. */
    private static List<String> sslModeValues(String url) {
        int fragment = url.indexOf('#');
        String beforeFragment = fragment < 0 ? url : url.substring(0, fragment);
        int propertyBlock = beforeFragment.indexOf('?');
        if (propertyBlock < 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String pair : beforeFragment.substring(propertyBlock + 1).split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (SSL_MODE_PROPERTY.equals(pair.substring(0, equals).trim())) {
                values.add(pair.substring(equals + 1).trim());
            }
        }
        return values;
    }
}
