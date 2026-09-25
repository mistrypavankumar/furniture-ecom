package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.dto.product.ProductResponse;
import com.pavan.furniture_ecom.model.Product;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import com.pavan.furniture_ecom.repository.ProductRepository;
import com.pavan.furniture_ecom.security.PermissionChecker;
import com.pavan.furniture_ecom.security.RowAccessGuard;
import com.pavan.furniture_ecom.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final RowAccessGuard rowAccessGuard;
    private final PermissionChecker permissionChecker;

    @Override
    public List<ProductResponse> getAllProducts() {

        PermissionScope permissionScope = rowAccessGuard.requireScope(Product.class, Operation.READ);
        List<Product> products = (permissionScope == PermissionScope.ALL)
                ? productRepository.findAll()
                : productRepository.findByCreatedBy(permissionChecker.currentUserEmail());

        return products
                .stream()
                .map(ProductResponse::from)
                .collect(Collectors.toList());
    }
}
