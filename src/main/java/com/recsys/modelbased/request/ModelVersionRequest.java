package com.recsys.modelbased.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelVersionRequest(
        @NotBlank(message = "variant is required")
        @Size(max = 80, message = "variant must not exceed 80 characters")
        String variant
) {
}
