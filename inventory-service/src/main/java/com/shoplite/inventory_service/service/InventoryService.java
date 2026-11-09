package com.shoplite.inventory_service.service;

import com.shoplite.inventory_service.exception.InsufficientStockException;
import com.shoplite.inventory_service.model.Inventory;
import com.shoplite.inventory_service.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public boolean isInStock(String skuCode, Integer quantity) {
        Optional<Inventory> optionalInventory = inventoryRepository.findBySkuCode(skuCode);

        return optionalInventory.filter(inventory -> inventory.getQuantity() >= quantity).isPresent();
    }

    @Transactional
    public void reserve(String skuCode, Integer quantity) {
        int result = inventoryRepository.reserve(skuCode, quantity);
        if (result == 0) {
            throw new InsufficientStockException(skuCode, quantity);
        }
    }
}
