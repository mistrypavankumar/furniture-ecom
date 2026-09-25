package com.pavan.furniture_ecom.model;

import com.pavan.furniture_ecom.model.enums.FurnitureType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name="tbl_product")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String description;
    private BigDecimal price;
    private Integer quantity;

    private BigDecimal rating;
    private String color;

    @Enumerated(EnumType.STRING)
    private FurnitureType type;
}
