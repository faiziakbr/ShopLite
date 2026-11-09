package com.shoplite.order_service.exception;

public class UnknownProductException extends RuntimeException {

    public UnknownProductException(String skuCode) {
        super("Product with SKU " + skuCode + " cannot be ordered");
    }
}
