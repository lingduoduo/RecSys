package com.recsys.infrastructure.alb;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class PathPatternCondition implements RuleCondition {

    private final List<String> patterns;

    private PathPatternCondition(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    public static PathPatternCondition of(String... patterns) {
        Objects.requireNonNull(patterns, "patterns");
        if (patterns.length == 0) throw new IllegalArgumentException("at least one pattern required");
        return new PathPatternCondition(Arrays.asList(patterns));
    }

    @Override
    public boolean matches(String path, String host, String method) {
        if (path == null) return false;
        return patterns.stream().anyMatch(p -> matchesGlob(p, path));
    }

    // Supports * (any sequence) and ? (exactly one character).
    static boolean matchesGlob(String pattern, String text) {
        int p = 0, t = 0, starP = -1, matchT = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) {
                p++;
                t++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p++;
                matchT = t;
            } else if (starP != -1) {
                p = starP + 1;
                t = ++matchT;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    public List<String> patterns() {
        return patterns;
    }
}
