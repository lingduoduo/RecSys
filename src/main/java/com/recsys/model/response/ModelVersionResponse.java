package com.recsys.model.response;

import java.util.List;

public record ModelVersionResponse(
        String activeVariant,
        String previousActiveVariant,
        List<VariantVersion> variants
) {
    public record VariantVersion(
            String variant,
            String modelVersion,
            boolean ready,
            boolean active
    ) {
    }
}
