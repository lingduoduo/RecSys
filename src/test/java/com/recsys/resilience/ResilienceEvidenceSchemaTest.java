package com.recsys.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        Path script = Path.of("scripts", "summarize-resilience-results.py").toAbsolutePath();

        Process process = new ProcessBuilder(
                "python3", script.toString(),
                "--suite", "load",
                "--reports", reports.toString(),
                "--output", output.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String commandOutput = new String(process.getInputStream().readAllBytes());

        assertThat(finished).as(commandOutput).isTrue();
        assertThat(process.exitValue()).as(commandOutput).isZero();

        JsonNode evidence = JSON.readTree(output.toFile());
        assertThat(evidence.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(evidence.get("suite").asText()).isEqualTo("load");
        assertThat(evidence.get("environment").isObject()).isTrue();
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
        Files.writeString(reports.resolve("TEST-metadata.xml"), "<properties/>");
        Path output = temp.resolve("variants.json");

        Process result = runSummarizer(reports, output);
        assertThat(result.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.exitValue())
                .as(new String(result.getInputStream().readAllBytes()))
                .isZero();
        JsonNode evidence = JSON.readTree(output.toFile());
        assertThat(evidence.at("/tests/run").asInt()).isEqualTo(2);
        assertThat(evidence.at("/tests/skipped").asInt()).isEqualTo(1);
        assertThat(evidence.at("/tests/elapsedSeconds").asDouble()).isEqualTo(0.3);
        assertThat(evidence.get("invariantsPassed").asBoolean()).isTrue();

        Path empty = Files.createDirectory(temp.resolve("empty"));
        Process missing = runSummarizer(empty, temp.resolve("missing.json"));
        assertThat(missing.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String error = new String(missing.getInputStream().readAllBytes());
        assertThat(missing.exitValue()).as(error).isNotZero();
        assertThat(error).contains("no valid TEST-*.xml Surefire reports");
    }

    private static Process runSummarizer(Path reports, Path output) throws Exception {
        Path script = Path.of("scripts", "summarize-resilience-results.py").toAbsolutePath();
        return new ProcessBuilder(
                "python3", script.toString(),
                "--suite", "load",
                "--reports", reports.toString(),
                "--output", output.toString())
                .redirectErrorStream(true)
                .start();
    }
}
