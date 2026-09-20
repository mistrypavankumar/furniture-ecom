package com.pavan.furniture_ecom.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserCreateRequest {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private String contactNumber;
}
