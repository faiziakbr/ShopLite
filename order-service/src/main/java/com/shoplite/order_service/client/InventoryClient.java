package com.shoplite.order_service.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

public interface InventoryClient {

    @PostExchange("/api/inventory/reservations")
    void reserve(@RequestBody ReservationRequest request);
}
