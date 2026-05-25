package com.recsys.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a Step Functions definition for Try/Confirm/Cancel orchestration.
 */
public final class AwsTccStepFunctionsSagaDefinition {
    private AwsTccStepFunctionsSagaDefinition() {
    }

    public static String render(SagaDefinition definition) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"Comment\": \"TCC saga orchestration for ").append(escape(definition.name())).append("\",\n")
                .append("  \"StartAt\": \"Try").append(stateName(definition.steps().get(0).name())).append("\",\n")
                .append("  \"States\": {\n");

        List<String> states = new ArrayList<>();
        for (int i = 0; i < definition.steps().size(); i++) {
            SagaStep step = definition.steps().get(i);
            String next = i + 1 == definition.steps().size()
                    ? "Confirm" + stateName(definition.steps().get(0).name())
                    : "Try" + stateName(definition.steps().get(i + 1).name());
            states.add(taskState("Try" + stateName(step.name()), step.awsResourceArn(), next, cancelStart(definition.steps(), i), step));
        }

        for (int i = 0; i < definition.steps().size(); i++) {
            SagaStep step = definition.steps().get(i);
            String next = i + 1 == definition.steps().size()
                    ? "SagaCompleted"
                    : "Confirm" + stateName(definition.steps().get(i + 1).name());
            states.add(taskState("Confirm" + stateName(step.name()), step.confirmResourceArn(), next, "ManualReconciliationRequired", step));
        }

        for (int i = definition.steps().size() - 1; i >= 0; i--) {
            SagaStep step = definition.steps().get(i);
            if (!step.hasCompensation()) {
                continue;
            }
            String next = previousCancel(definition.steps(), i);
            states.add(taskState("Cancel" + stateName(step.name()), step.compensationResourceArn(), next, "ManualReconciliationRequired", step));
        }

        states.add("    \"SagaCompleted\": {\n"
                + "      \"Type\": \"Succeed\"\n"
                + "    }");
        states.add("    \"SagaCancelled\": {\n"
                + "      \"Type\": \"Fail\",\n"
                + "      \"Error\": \"SagaCancelled\",\n"
                + "      \"Cause\": \"Try phase failed and reservations were cancelled\"\n"
                + "    }");
        states.add("    \"ManualReconciliationRequired\": {\n"
                + "      \"Type\": \"Fail\",\n"
                + "      \"Error\": \"ManualReconciliationRequired\",\n"
                + "      \"Cause\": \"TCC confirm or cancel failed after retries\"\n"
                + "    }");

        json.append(String.join(",\n", states));
        json.append("\n  }\n}");
        return json.toString();
    }

    private static String taskState(String name, String resourceArn, String next, String catchNext, SagaStep step) {
        return "    \"" + name + "\": {\n"
                + "      \"Type\": \"Task\",\n"
                + "      \"Resource\": \"" + escape(resourceArn) + "\",\n"
                + "      \"Retry\": [{\n"
                + "        \"ErrorEquals\": [\"States.ALL\"],\n"
                + "        \"IntervalSeconds\": " + Math.max(1, step.backoff().toSeconds()) + ",\n"
                + "        \"MaxAttempts\": " + step.maxAttempts() + ",\n"
                + "        \"BackoffRate\": 2.0,\n"
                + "        \"MaxDelaySeconds\": 30,\n"
                + "        \"JitterStrategy\": \"FULL\"\n"
                + "      }],\n"
                + "      \"Catch\": [{\n"
                + "        \"ErrorEquals\": [\"States.ALL\"],\n"
                + "        \"ResultPath\": \"$.sagaError\",\n"
                + "        \"Next\": \"" + catchNext + "\"\n"
                + "      }],\n"
                + "      \"Next\": \"" + next + "\"\n"
                + "    }";
    }

    private static String cancelStart(List<SagaStep> steps, int failedTryIndex) {
        for (int i = failedTryIndex - 1; i >= 0; i--) {
            if (steps.get(i).hasCompensation()) {
                return "Cancel" + stateName(steps.get(i).name());
            }
        }
        return "SagaCancelled";
    }

    private static String previousCancel(List<SagaStep> steps, int currentIndex) {
        for (int i = currentIndex - 1; i >= 0; i--) {
            if (steps.get(i).hasCompensation()) {
                return "Cancel" + stateName(steps.get(i).name());
            }
        }
        return "SagaCancelled";
    }

    private static String stateName(String step) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char c : step.toCharArray()) {
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
