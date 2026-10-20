package com.shoplite.product_service.exception;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String skuCode) {
        super("A product with SKU '" + skuCode + "' already exists");
    }
}
