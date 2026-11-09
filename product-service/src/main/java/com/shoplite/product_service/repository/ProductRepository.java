package com.shoplite.product_service.repository;

import com.shoplite.product_service.model.Product;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProductRepository extends MongoRepository<Product, String> {

    boolean existsBySkuCode(String sku);

    Optional<Product> findBySkuCode(String sku);

}
