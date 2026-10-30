package com.shoplite.order_service.dto;

import com.shoplite.order_service.enums.OrderStatus;
import com.shoplite.order_service.model.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID orderNumber,
        String skuCode,
        BigDecimal price,
        Integer quantity,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderNumber(),
                order.getSkuCode(),
                order.getPrice(),
                order.getQuantity(),
                order.getStatus(),
                order.getCreatedAt());
    }
}