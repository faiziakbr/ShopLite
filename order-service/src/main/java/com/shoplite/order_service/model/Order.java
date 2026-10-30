package com.shoplite.order_service.model;

import com.shoplite.order_service.enums.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID orderNumber;

    private String skuCode;

    private BigDecimal price;

    private Integer quantity;

    @Enumerated(EnumType.STRING)   // store "PLACED", not 0
    private OrderStatus status;

    private Instant createdAt;

    protected Order() {
        // for Hibernate only: it fills the fields from the database after calling this
    }

    public Order(String skuCode, BigDecimal price, Integer quantity) {
        this.orderNumber = UUID.randomUUID();
        this.skuCode = skuCode;
        this.price = price;
        this.quantity = quantity;
        this.status = OrderStatus.PLACED;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getOrderNumber() {
        return orderNumber;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}