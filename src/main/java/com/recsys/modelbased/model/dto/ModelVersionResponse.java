package com.recsys.modelbased.model.dto;

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
