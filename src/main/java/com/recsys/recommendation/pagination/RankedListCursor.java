package com.recsys.recommendation.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record RankedListCursor(int offset) {
    private static final String PREFIX = "v1:";

    public RankedListCursor {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + offset).getBytes(StandardCharsets.UTF_8));
    }

    public static RankedListCursor decode(String token) {
        if (token == null || token.isBlank()) {
            return new RankedListCursor(0);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            if (!decoded.startsWith(PREFIX)) {
                throw new IllegalArgumentException("unsupported cursor version");
            }
            return new RankedListCursor(Integer.parseInt(decoded.substring(PREFIX.length())));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid ranked-list cursor", e);
        }
    }
}
