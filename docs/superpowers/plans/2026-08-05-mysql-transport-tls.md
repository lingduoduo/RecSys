# MySQL Transport TLS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refuse to construct MySQL settings that would connect over unverified transport, when MySQL is enabled.

**Architecture:** A third `if (enabled && …)` validation in `MySqlConnectionSettings`'s compact constructor, beside the password and cursor-key checks already there, requiring `sslMode=VERIFY_IDENTITY`. A loopback exemption keeps Testcontainers and local development working without any flag a manifest could set wrong.

**Tech Stack:** Java 17, JUnit 5, AssertJ, SnakeYAML, Maven, Kustomize, MySQL Connector/J 8.4.

## Global Constraints

- Build/test with JDK 17: `JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn ...`. Newer JDKs fail a clean compile of two pre-existing files — a known pre-existing condition.
- Design doc: `docs/superpowers/specs/2026-08-05-mysql-transport-tls-design.md`. Read it before starting.
- The guard lives in the **compact constructor**, not in `fromEnv`. `CatalogComponent.fromEnvironment()` calls `fromEnv()` *before* checking `settings.enabled()`, so a guard there would throw on every service running the default configuration, where `MYSQL_ENABLED=false`.
- Throw `IllegalArgumentException`, matching the two sibling validations in the same constructor — not `IllegalStateException`.
- **Only `sslMode=VERIFY_IDENTITY` passes.** Absent, `DISABLED`, `PREFERRED`, `REQUIRED`, `VERIFY_CA` are all rejected. If `sslMode` appears more than once, every occurrence must be `VERIFY_IDENTITY`.
- A URL carrying the deprecated `useSSL` is rejected, with a message directing the operator to `sslMode`.
- **The loopback exemption skips the entire transport requirement** — the `sslMode` rule and the `useSSL` rejection alike — for hosts `localhost`, `127.0.0.1` and `[::1]`. `application.yml`'s local default is `jdbc:mysql://localhost:3306/recsys?useSSL=false&…`, so an exemption covering only `sslMode` would reject the configuration this repo ships for local development.
- No environment-variable opt-out. The exemption is scoped by host precisely so there is no flag a manifest could set wrong — that was the shape of the Redis finding this mirrors.
- Tests must be added to the `resilience` profile in `pom.xml`, which is what the PR gate runs.
- Never merge to `main` directly — this work ships as a PR.
- Branch: `feat/mysql-transport-tls` (already created; the spec is already committed on it).

---

### Task 1: Require verified TLS when MySQL is enabled

**Files:**
- Modify: `src/main/java/com/recsys/infrastructure/persistence/MySqlConnectionSettings.java:32-47`
- Test: `src/test/java/com/recsys/infrastructure/persistence/MySqlConnectionSettingsTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `MySqlConnectionSettings`'s compact constructor now throws `IllegalArgumentException` for an enabled, non-loopback URL that does not require `sslMode=VERIFY_IDENTITY`. Task 2 relies on that behavior when it sets the manifest URL.

Facts verified against the repo, so you need not rediscover them:

- The compact constructor already ends with two `if (enabled && …)` throws — for `MYSQL_PASSWORD` and `MYSQL_CURSOR_SIGNING_KEY`. Yours is the third, in the same style.
- `normalizeUrl(url)` runs on the first line of that constructor, so parse the **normalized** `url` local, not the parameter.
- `DEFAULT_URL` (used by `disabled()`) is a `localhost` URL carrying `useSSL=false`. It must keep working — `disabled()` passes `enabled=false`, and the loopback exemption covers it even if someone enables it locally.
- Testcontainers-based tests construct settings from `MYSQL.getJdbcUrl()`, which is a `localhost` URL with a mapped port, so they are covered by the exemption and need no change.
- `MySqlConnectionSettingsTest` has fixtures using `jdbc:mysql://db.internal:3306/recsys`, `jdbc:mysql://db.internal:3306/catalog` and `jdbc:mysql://db/catalog` with `MYSQL_ENABLED=true`. Those are non-loopback and will now be rejected — Step 5 updates them.

- [ ] **Step 1: Write the failing tests**

Add to `MySqlConnectionSettingsTest`:

