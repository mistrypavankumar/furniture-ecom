package com.pavan.furniture_ecom.aop;

import com.pavan.furniture_ecom.annotation.RequirePermission;
import com.pavan.furniture_ecom.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import java.nio.file.AccessDeniedException;

@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {
    private final PermissionChecker permissionChecker;

    @Before("@annotation(requirePermission")
    public void checkPermission(RequirePermission requirePermission) throws AccessDeniedException {
        boolean allowed = permissionChecker.hasPermission(
                requirePermission.entity(),
                requirePermission.operation(),
                requirePermission.scope()
        );

        if(!allowed) {
            throw new AccessDeniedException("Missing permission: " + requirePermission.operation()
                    + " on " + requirePermission.entity().getSimpleName());
        }
    }
}
