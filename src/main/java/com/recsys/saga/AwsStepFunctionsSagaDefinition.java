package com.recsys.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an Amazon States Language definition for the same ordered saga used by
 * {@link SagaOrchestrator}. Each forward Task catches errors into reverse-order
 * compensation states and then fails the execution, matching the Saga pattern.
 */
public final class AwsStepFunctionsSagaDefinition {
    private AwsStepFunctionsSagaDefinition() {
    }

    public static String render(SagaDefinition definition) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"Comment\": \"Saga orchestration for ").append(escape(definition.name())).append("\",\n")
                .append("  \"StartAt\": \"").append(stateName(definition.steps().get(0).name())).append("\",\n")
                .append("  \"States\": {\n");

        List<String> states = new ArrayList<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            SagaStep step = definition.steps().get(i);
            String next = i + 1 == definition.steps().size()
                    ? "\"End\": true"
                    : "\"Next\": \"" + stateName(definition.steps().get(i + 1).name()) + "\"";
            states.add(taskState(step, next, compensationStart(definition.steps(), i)));
        }

        for (int i = definition.steps().size() - 1; i >= 0; i--) {
            SagaStep step = definition.steps().get(i);
            if (!step.hasCompensation()) {
                continue;
            }
            states.add(compensationState(step, previousCompensation(definition.steps(), i)));
        }

        states.add("    \"SagaFailed\": {\n"
                + "      \"Type\": \"Fail\",\n"
                + "      \"Error\": \"SagaFailed\",\n"
                + "      \"Cause\": \"A saga step failed and completed steps were compensated\"\n"
                + "    }");
        json.append(String.join(",\n", states));
        json.append("\n  }\n}");
        return json.toString();
    }

    private static String taskState(SagaStep step, String next, String compensationStart) {
        StringBuilder state = new StringBuilder();
        state.append("    \"").append(stateName(step.name())).append("\": {\n")
                .append("      \"Type\": \"Task\",\n")
                .append("      \"Resource\": \"").append(escape(step.awsResourceArn())).append("\",\n")
                .append("      \"Retry\": [{\n")
                .append("        \"ErrorEquals\": [\"States.ALL\"],\n")
                .append("        \"IntervalSeconds\": ").append(Math.max(1, step.backoff().toSeconds())).append(",\n")
                .append("        \"MaxAttempts\": ").append(step.maxAttempts()).append(",\n")
                .append("        \"BackoffRate\": 2.0,\n")
                .append("        \"MaxDelaySeconds\": 30,\n")
                .append("        \"JitterStrategy\": \"FULL\"\n")
                .append("      }],\n")
                .append("      \"Catch\": [{\n")
                .append("        \"ErrorEquals\": [\"States.ALL\"],\n")
                .append("        \"ResultPath\": \"$.sagaError\",\n")
                .append("        \"Next\": \"").append(compensationStart).append("\"\n")
                .append("      }],\n")
                .append("      ").append(next).append("\n")
                .append("    }");
        return state.toString();
    }

    private static String compensationState(SagaStep step, String next) {
        return "    \"" + compensationStateName(step.name()) + "\": {\n"
                + "      \"Type\": \"Task\",\n"
                + "      \"Resource\": \"" + escape(step.compensationResourceArn()) + "\",\n"
                + "      \"Retry\": [{\n"
                + "        \"ErrorEquals\": [\"States.ALL\"],\n"
                + "        \"IntervalSeconds\": " + Math.max(1, step.backoff().toSeconds()) + ",\n"
                + "        \"MaxAttempts\": " + step.maxAttempts() + ",\n"
                + "        \"BackoffRate\": 2.0,\n"
                + "        \"MaxDelaySeconds\": 30,\n"
                + "        \"JitterStrategy\": \"FULL\"\n"
                + "      }],\n"
                + "      \"Next\": \"" + next + "\"\n"
                + "    }";
    }

    private static String compensationStart(List<SagaStep> steps, int failedStepIndex) {
        for (int i = failedStepIndex - 1; i >= 0; i--) {
            if (steps.get(i).hasCompensation()) {
                return compensationStateName(steps.get(i).name());
            }
        }
        return "SagaFailed";
    }

    private static String previousCompensation(List<SagaStep> steps, int currentIndex) {
        for (int i = currentIndex - 1; i >= 0; i--) {
            if (steps.get(i).hasCompensation()) {
                return compensationStateName(steps.get(i).name());
            }
        }
        return "SagaFailed";
    }

    private static String stateName(String step) {
        return toPascal(step);
    }

    private static String compensationStateName(String step) {
        return "Compensate" + toPascal(step);
    }

    private static String toPascal(String value) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : value.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
