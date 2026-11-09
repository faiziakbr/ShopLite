package com.shoplite.inventory_service.exception;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String skuCode, int requested) {
        super("Not enough stock for SKU '" + skuCode + "' (requested " + requested + ")");
    }
}
