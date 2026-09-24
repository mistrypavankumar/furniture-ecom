package com.pavan.furniture_ecom.initializer;

import com.pavan.furniture_ecom.model.Permission;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionLevel;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import com.pavan.furniture_ecom.repository.PermissionRepository;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@Slf4j
@Order(1)
@RequiredArgsConstructor
public class PermissionsInitializer implements CommandLineRunner {

    private static final List<Operation> FIELD_OPERATIONS = List.of(Operation.READ, Operation.UPDATE);

    private final EntityManagerFactory entityManagerFactory;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(String... args){
        Set<Permission> desired = buildDesiredPermission();
        Set<Permission> current = new HashSet<>(permissionRepository.findAll());

        Set<Permission> toCreate = new HashSet<>(desired);
        toCreate.removeAll(current);

        Set<Permission> orphans = new HashSet<>(current);
        orphans.removeAll(desired);

        if(!toCreate.isEmpty()){
            permissionRepository.saveAll(toCreate);
        }

        log.info("Permissions synced: {} created, {} total", toCreate.size(), current.size());

        if(!orphans.isEmpty()){
            // Never auto-delete: a removed field may still be granted to roles
            log.warn("{} orphan permissions found (entity/field no longer exists): {}", orphans.size(), orphans);
        }

    }

    private Set<Permission> buildDesiredPermission() {
        Set<Permission> desired = new HashSet<>();

        for(EntityType<?> entity: entityManagerFactory.getMetamodel().getEntities()){
            String entityName = entity.getJavaType().getSimpleName();

            // Object: every operation x every scope
            for(Operation operation: Operation.values()){
                for(PermissionScope scope: PermissionScope.values()){
                    desired.add(permission(operation, PermissionLevel.OBJECT, scope, entityName, null));
                }
            }

            // TOTAL: all fields, read and update (CREATE uses UPDATE)
            for(Operation operation: FIELD_OPERATIONS){
                desired.add(permission(operation, PermissionLevel.TOTAL, null, entityName, null));
            }

            // FIELD: one row per field, READ and UPDATE, Include Auditable fields
            for(Attribute<?,?> attribute: entity.getAttributes()){
                for(Operation operation: FIELD_OPERATIONS){
                    desired.add(permission(operation, PermissionLevel.FIELD, null, entityName, attribute.getName()));
                }
            }
        }

        return desired;
    }

    private Permission permission(Operation operation, PermissionLevel permissionLevel, PermissionScope scope, String entityName,
                                  String fieldName) {
        return Permission.builder()
                .operation(operation)
                .level(permissionLevel)
                .scope(scope)
                .entityName(entityName)
                .fieldName(fieldName)
                .build();
    }

}
