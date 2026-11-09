package com.shoplite.order_service;

import com.shoplite.order_service.client.InventoryClient;
import com.shoplite.order_service.client.ProductClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.service.registry.ImportHttpServices;

@ImportHttpServices(group = "product", types = ProductClient.class)
@ImportHttpServices(group = "inventory", types = InventoryClient.class)
@SpringBootApplication
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}
