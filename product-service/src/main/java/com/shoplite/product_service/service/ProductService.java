package com.shoplite.product_service.service;

import com.shoplite.product_service.dto.CreateProductRequest;
import com.shoplite.product_service.dto.ProductResponse;
import com.shoplite.product_service.exception.DuplicateSkuException;
import com.shoplite.product_service.exception.ProductNotFoundException;
import com.shoplite.product_service.model.Product;
import com.shoplite.product_service.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public ProductResponse create(CreateProductRequest request) {
        if (productRepository.existsBySkuCode(request.skuCode())) {
            throw new DuplicateSkuException(request.skuCode());
        } else {
            Product product = new Product(
                    null,                   // null id → MongoDB generates one
                    request.name(),
                    request.description(),
                    request.skuCode(),
                    request.price());
            Product saved = productRepository.save(product);
            return ProductResponse.from(saved);
        }
    }

    public List<ProductResponse> findAll() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse findById(String id) {
        Optional<Product> productOptional = productRepository.findById(id);
        if (productOptional.isPresent()) {
            return ProductResponse.from(productOptional.get());
        } else {
            throw new ProductNotFoundException(id);
        }
    }

}
