package com.recsys.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceEvidenceSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void summarizesSurefireSuitesIntoVersionedDeterministicEvidence() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("reports"));
        Files.writeString(reports.resolve("TEST-com.recsys.LoadTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.recsys.LoadTest" tests="2" failures="1"
                           errors="0" skipped="0" time="1.25">
                  <testcase name="passes" time="0.25"/>
                  <testcase name="fails" time="1.0"><failure message="boom"/></testcase>
                </testsuite>
                """);
        // Surefire may emit auxiliary XML. Only TEST-*.xml suite reports are aggregated.
        Files.writeString(reports.resolve("testng-results.xml"), """
                <testsuite tests="99" failures="0" errors="0" skipped="0" time="99"/>
                """);
        Path output = temp.resolve("evidence.json");
        Path measurements = writeMeasurements(temp.resolve("measurements.json"));

        CommandResult result = runSummarizer(reports, measurements, output);
        assertThat(result.exitCode()).as(result.output()).isEqualTo(1);

        JsonNode evidence = JSON.readTree(output.toFile());
        assertThat(evidence.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(evidence.get("suite").asText()).isEqualTo("load");
        assertThat(evidence.get("environment").isObject()).isTrue();
        assertThat(evidence.at("/environment/javaVersion").asText()).isNotBlank();
        assertThat(evidence.at("/tests/run").asInt()).isEqualTo(2);
        assertThat(evidence.at("/tests/failed").asInt()).isEqualTo(1);
        assertThat(evidence.at("/tests/errors").asInt()).isZero();
        assertThat(evidence.at("/tests/skipped").asInt()).isZero();
        assertThat(evidence.at("/tests/elapsedSeconds").asDouble()).isEqualTo(1.25);
        assertThat(evidence.get("invariantsPassed").asBoolean()).isFalse();
    }

    @Test
    void aggregatesTestsuitesChildrenOnceAndRejectsAnEmptyReportSet() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("variants"));
        Files.writeString(reports.resolve("TEST-aggregate.xml"), """
                <testsuites tests="20" failures="20" errors="20" skipped="20" time="20">
                  <testsuite name="one" tests="1" failures="0" errors="0" skipped="0" time="0.1"/>
                  <testsuite name="two" tests="1" failures="0" errors="0" skipped="1" time="0.2"/>
                </testsuites>
                """);
        Files.writeString(reports.resolve("testng-results.xml"), "<properties/>");
        Path output = temp.resolve("variants.json");

        CommandResult result = runSummarizer(
                reports, writeMeasurements(temp.resolve("variant-measurements.json")), output);
        assertThat(result.exitCode()).as(result.output()).isZero();
        JsonNode evidence = JSON.readTree(output.toFile());
        assertThat(evidence.at("/tests/run").asInt()).isEqualTo(2);
        assertThat(evidence.at("/tests/skipped").asInt()).isEqualTo(1);
        assertThat(evidence.at("/tests/elapsedSeconds").asDouble()).isEqualTo(0.3);
        assertThat(evidence.get("invariantsPassed").asBoolean()).isTrue();

        Path empty = Files.createDirectory(temp.resolve("empty"));
        CommandResult missing = runSummarizer(
                empty, writeMeasurements(temp.resolve("missing-measurements.json")),
                temp.resolve("missing.json"));
        assertThat(missing.exitCode()).as(missing.output()).isNotZero();
        assertThat(missing.output()).contains("no valid TEST-*.xml Surefire reports");
    }

    @Test
    void failsClosedForAnyMalformedOrImpossibleSurefireReport() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("bad-reports"));
        Files.writeString(reports.resolve("TEST-valid.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.1\"/>");
        Path measurements = writeMeasurements(temp.resolve("bad-measurements.json"));

        Files.writeString(reports.resolve("TEST-malformed.xml"), "<testsuite");
        assertFailed(reports, measurements, "malformed");

        Files.writeString(reports.resolve("TEST-malformed.xml"),
                "<testsuite tests=\"one\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.1\"/>");
        assertFailed(reports, measurements, "invalid tests");

        Files.writeString(reports.resolve("TEST-malformed.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        assertFailed(reports, measurements, "missing required time");

        Files.writeString(reports.resolve("TEST-malformed.xml"),
                "<testsuite tests=\"1\" failures=\"2\" errors=\"0\" skipped=\"0\" time=\"0.1\"/>");
        assertFailed(reports, measurements, "exceed tests");

        Files.writeString(reports.resolve("TEST-malformed.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"NaN\"/>");
        assertFailed(reports, measurements, "finite");
    }

    @Test
    void rejectsZeroTestsAndInvalidMeasurementInvariants() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("zero-reports"));
        Files.writeString(reports.resolve("TEST-empty.xml"),
                "<testsuite tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>");
        Path measurements = writeMeasurements(temp.resolve("zero-measurements.json"));
        assertFailed(reports, measurements, "at least one test");

        Files.writeString(reports.resolve("TEST-empty.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>");
        Files.writeString(measurements, validMeasurements().replace(
                "\"admissionBounded\": true", "\"admissionBounded\": false"));
        assertFailed(reports, measurements, "invariant failed");
    }

    @Test
    void rejectsNonFiniteAndImpossibleMeasurementValues() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("measurement-reports"));
        Files.writeString(reports.resolve("TEST-one.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>");
        Path measurements = temp.resolve("invalid-measurements.json");

        Files.writeString(measurements, validMeasurements().replace(
                "\"elapsedMillis\": 1", "\"elapsedMillis\": NaN"));
        assertFailed(reports, measurements, "non-finite");

        Files.writeString(measurements, validMeasurements().replace(
                "\"elapsedMillis\": 1", "\"elapsedMillis\": Infinity"));
        assertFailed(reports, measurements, "non-finite");

        Files.writeString(measurements, validMeasurements().replace(
                "\"accepted\": 2", "\"accepted\": 3"));
        assertFailed(reports, measurements, "impossible concurrency");
    }

    @Test
    void writesFailedEvidenceBeforeReturningNonzeroAndValidatesDockerShape() throws Exception {
        Path reports = Files.createDirectory(temp.resolve("failed-evidence-reports"));
        Files.writeString(reports.resolve("TEST-one.xml"),
                "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>");
        Path failedMeasurements = temp.resolve("failed-measurements.json");
        Files.writeString(failedMeasurements, validMeasurements()
                .replace("\"bulkhead\": 1", "\"bulkhead\": 0")
                .replace("\"bulkheadRejected\": true", "\"bulkheadRejected\": false"));
        Path failedOutput = temp.resolve("failed-evidence.json");

        CommandResult failed = runSummarizer(reports, failedMeasurements, failedOutput);
        assertThat(failed.exitCode()).as(failed.output()).isEqualTo(1);
        assertThat(JSON.readTree(failedOutput.toFile()).get("invariantsPassed").asBoolean())
                .isFalse();

        Path dockerMeasurements = temp.resolve("docker-measurements.json");
        Files.writeString(dockerMeasurements, validDockerMeasurements());
        Path dockerOutput = temp.resolve("docker-evidence.json");
        CommandResult docker =
                runSummarizer(reports, dockerMeasurements, dockerOutput, "docker");
        assertThat(docker.exitCode()).as(docker.output()).isZero();
        JsonNode evidence = JSON.readTree(dockerOutput.toFile());
        assertThat(evidence.get("source").asText()).isEqualTo("schema-test-real-redis");
        assertThat(evidence.at("/applicability/load").asBoolean()).isFalse();
        assertThat(evidence.at("/applicability/redisBoundary").asBoolean()).isTrue();
        assertThat(evidence.at("/measurements/concurrency").isMissingNode()).isTrue();
    }

    private static void assertFailed(Path reports, Path measurements, String message) throws Exception {
        CommandResult result = runSummarizer(reports, measurements, reports.resolve("out.json"));
        assertThat(result.exitCode()).as(result.output()).isNotZero();
        assertThat(result.output()).containsIgnoringCase(message);
    }

    private static CommandResult runSummarizer(Path reports, Path measurements, Path output)
            throws Exception {
        return runSummarizer(reports, measurements, output, "load");
    }

    private static CommandResult runSummarizer(
            Path reports, Path measurements, Path output, String suite) throws Exception {
        Path script = Path.of("scripts", "summarize-resilience-results.py").toAbsolutePath();
        Exception last = null;
        for (String python : pythonCandidates()) {
            try {
                Process process = new ProcessBuilder(
                        python, script.toString(),
                        "--suite", suite,
                        "--reports", reports.toString(),
                        "--measurements", measurements.toString(),
                        "--output", output.toString())
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    return new CommandResult(-1, "summarizer exceeded 10-second deadline");
                }
                return new CommandResult(
                        process.exitValue(), new String(process.getInputStream().readAllBytes()));
            } catch (java.io.IOException error) {
                last = error;
            }
        }
        throw new java.io.IOException("no Python interpreter available", last);
    }

    private static List<String> pythonCandidates() {
        String configured = System.getenv("PYTHON");
        if (configured == null || configured.isBlank()) return List.of("python3", "python");
        if (configured.equals("python3")) return List.of("python3", "python");
        if (configured.equals("python")) return List.of("python", "python3");
        return List.of(configured, "python3", "python");
    }

    private static Path writeMeasurements(Path path) throws Exception {
        return Files.writeString(path, validMeasurements());
    }

    private static String validMeasurements() {
        return """
                {
                  "schemaVersion": 1,
                  "suite": "load",
                  "source": "schema-test-load-probe",
                  "applicability": {
                    "load": true,
                    "redisBoundary": false,
                    "redisBoundaryCoveredBy": "docker"
                  },
                  "measurements": {
                    "concurrency": {"offered": 3, "accepted": 2},
                    "rejections": {"admission": 1, "bulkhead": 1},
                    "degradation": {"total": 2, "degraded": 1, "ratio": 0.5},
                    "timeoutRecovery": {"timeouts": 1, "recovered": true},
                    "gracefulDrain": {"completed": true, "inFlightAfterDrain": 0},
                    "performance": {"elapsedMillis": 1}
                  },
                  "invariants": {
                    "admissionBounded": true,
                    "bulkheadRejected": true,
                    "degradationMeasured": true,
                    "timeoutRecovered": true,
                    "gracefulDrainCompleted": true
                  }
                }
                """;
    }

    private static String validDockerMeasurements() {
        return """
                {
                  "schemaVersion": 1,
                  "suite": "docker",
                  "source": "schema-test-real-redis",
                  "applicability": {
                    "load": false,
                    "loadCoveredBy": "load",
                    "redisBoundary": true
                  },
                  "measurements": {
                    "redisBoundary": {
                      "limit": 100,
                      "initialAllowed": 100,
                      "attempted": 100,
                      "allowed": 0,
                      "rejected": 100
                    },
                    "performance": {"elapsedMillis": 1}
                  },
                  "invariants": {"redisBoundaryEnforced": true}
                }
                """;
    }

    private record CommandResult(int exitCode, String output) {}
}
