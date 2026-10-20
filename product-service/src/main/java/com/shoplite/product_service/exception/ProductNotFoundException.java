package com.shoplite.product_service.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String id) {
        super("Product with id '" + id + "' was not found");
    }
}
