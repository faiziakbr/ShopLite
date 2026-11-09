package com.shoplite.order_service.client;

public record ReservationRequest(
    String skuCode,
    Integer quantity
) {
}
