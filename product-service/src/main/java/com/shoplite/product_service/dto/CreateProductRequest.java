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
        @Size(max = 100)
        String skuCode,

        @NotNull
        @Positive
        @Digits(integer = 10, fraction = 2)
        BigDecimal price
) {
}
