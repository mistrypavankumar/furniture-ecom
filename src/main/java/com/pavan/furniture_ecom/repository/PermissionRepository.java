package com.pavan.furniture_ecom.repository;

import com.pavan.furniture_ecom.model.Permission;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionLevel;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    List<Permission> findByEntityName(String entityName);

    @Query("""
        select count(p) > 0
            from Role r join r.permissions p
                where r.id in :roleIds
                    and p.level = :level
                    and p.operation = :operation
                    and p.entityName = :entityName
                    and p.scope in :scopes
    """)
    boolean existsGrant(@Param("roleIds") Collection<Long> roleIds,
                        @Param("level")PermissionLevel level,
                        @Param("operation")Operation operation,
                        @Param("entityName") String entityName,
                        @Param("scopes")Collection<PermissionScope> scopes);
}
