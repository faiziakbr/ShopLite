package com.shoplite.product_service.repository;

import com.shoplite.product_service.model.Product;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRepository extends MongoRepository<Product, String> {

    boolean existsBySkuCode(String sku);

}
