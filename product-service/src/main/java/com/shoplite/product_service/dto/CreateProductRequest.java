package com.shoplite.product_service.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateProductRequest(
        @Size(max = 100)
        @NotBlank
        String name,

        @Size(max = 1000)
        @NotBlank
        String description,

        @NotBlank
        String skuCode,

        @NotNull
        @Positive
        BigDecimal price
) {
}