```java
    @Test
    void rejectsAnEnabledConnectionThatDoesNotVerifyTls() {
        assertThatThrownBy(() -> enabledWithUrl("jdbc:mysql://db.internal:3306/recsys"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void rejectsEverySslModeWeakerThanVerifyIdentity() {
        // PREFERRED is Connector/J 8's default and the reason this guard exists: it negotiates TLS
        // if offered, verifies no certificate, and falls back to plaintext in silence.
        for (String mode : new String[]{"DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA"}) {
            assertThatThrownBy(() -> enabledWithUrl(
                    "jdbc:mysql://db.internal:3306/recsys?sslMode=" + mode))
                    .as("sslMode=%s must be rejected", mode)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sslMode=VERIFY_IDENTITY");
        }
    }

    @Test
    void acceptsVerifyIdentity() {
        MySqlConnectionSettings settings =
                enabledWithUrl("jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY");
        assertThat(settings.url()).contains("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void rejectsTheDeprecatedUseSslPropertyEvenAlongsideVerifyIdentity() {
        // Where both appear, Connector/J lets sslMode win — so a URL carrying both reads as one
        // thing and behaves as another. Refusing the ambiguity is cheaper than resolving it.
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.internal:3306/recsys?useSSL=true&sslMode=VERIFY_IDENTITY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("useSSL");
    }

    @Test
    void rejectsDisagreeingDuplicateSslModes() {
        assertThatThrownBy(() -> enabledWithUrl(
                "jdbc:mysql://db.internal:3306/recsys?sslMode=VERIFY_IDENTITY&sslMode=DISABLED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslMode=VERIFY_IDENTITY");
    }

    @Test
    void aDisabledConnectionIsNotSubjectToTheTransportRule() {
        // The disabled case must stay inert: MYSQL_ENABLED=false is the default in k8s/base, and a
        // guard that fired here would stop every service from starting.
        MySqlConnectionSettings settings = new MySqlConnectionSettings(
                false, "jdbc:mysql://db.internal:3306/recsys", "app", "", 2, 2, 50, "");
        assertThat(settings.enabled()).isFalse();
    }

    @Test
    void loopbackHostsAreExemptFromTheTransportRule() {
        // Not an opt-out: a loopback connection has no network segment to intercept, and the host
        // in Kubernetes is "mysql" or an RDS endpoint, so this cannot be reached from a manifest.
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
            assertThat(enabledWithUrl("jdbc:mysql://" + host + ":3306/recsys").enabled())
                    .as("%s must be exempt", host)
                    .isTrue();
        }
    }

    @Test
    void theLocalDevelopmentDefaultStillConstructs() {
        // The exact URL application.yml ships. It is loopback AND carries useSSL=false, so the
        // exemption has to cover the useSSL rejection too, not only the sslMode rule.
        assertThat(enabledWithUrl("jdbc:mysql://localhost:3306/recsys?useSSL=false"
                + "&serverTimezone=UTC&connectTimeout=1000&socketTimeout=2000").enabled()).isTrue();
    }

    @Test
    void aNonLoopbackHostIsNotExempt() {
        // Guards against an exemption written loosely enough to match everything.
        assertThatThrownBy(() -> enabledWithUrl("jdbc:mysql://localhost.evil.example:3306/recsys"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An enabled settings object with the given URL and otherwise-valid required values. */
    private static MySqlConnectionSettings enabledWithUrl(String url) {
        return new MySqlConnectionSettings(true, url, "app", "secret", 2, 2, 50,
                "0123456789abcdef0123456789abcdef");
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` if absent.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MySqlConnectionSettingsTest
```

Expected: the new rejection tests fail — no guard exists yet. `aDisabledConnectionIsNotSubjectToTheTransportRule` and the loopback tests should already pass, since nothing rejects anything today.

- [ ] **Step 3: Add the guard**

In `MySqlConnectionSettings`, add after the `cursorSigningKey` check in the compact constructor:

```java
        if (enabled) {
            requireVerifiedTransport(url);
        }
