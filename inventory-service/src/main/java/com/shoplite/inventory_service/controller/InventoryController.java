package com.shoplite.inventory_service.controller;

import com.shoplite.inventory_service.dto.InventoryResponse;
import com.shoplite.inventory_service.dto.ReservationRequest;
import com.shoplite.inventory_service.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/reservations")
    public ResponseEntity<Void> addReservation(@Valid @RequestBody ReservationRequest reservationRequest) {
        inventoryService.reserve(reservationRequest.skuCode(), reservationRequest.quantity());
        return ResponseEntity.noContent().build();
    }

}
