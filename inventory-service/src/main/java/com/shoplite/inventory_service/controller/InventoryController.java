package com.shoplite.inventory_service.controller;

import com.shoplite.inventory_service.dto.InventoryResponse;
import com.shoplite.inventory_service.service.InventoryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/inventory")
@RestController
public class InventoryController {
    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public ResponseEntity<InventoryResponse> isInStock(@RequestParam @NotBlank String skuCode,
                                                       @RequestParam @Positive Integer quantity) {
        boolean inStock = inventoryService.isInStock(skuCode, quantity);
        return ResponseEntity.ok(new InventoryResponse(skuCode, inStock));
    }


}
