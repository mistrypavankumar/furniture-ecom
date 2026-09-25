package com.pavan.furniture_ecom.dto.product;

import com.pavan.furniture_ecom.model.Product;
import com.pavan.furniture_ecom.model.enums.FurnitureType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal rating;
    private String color;
    private FurnitureType type;
    private LocalDateTime createdDate;
    private String createdBy;
    private LocalDateTime lastModifiedDate;
    private String lastModifiedBy;

    public static ProductResponse from(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .color(product.getColor())
                .rating(product.getRating())
                .quantity(product.getQuantity())
                .type(product.getType())
                .price(product.getPrice())
                .createdDate(product.getCreatedDate())
                .createdBy(product.getCreatedBy())
                .lastModifiedBy(product.getLastModifiedBy())
                .lastModifiedDate(product.getLastModifiedDate())
                .build();
    }

}
