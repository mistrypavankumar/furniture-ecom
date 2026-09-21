package com.pavan.furniture_ecom.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;

@Entity
@Table(name = "tbl_user")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String firstName;
    private String lastName;

    @Column(unique = true, nullable = false)
    private String keycloakId;

    @Column(unique = true)
    private String email;

    private String password;
    private String contactNumber;

    @Builder.Default
    private Boolean isAdmin = false;

    @Builder.Default
    private Boolean active = false;

//    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
//    private HashSet<Role> roles = new HashSet<>();

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdDate;
    private String createdBy;

    @UpdateTimestamp
    private LocalDateTime lastModifiedDate;
    private String lastModifiedBy;
}
