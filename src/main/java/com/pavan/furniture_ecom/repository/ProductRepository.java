package com.pavan.furniture_ecom.repository;

import com.pavan.furniture_ecom.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCreatedBy(String email);
}
