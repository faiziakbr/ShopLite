package com.shoplite.product_service.dto;

import com.shoplite.product_service.model.Product;

import java.math.BigDecimal;

public record ProductResponse(
        String id,
        String name,
        String description,
        String skuCode,
        BigDecimal price
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.id(),
                product.name(),
                product.description(),
                product.skuCode(),
                product.price());
    }
}
