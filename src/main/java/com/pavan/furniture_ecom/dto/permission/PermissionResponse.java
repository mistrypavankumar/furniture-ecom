package com.pavan.furniture_ecom.dto.permission;

import com.pavan.furniture_ecom.model.Permission;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionLevel;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PermissionResponse {
    private Long id;
    private Operation operation;
    private PermissionLevel level;
    private PermissionScope scope;
    private String entityName;
    private String fieldName;

    public static PermissionResponse from(Permission permission){
        return PermissionResponse.builder()
                .id(permission.getId())
                .operation(permission.getOperation())
                .level(permission.getLevel())
                .scope(permission.getScope())
                .entityName(permission.getEntityName())
                .fieldName(permission.getFieldName())
                .build();
    }
}
