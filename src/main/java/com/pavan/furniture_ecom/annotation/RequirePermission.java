package com.pavan.furniture_ecom.annotation;

import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    Class<?> entity();

    Operation operation();

    // Own = any grant (ALL OR OWN) is enough; ALL = must be allowed on every row
    PermissionScope scope() default PermissionScope.OWN;
}
