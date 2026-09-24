package com.pavan.furniture_ecom.service.impl;

import com.pavan.furniture_ecom.dto.role.RoleCreateInput;
import com.pavan.furniture_ecom.dto.role.RoleResponse;
import com.pavan.furniture_ecom.dto.role.RoleUpdateInput;
import com.pavan.furniture_ecom.dto.user.UserResponse;
import com.pavan.furniture_ecom.exception.AppException;
import com.pavan.furniture_ecom.model.Role;
import com.pavan.furniture_ecom.model.User;
import com.pavan.furniture_ecom.repository.RoleRepository;
import com.pavan.furniture_ecom.repository.UserRepository;
import com.pavan.furniture_ecom.service.RoleService;
import com.pavan.furniture_ecom.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final UserService userService;

    @Override
    public RoleResponse createRole(RoleCreateInput input) {

        if(roleRepository.existsByName(input.getName())){
            throw new AppException("Role already exists with name: " + input.getName(),
                    HttpStatus.CONFLICT,
                    "ROLE_ALREADY_EXISTS");
        }

        Role role = Role.builder()
                .name(input.getName())
                .description(input.getDescription())
                .build();
        
        role = roleRepository.save(role);
        return mapToRoleResponse(role);
    }

    @Override
    public List<RoleResponse> getAllRoles() {
        return roleRepository.findAll()
                .stream()
                .map(this::mapToRoleResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RoleResponse updateRole(Long id, RoleUpdateInput input) {
        Role role = roleRepository.findById(id).orElseThrow(() -> new AppException("Role not found with id: " + id,
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND"));

        role.setName(Objects.requireNonNullElse(input.getName(), role.getName()));
        role.setDescription(Objects.requireNonNullElse(input.getDescription(), role.getDescription()));

        role = roleRepository.save(role);
        return mapToRoleResponse(role);
    }

    @Override
    @Transactional
    public boolean deleteRoleById(Long id) {

        if(!roleRepository.existsById(id)){
            throw new AppException("Role not found with id: " + id,
                    HttpStatus.NOT_FOUND,
                    "ROLE_NOT_FOUND");
        }

        roleRepository.deleteById(id);
        return true;
    }

    @Override
    @Transactional
    public RoleResponse assignUserToRole(Long id, Long userId) {
        Role role =  roleRepository.findById(id).orElseThrow(() -> new AppException("Role not found with id: " + id,
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND"));

        User user = userService.findUserById(userId);
        role.setUser(user);
        role = roleRepository.save(role);
        return mapToRoleResponse(role);
    }

    private RoleResponse mapToRoleResponse(Role role) {
        return RoleResponse.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .isAdmin(role.getIsAdmin())
                .createdBy(role.getCreatedBy())
                .createdDate(role.getCreatedDate())
                .lastModifiedBy(role.getLastModifiedBy())
                .lastModifiedDate(role.getLastModifiedDate())
                .build();
    }
}
