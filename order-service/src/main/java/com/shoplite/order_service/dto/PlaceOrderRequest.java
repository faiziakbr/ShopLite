package com.shoplite.order_service.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotBlank
        @Size(max = 100)
        String skuCode,

        @NotNull
        @Positive
        Integer quantity
) {
}