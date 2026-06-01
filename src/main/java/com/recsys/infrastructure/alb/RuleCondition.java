package com.recsys.infrastructure.alb;

@FunctionalInterface
public interface RuleCondition {
    boolean matches(String path, String host, String method);
}