```

Add the helper and its constants to the class:

```java
    private static final Pattern URL_SSL_MODE = Pattern.compile("(?i)[?&;]sslMode=([^&;]*)");
    private static final Pattern URL_USE_SSL = Pattern.compile("(?i)[?&;]useSSL=");
    private static final Pattern URL_HOST = Pattern.compile("(?i)jdbc:mysql://([^/?;]+)");

    /**
     * Refuses a connection that would not verify the server it talks to.
     *
     * <p>Connector/J 8 defaults to {@code sslMode=PREFERRED}: it negotiates TLS when the server
     * offers it, verifies no certificate, and falls back to plaintext in silence. From here a
     * plaintext connection is indistinguishable from an encrypted one, which is why this refuses
     * rather than warns — the same reasoning as {@code LettuceClientFactory.requireAuthentication}.
     *
     * <p>{@code VERIFY_IDENTITY} rather than {@code REQUIRED} because REQUIRED encrypts without
     * verifying, which stops silent plaintext but not an active man-in-the-middle. Against RDS it
     * costs no extra provisioning: Amazon's CAs are already in the JVM truststore.
     *
     * <p>Loopback hosts are exempt in full, including the {@code useSSL} rejection — a loopback
     * connection has no network segment to intercept, and {@code application.yml}'s local default
     * carries {@code useSSL=false}. This is deliberately a host test rather than an opt-out flag:
     * the host in Kubernetes is {@code mysql} or an RDS endpoint, so no manifest can reach it.
     */
    private static void requireVerifiedTransport(String url) {
        if (isLoopback(url)) {
            return;
        }
        if (URL_USE_SSL.matcher(url).find()) {
            throw new IllegalArgumentException(
                    "MYSQL_URL uses the deprecated useSSL property; MySQL Connector/J 8 lets "
                            + "sslMode override it silently. Remove useSSL and set "
                            + "sslMode=VERIFY_IDENTITY.");
        }
        Matcher modes = URL_SSL_MODE.matcher(url);
        boolean sawMode = false;
        while (modes.find()) {
            sawMode = true;
            if (!"VERIFY_IDENTITY".equalsIgnoreCase(modes.group(1).trim())) {
                throw verifyIdentityRequired();
            }
        }
        if (!sawMode) {
            throw verifyIdentityRequired();
        }
    }

    private static IllegalArgumentException verifyIdentityRequired() {
        return new IllegalArgumentException(
                "MYSQL_URL must set sslMode=VERIFY_IDENTITY when MySQL is enabled. Connector/J "
                        + "defaults to PREFERRED, which falls back to plaintext without error and "
                        + "verifies no certificate, so the connection carrying catalog rows, outbox "
                        + "events and saga state would be unprotected and look identical to a "
                        + "protected one.");
    }

    private static boolean isLoopback(String url) {
        Matcher host = URL_HOST.matcher(url);
        if (!host.find()) {
            return false;
        }
        String authority = host.group(1);
        int colon = authority.lastIndexOf(':');
        String hostOnly = colon > 0 && authority.indexOf(']') < colon
                ? authority.substring(0, colon)
                : authority;
        return hostOnly.equalsIgnoreCase("localhost")
                || hostOnly.equals("127.0.0.1")
                || hostOnly.equals("[::1]");
    }
```

Add `import java.util.regex.Matcher;` if absent — `Pattern` is already imported.

- [ ] **Step 4: Run the new tests**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MySqlConnectionSettingsTest
```

Expected: the new tests pass. Pre-existing tests in this class that use `db.internal` or `db` with
`MYSQL_ENABLED=true` will now fail — that is Step 5, not a defect.

- [ ] **Step 5: Update the fixtures the rule now rejects**

In `MySqlConnectionSettingsTest`, the fixtures using a non-loopback host with `MYSQL_ENABLED=true`
need `?sslMode=VERIFY_IDENTITY` appended (or `&sslMode=VERIFY_IDENTITY` where a query string already
exists). Known ones: `fromEnv_readsExplicitMysqlSettings` (`jdbc:mysql://db.internal:3306/recsys`,
and its `assertThat(settings.url())` expectation), and the `db.internal:3306/catalog` and
`db/catalog` fixtures in the description and validation tests.

Where a test asserts on `safeDescription()` output, update the expected substring to match the new
URL rather than loosening the assertion.

`safeDescription_redactsJdbcUrlCredentialsButPreservesLocation` uses a URL containing `useSSL=true`.
That URL is now rejected — change it to `sslMode=VERIFY_IDENTITY`, keeping the `user=`/`password=`
parameters the test is actually about.

- [ ] **Step 6: Run the whole suite and fix every fixture the rule rejects**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Any failure whose message contains `sslMode=VERIFY_IDENTITY` or `useSSL` is a fixture that
constructs enabled settings with a non-loopback plaintext URL. Add `sslMode=VERIFY_IDENTITY` to that
URL. Do **not** loosen the guard, and do **not** switch a fixture to `enabled=false` to dodge it —
that would delete the coverage the fixture existed for.

Testcontainers suites use `MYSQL.getJdbcUrl()`, which is a `localhost` URL, so they should not
appear. If one does, report it — it means the exemption is not matching a shape it should.

List every file you changed in your report.

- [ ] **Step 7: Add the test to the PR gate and commit**

In `pom.xml`, inside the `resilience` profile's `<includes>`:

```xml
                <!-- The MySQL connection carries catalog rows, outbox events and saga state.
                     Connector/J defaults to a mode that falls back to plaintext without error,
                     so the transport requirement is only real if something pins it. -->
                <include>**/persistence/MySqlConnectionSettingsTest.java</include>
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add src/main/java/com/recsys/infrastructure/persistence/MySqlConnectionSettings.java \
        src/test/java/com/recsys/infrastructure/persistence/MySqlConnectionSettingsTest.java pom.xml
git commit -m "feat: require verified TLS on the MySQL connection when enabled"
```

