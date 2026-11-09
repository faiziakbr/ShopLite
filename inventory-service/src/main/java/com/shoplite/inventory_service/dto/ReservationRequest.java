package com.shoplite.inventory_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ReservationRequest(
        @NotBlank
        @Size(max = 100)
        String skuCode,

        @NotNull
        @Positive
        Integer quantity
) {
}
