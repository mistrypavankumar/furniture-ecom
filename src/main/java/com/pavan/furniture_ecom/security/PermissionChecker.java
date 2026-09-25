package com.pavan.furniture_ecom.security;

import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.model.User;
import com.pavan.furniture_ecom.model.enums.Operation;
import com.pavan.furniture_ecom.model.enums.PermissionLevel;
import com.pavan.furniture_ecom.model.enums.PermissionScope;
import com.pavan.furniture_ecom.repository.PermissionRepository;
import com.pavan.furniture_ecom.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PermissionChecker {

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;

    public boolean hasPermission(Class<?> entity, Operation operation, PermissionScope requiredScope) {
        return resolveScope(entity, operation)
                .map(scope -> requiredScope == PermissionScope.OWN || scope == PermissionScope.ALL)
                .orElse(false);
    }

    public Optional<PermissionScope> resolveScope(Class<?> entity, Operation operation){
        User user = currentUser();

        // 1. Admin role → everything allowed
        if(user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getIsAdmin()))){
            return Optional.of(PermissionScope.ALL);
        }

        if(user.getRoles().isEmpty()){
            return Optional.empty();
        }

        List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
        String entityName = entity.getSimpleName();

        // 2. Best scope wins: check ALL first, then OWN
        if(permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation, entityName, Set.of(PermissionScope.ALL))){
            return Optional.of(PermissionScope.ALL);
        }

        if(permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation, entityName, Set.of(PermissionScope.OWN))){
            return Optional.of(PermissionScope.OWN);
        }

        return Optional.empty();
    }

    public String currentUserEmail(){
        Authentication authentication =SecurityContextHolder.getContext().getAuthentication();

        if(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken){
            return jwtAuthenticationToken.getToken().getClaimAsString("email");
        }

        throw new AccessDeniedException("Not authenticated");
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if(authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return userRepository.findByKeycloakId(jwtAuthenticationToken.getToken().getSubject())
                    .orElseThrow(() -> new AccessDeniedException("User not found"));
        }

        throw new AccessDeniedException("Not authenticated");
    }
}
