package com.shoplite.product_service.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String id) {
        super("Product with id '" + id + "' was not found");
    }

    private ProductNotFoundException(String value, IdentifierType type) {
        super("Product with " + type.name().toLowerCase() + " '" + value + "' was not found");
    }

    private enum IdentifierType {
        SKU
    }

    public static ProductNotFoundException forSku(String sku) {
        return new ProductNotFoundException(sku, IdentifierType.SKU);
    }
}
