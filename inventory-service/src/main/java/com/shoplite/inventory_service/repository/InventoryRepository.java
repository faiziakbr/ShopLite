package com.shoplite.inventory_service.repository;

import com.shoplite.inventory_service.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findBySkuCode(String skuCode);

    @Modifying
    @Query("UPDATE Inventory i SET i.quantity = i.quantity - :quantity " +
            "WHERE i.skuCode = :skuCode AND i.quantity >= :quantity")
    int reserve(@Param("skuCode") String skuCode, @Param("quantity") int quantity);
}
