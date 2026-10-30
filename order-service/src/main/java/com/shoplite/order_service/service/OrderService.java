package com.shoplite.order_service.service;

import com.shoplite.order_service.dto.OrderResponse;
import com.shoplite.order_service.dto.PlaceOrderRequest;
import com.shoplite.order_service.exception.OrderNotFoundException;
import com.shoplite.order_service.model.Order;
import com.shoplite.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        Order order = new Order(request.skuCode(), request.price(), request.quantity());
        Order saved = orderRepository.save(order);
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse findByOrderNumber(UUID orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));
    }
}