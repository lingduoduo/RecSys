package com.recsys.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The IRSA comments are provisioning instructions; this pins the source facts they assert.
 *
 * <p>{@code k8s/eks-shared/gateway-irsa-sa.yaml}, {@code k8s/eks-shared/patches/irsa-model-serving.yaml},
 * {@code k8s/base/online-serving.yaml} and {@code k8s/base/outbox-relay-deployment.yaml} tell whoever
 * provisions an IAM role which AWS permissions each workload needs. Nobody applies them by machine, so
 * they are only as good as the claims in them — and a wrong claim fails late and expensively: an
 * over-grant hands a public-facing pod permissions it never uses, an under-grant surfaces as
 * {@code AccessDenied} the first time a feature flag is flipped in production.
 *
 * <p>They were wrong twice. The gateway comment asked for two {@code servicediscovery} and three
 * {@code route53} permissions, including {@code ChangeResourceRecordSets}, for a workload that makes no
 * AWS API call at all. And online serving — a fourth SQS-calling workload — went undocumented through
 * two reviews of the change that corrected the other three, because nothing checked the claims
 * mechanically and each review re-derived the workload list by hand.
 *
 * <p><strong>What this pins, and what it deliberately does not.</strong> Asserting that a comment
 * contains particular words would pin prose: it would break every time the wording improved, while
 * still passing for a comment that is fluent and false. So this asserts the two facts underneath the
 * wording instead:
 *
 * <ol>
 *   <li>{@link #sqsIsTheOnlyAwsServiceClientInMainSource()} — the only AWS SDK <em>service</em> client
 *       anywhere in {@code src/main/java} is {@code sqs}. This is the claim the gateway comment rests
 *       on; adding a {@code servicediscovery}, {@code route53} or {@code s3} client falsifies it and
 *       fails here, at the commit that introduces the client rather than at the review that missed it.
 *   <li>{@link #everySqsSendCallSiteIsAttributedToAWorkload()} — the set of files that send to SQS, and
 *       which action each one calls, equals {@link #SEND_CALL_SITES}. Every entry carries a note naming
 *       the workload it belongs to, so a new caller fails the build until somebody says which role
 *       needs which permission. This is the check that would have caught the online-serving omission.
 * </ol>
 *
 * <p>The action distinction in {@link #SEND_CALL_SITES} is load-bearing, not decoration:
 * {@code sqs:SendMessage} and {@code sqs:SendMessageBatch} are distinct IAM actions, and a role granting
 * only the first fails against a publisher that batches. That is exactly the error the model-serving
 * comment shipped with in its first draft.
 *
 * <p><strong>How a fact is found.</strong> Both scans match text anywhere in a source file — the SDK
 * package prefix, and the bare {@code sendMessage(} / {@code sendMessageBatch(} call shapes — rather
 * than a parsed import or resolved call. That deliberately over-matches, exactly as
 * {@link OperatorTokenManifestTest}'s marker does: a fully-qualified client built inline, or a send
 * behind a helper, is a real permission need that an import-only scan would miss, and the trade is
 * asymmetric. A false positive (a javadoc naming a client, a string literal) is a loud failure a human
 * clears in one line by adding the entry with a note saying so. A false negative is a workload sending
 * to a queue its role cannot write, discovered in production.
 *
 * <p><strong>Scope.</strong> Both checks read {@code src/main/java} text only — no Redis, no Docker, no
 * timing. They are one-directional in the same sense as {@link OperatorTokenManifestTest}: they prove
 * the comments are not missing a caller, never that a comment's prose is accurate. Wording accuracy
 * stays a review responsibility. Nor is the attribution in each note verified — nothing checks that
 * {@code SqsAsyncEventPublisher} really is reached from the two workloads its note names; a wrong note
 * would be a passing test and a misleading comment. The note exists to force the question at review
 * time, which is more than existed before. Test sources are not scanned: a fake or a test-only client
 * needs no IAM.
 */
class IrsaPermissionSourceFactsTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * Matches a reference to any AWS SDK v2 service client package, in an import or a fully-qualified
     * use. The captured group is the service name — {@code sqs}, {@code s3}, {@code route53}, …
     */
    private static final Pattern AWS_SERVICE_PACKAGE =
            Pattern.compile("software\\.amazon\\.awssdk\\.services\\.([a-zA-Z0-9]+)");

    /**
     * The one service the manifests account for. Everything the comments say about the gateway — and
     * the whole "no IAM needed" conclusion — depends on nothing else appearing here.
     */
    private static final Set<String> DOCUMENTED_SERVICES = Set.of("sqs");

    private static final String SEND_MESSAGE = "sendMessage";
    private static final String SEND_MESSAGE_BATCH = "sendMessageBatch";

    /**
     * Every file that sends to SQS, the action(s) it calls, and the workload whose IAM role therefore
     * needs that permission. Derived from the source, and the reason each manifest comment says what it
     * says. Keep the note honest: it is what a reviewer reads when this test fails.
     */
    private static final Map<String, CallSite> SEND_CALL_SITES = Map.of(
            "SqsOutboxDeliveryAdapter.java", new CallSite(
                    Set.of(SEND_MESSAGE),
                    "outbox relay (k8s/base/outbox-relay-deployment.yaml) — constructed by "
                            + "OutboxRelayCommand under SAGA_EVENTS_SQS_ENABLED + a non-blank "
                            + "SAGA_EVENTS_SQS_QUEUE_URL, both off in k8s/base/configmap.yaml; the "
                            + "Deployment has no serviceAccountName, so there is no role to grant on yet"),
            "SqsAsyncEventPublisher.java", new CallSite(
                    Set.of(SEND_MESSAGE_BATCH),
                    "model serving (ModelEventConfig.abExposurePublisher, RECSYS_EVENTS_SQS_*, "
                            + "k8s/eks-shared/patches/irsa-model-serving.yaml) and online serving "
                            + "(AsyncEventPublisherFactory.fromEnvironment(\"ONLINE_EVENTS\"), "
                            + "ONLINE_EVENTS_SQS_*, k8s/base/online-serving.yaml) — batches "
                            + "unconditionally, so both need SendMessageBatch and not SendMessage"),
            "SqsSagaEventPublisher.java", new CallSite(
                    Set.of(SEND_MESSAGE),
                    "no workload — reachable only through SagaEventPublishers.fromEnvironment, which "
                            + "nothing in src/main/java calls. Cite it as a live permission need only "
                            + "once something wires it"));

    /** The SQS send actions a single source file calls, and who needs the matching permission. */
    private record CallSite(Set<String> actions, String workloadNote) {}

    @Test
    void sqsIsTheOnlyAwsServiceClientInMainSource() throws IOException {
        Map<String, Set<String>> byService = new TreeMap<>();
        for (Path file : javaSources()) {
            Matcher matcher = AWS_SERVICE_PACKAGE.matcher(Files.readString(file));
            while (matcher.find()) {
                byService.computeIfAbsent(matcher.group(1), s -> new TreeSet<>())
                        .add(file.getFileName().toString());
            }
        }

        Map<String, Set<String>> undocumented = new TreeMap<>(byService);
        undocumented.keySet().removeAll(DOCUMENTED_SERVICES);

        assertThat(undocumented)
                .describedAs("These AWS SDK service clients are used in %s, but every IRSA comment in "
                        + "k8s/ is written on the premise that SQS is the only one — the gateway's "
                        + "role, in particular, is documented as needing no permissions BECAUSE the "
                        + "gateway calls no AWS API. A new service client means some workload now "
                        + "needs IAM actions nobody has written down. Update the manifest comment for "
                        + "the workload that uses it, then add the service here.", SOURCE_ROOT)
                .isEmpty();
    }

    @Test
    void everySqsSendCallSiteIsAttributedToAWorkload() throws IOException {
        Map<String, Set<String>> found = new TreeMap<>();
        for (Path file : javaSources()) {
            Set<String> actions = sendActionsIn(Files.readString(file));
            if (!actions.isEmpty()) {
                found.put(file.getFileName().toString(), actions);
            }
        }

        Set<String> unattributed = new TreeSet<>(found.keySet());
        unattributed.removeAll(SEND_CALL_SITES.keySet());
        assertThat(unattributed)
                .describedAs("These files send to SQS but no SEND_CALL_SITES entry says which "
                        + "workload's IAM role needs the permission. A workload that sends to SQS "
                        + "with no role granting it fails with AccessDenied the first time its flag "
                        + "is enabled — which is how online serving stayed undocumented through two "
                        + "reviews. Add the entry AND the manifest comment for its workload.")
                .isEmpty();

        Set<String> stale = new TreeSet<>(SEND_CALL_SITES.keySet());
        stale.removeAll(found.keySet());
        assertThat(stale)
                .describedAs("SEND_CALL_SITES claims these files send to SQS, but the scan no longer "
                        + "finds a send in them. Re-derive from source: the send moved or was removed "
                        + "(so a manifest comment now over-states what its role needs), or the file "
                        + "was renamed and this map's key must follow.")
                .isEmpty();

        List<String> actionMismatches = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
            CallSite expected = SEND_CALL_SITES.get(entry.getKey());
            if (expected != null && !expected.actions().equals(entry.getValue())) {
                actionMismatches.add(entry.getKey() + ": calls " + entry.getValue()
                        + ", SEND_CALL_SITES expects " + expected.actions()
                        + " (needed by: " + expected.workloadNote() + ")");
            }
        }
        assertThat(actionMismatches)
                .describedAs("sqs:SendMessage and sqs:SendMessageBatch are distinct IAM actions. A "
                        + "publisher that switches between them silently invalidates the action named "
                        + "in its workload's manifest comment, and a role granting the other one "
                        + "fails closed with AccessDenied. Fix the comment and this map together.")
                .isEmpty();
    }

    /**
     * The SQS send actions a source file calls. The trailing {@code (} keeps {@code sendMessageBatch}
     * from also counting as {@code sendMessage}, so the two actions stay distinguishable.
     */
    private static Set<String> sendActionsIn(String source) {
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put(SEND_MESSAGE + "(", SEND_MESSAGE);
        markers.put(SEND_MESSAGE_BATCH + "(", SEND_MESSAGE_BATCH);

        Set<String> actions = new TreeSet<>();
        markers.forEach((marker, action) -> {
            if (source.contains(marker)) {
                actions.add(action);
            }
        });
        return actions;
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
