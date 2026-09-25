package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.product.ProductResponse;

import java.util.List;

public interface ProductService {
    List<ProductResponse> getAllProducts();
}
