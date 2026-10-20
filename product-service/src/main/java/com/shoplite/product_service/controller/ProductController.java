package com.shoplite.product_service.controller;

import com.shoplite.product_service.dto.CreateProductRequest;
import com.shoplite.product_service.dto.ProductResponse;
import com.shoplite.product_service.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;


@RequestMapping("/api/products")
@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> addProduct(@Valid @RequestBody CreateProductRequest product) {
        ProductResponse response = productService.create(product);
        URI uri = ServletUriComponentsBuilder.fromCurrentRequest().pathSegment("{id}").build(response.id());
        return ResponseEntity.created(uri).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        List<ProductResponse> productList = productService.findAll();
        return ResponseEntity.ok(productList);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable("id") String id) {
        ProductResponse product = productService.findById(id);
        return ResponseEntity.ok(product);
    }
}
