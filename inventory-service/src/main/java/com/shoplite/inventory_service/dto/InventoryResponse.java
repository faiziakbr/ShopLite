package com.shoplite.inventory_service.dto;

public record InventoryResponse(
        String skuCode,
        boolean inStock
) {
}
