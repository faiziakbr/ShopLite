package com.shoplite.order_service.service;

import com.shoplite.order_service.client.InventoryClient;
import com.shoplite.order_service.client.ProductClient;
import com.shoplite.order_service.client.ReservationRequest;
import com.shoplite.order_service.dto.OrderResponse;
import com.shoplite.order_service.dto.PlaceOrderRequest;
import com.shoplite.order_service.exception.OrderNotFoundException;
import com.shoplite.order_service.exception.OutOfStockException;
import com.shoplite.order_service.exception.ServiceUnavailableException;
import com.shoplite.order_service.exception.UnknownProductException;
import com.shoplite.order_service.model.Order;
import com.shoplite.order_service.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderService {

    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final OrderRepository orderRepository;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public OrderService(ProductClient productClient, InventoryClient inventoryClient, OrderRepository orderRepository) {
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.orderRepository = orderRepository;
    }

    private BigDecimal fetchPrice(String skuCode) {
        try {
            BigDecimal price = productClient.getBySkuCode(skuCode).price();
            if (price != null) {
                return price;
            }
            throw new UnknownProductException(skuCode);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UnknownProductException(skuCode);
        } catch (RestClientException e) {
            log.warn("Could not reach product-service for SKU {}", skuCode, e);
            throw new ServiceUnavailableException(e);
        }
    }

    private void reserveStock(String skuCode, int quantity) {
        try {
            inventoryClient.reserve(new ReservationRequest(skuCode, quantity));
        } catch (HttpClientErrorException.Conflict e) {
            throw new OutOfStockException(skuCode, quantity);
        } catch (RestClientException e) {
            log.warn("Could not reach inventory-service for SKU {}", skuCode, e);
            throw new ServiceUnavailableException(e);
        }
    }

    public OrderResponse placeOrder(PlaceOrderRequest request) {
        BigDecimal price = fetchPrice(request.skuCode());
        reserveStock(request.skuCode(), request.quantity());
        Order order = new Order(request.skuCode(), price, request.quantity());
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