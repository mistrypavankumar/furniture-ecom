package com.pavan.furniture_ecom.security;


import com.pavan.furniture_ecom.exception.AppException;
import com.pavan.furniture_ecom.model.Ownable;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RowAccessGuard {

    private final PermissionChecker permissionChecker;

    /** Returns ALL or OWN, or throws 403. */
    public PermissionScope requireScope(Class<?> entity, Operation operation) {
        return permissionChecker.resolveScope(entity, operation)
                .orElseThrow(() -> new AccessDeniedException(
                        "Missing permission: " + operation + " on " + entity.getSimpleName()
                ));
    }

    /** True if the current user owns the row. */
    public boolean isOwner(Ownable row){
        return Objects.equals(row.getOwnerEmail(), permissionChecker.currentUserEmail());
    }

    /**
     * Throws 404 unless the user has ALL scope or owns the row.
     * Call it right after loading the row, before reading or changing it.
     */
    public <T extends Ownable> T checkRow(T row, Class<T> entity, Operation operation) {
        PermissionScope scope = requireScope(entity, operation);

        if(scope == PermissionScope.OWN && !isOwner(row)){
            String name = entity.getSimpleName();
            throw new AppException(name + " not found",
                    HttpStatus.NOT_FOUND,
                    name.toUpperCase() + "_NOT_FOUND");
        }

        return row;
    }
}
