package com.shoplite.order_service.exception;

public class OutOfStockException extends RuntimeException{

    public OutOfStockException(String skuCode, int quantity) {
        super("Not enough stock for SKU " + skuCode + " requested quantity " + quantity);
    }
}
