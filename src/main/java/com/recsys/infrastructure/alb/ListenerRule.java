package com.recsys.infrastructure.alb;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ListenerRule {

    static final int DEFAULT_PRIORITY = Integer.MAX_VALUE;

    private final int priority;
    private final List<RuleCondition> conditions;
    private final String targetGroupName;

    private ListenerRule(int priority, List<RuleCondition> conditions, String targetGroupName) {
        this.priority = priority;
        this.conditions = List.copyOf(conditions);
        this.targetGroupName = targetGroupName;
    }

    public static ListenerRule defaultRule(String targetGroupName) {
        Objects.requireNonNull(targetGroupName, "targetGroupName");
        return new ListenerRule(DEFAULT_PRIORITY, List.of(), targetGroupName);
    }

    public int priority() { return priority; }
    public List<RuleCondition> conditions() { return conditions; }
    public String targetGroupName() { return targetGroupName; }

    boolean isDefault() {
        return priority == DEFAULT_PRIORITY;
    }

    boolean evaluate(String path, String host, String method) {
        if (isDefault()) return true;
        return conditions.stream().allMatch(c -> c.matches(path, host, method));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int priority = -1;
        private final List<RuleCondition> conditions = new ArrayList<>();
        private String targetGroupName;

        public Builder priority(int priority) {
            if (priority < 1 || priority > 50000) {
                throw new IllegalArgumentException("priority must be 1–50000");
            }
            this.priority = priority;
            return this;
        }

        public Builder condition(RuleCondition condition) {
            conditions.add(Objects.requireNonNull(condition, "condition"));
            return this;
        }

        public Builder forwardTo(String targetGroupName) {
            this.targetGroupName = Objects.requireNonNull(targetGroupName, "targetGroupName");
            return this;
        }

        public ListenerRule build() {
            if (priority == -1) throw new IllegalStateException("priority is required");
            if (targetGroupName == null) throw new IllegalStateException("forwardTo target group is required");
            if (conditions.isEmpty()) throw new IllegalStateException("at least one condition is required");
            return new ListenerRule(priority, conditions, targetGroupName);
        }
    }
}