Stage any other fixture files Step 6 required, and name them in the commit body.

---

### Task 2: Set the manifest URL, pin it, and document the rule

**Files:**
- Modify: `k8s/base/configmap.yaml:101`
- Test: `src/test/java/com/recsys/infrastructure/k8s/MySqlTlsManifestTest.java` (create)
- Modify: `pom.xml`, `docs/system_design/20_AuthN_AuthZ.md`, `.claude/CLAUDE.md`

**Interfaces:**
- Consumes: the constructor guard from Task 1.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/recsys/infrastructure/k8s/MySqlTlsManifestTest.java`:

```java
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
```

- [ ] **Step 2: Run it to verify it fails**

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest=MySqlTlsManifestTest
```

Expected: FAIL — `k8s/base/configmap.yaml` still has a URL with no `sslMode`.

- [ ] **Step 3: Set the manifest URL**

In `k8s/base/configmap.yaml`, replace line 101:

```yaml
  # sslMode=VERIFY_IDENTITY is required, not decorative: Connector/J defaults to PREFERRED, which
  # falls back to plaintext without error and verifies no certificate. MySqlConnectionSettings
  # refuses to construct without it when MYSQL_ENABLED=true.
  MYSQL_URL: "jdbc:mysql://mysql:3306/recsys?sslMode=VERIFY_IDENTITY"
```

- [ ] **Step 4: Check nothing else parses that URL**

`src/test/java/com/recsys/infrastructure/k8s/Upstream.java` strips the `jdbc:` prefix and parses the
rest as a URI to extract host and port, and `NetworkPolicyEgressManifestTest` and `UpstreamTest`
both consume it. A query string should not disturb host/port extraction, but confirm rather than
assume:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='UpstreamTest,NetworkPolicyEgressManifestTest,MySqlTlsManifestTest'
```

Expected: PASS. If host/port extraction breaks on the query string, fix `Upstream`'s parsing — do
not remove the parameter from the manifest.

Then confirm the overlays still build:

```bash
kubectl kustomize k8s/base > /dev/null && echo "base OK"
kubectl kustomize k8s/eks > /dev/null && echo "us-east-1 OK"
kubectl kustomize k8s/eks-us-west-2 > /dev/null && echo "us-west-2 OK"
```

If `kubectl` is unavailable, say so in your report rather than skipping the check silently.

- [ ] **Step 5: Document it**

In `docs/system_design/20_AuthN_AuthZ.md`, extend the existing **"Data-tier authentication"** section
— do not add a new `##` heading and do not renumber. Cover:

- MySQL requires `sslMode=VERIFY_IDENTITY` when `MYSQL_ENABLED=true`; construction refuses otherwise.
- Why `VERIFY_IDENTITY` and not `REQUIRED`, and that RDS needs no truststore work because Amazon's
  CAs are already trusted — the same reasoning `k8s/eks/redis-elasticache-patch.yaml` records.
- The loopback exemption, and why it is a host test rather than an opt-out flag: no manifest can
  reach it, which is the shape the Redis finding lacked.
- The consequence: once MySQL is enabled, a server without a verifiable certificate stops 6010 and
  7010 from starting. Bounded by `MYSQL_ENABLED=false` being the default.
- That nothing here deploys MySQL, so this is unverified against a real server.

Verify each claim against the code before writing it. Three recent branches shipped false statements
in this file.

In `.claude/CLAUDE.md`, note beside the MySQL environment variables that `MYSQL_URL` must carry
`sslMode=VERIFY_IDENTITY` when MySQL is enabled, and that loopback hosts are exempt.

- [ ] **Step 6: Add to the gate, verify, and commit**

In `pom.xml`, inside the `resilience` profile's `<includes>`:

```xml
                <include>**/k8s/MySqlTlsManifestTest.java</include>
```

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Dtest='DocumentationIndexTest,DocumentedMechanismTest'
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
git add k8s/base/configmap.yaml src/test/java/com/recsys/infrastructure/k8s/MySqlTlsManifestTest.java \
        pom.xml docs/system_design/20_AuthN_AuthZ.md .claude/CLAUDE.md
git commit -m "feat: require sslMode=VERIFY_IDENTITY in the MySQL manifest URL"
```

Do not push and do not open a PR; the controller handles that.

---

## Verification

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test -Presilience
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn test
```

Both must pass — the full suite matters here because the guard changes what a valid settings object
is, and the fixtures affected are spread across persistence, outbox, saga and server-wiring tests.
