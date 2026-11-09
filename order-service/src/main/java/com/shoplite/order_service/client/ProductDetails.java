package com.shoplite.order_service.client;

import java.math.BigDecimal;

public record ProductDetails (
    String skuCode,
    BigDecimal price
) {
}
