# RBAC Permissions

**Model:** permissions are structured tuples stored in Postgres, seeded automatically for every
JPA entity and field, granted to roles, and enforced on endpoints with a custom
`@RequirePermission` annotation. The design is a scaled-down version of the "5D RBAC" model used
in `daxwell-scm-server` (without Redis caching, distributed locks, S3 `FILE` permissions or
metamodel snapshots).

See `authentication-setup.md` for how the Keycloak JWT is turned into a local `User`. This
document starts from "we know who the user is" and covers "what may they do".

---

## 1. Implementation status

| Piece | Status | Location |
|---|---|---|
| Enums `Operation`, `PermissionLevel`, `PermissionScope` | Done | `model/enums/` |
| `Permission` entity (5-column tuple) | Done | `model/Permission.java` |
| `Role.permissions` many-to-many | Done | `model/Role.java` |
| Startup seeder | Done | `initializer/PermissionsInitializer.java` |
| Assign permissions to a role | In progress | `service/PermissionService.java`, `controller/PermissionController.java` |
| `@RequirePermission` annotation + aspect | To do | Section 7 |
| Row-level `OWN` filtering in services (`Ownable`, `RowAccessGuard`) | To do | Section 9 |
| Field-level (`FIELD` / `TOTAL`) enforcement | To do | Section 10 |

---

## 2. Concepts

A permission is **not** a name string like `USER:EMAIL:READ`. It is a tuple of five columns:

| Column | Meaning | Example |
|---|---|---|
| `operation` | What action | `READ`, `CREATE`, `UPDATE`, `DELETE` |
| `level` | What granularity | `OBJECT`, `FIELD`, `TOTAL` |
| `scope` | Which rows (OBJECT only) | `ALL`, `OWN`, or `NULL` |
| `entity_name` | Which entity (Java simple class name) | `User`, `Role` |
| `field_name` | Which field (FIELD only) | `email`, or `NULL` |

### Levels

| Level | Grants | `scope` | `field_name` |
|---|---|---|---|
| `OBJECT` | Access to the entity at all (can call the endpoint) | `ALL` or `OWN` | `NULL` |
| `TOTAL` | Access to **every** field of the entity | `NULL` | `NULL` |
| `FIELD` | Access to **one** field | `NULL` | field name |

`TOTAL` is the "wildcard". There is no `"*"` field name.

### Scopes

| Scope | Meaning |
|---|---|
| `ALL` | Every row of the entity |
| `OWN` | Only rows the user owns (`Ownable.getOwnerId()` = current user's Keycloak `sub`; see Section 9) |

### Operations on field levels

`FIELD` and `TOTAL` rows exist only for `READ` and `UPDATE`. `CREATE` reuses the `UPDATE` field
grants (a user who may set a field on update may also set it on create). `DELETE` is
object-level only (you delete a row, not a field).

### Admin bypass

A role with `isAdmin = true` passes every permission check. No permission rows are needed for
admins.

---

## 3. Data model

```
tbl_user ──< tbl_user_role >── tbl_role ──< tbl_role_permission >── tbl_permission
```

| Table | Columns | Owner |
|---|---|---|
| `tbl_permission` | `id`, `operation`, `level`, `scope`, `entity_name`, `field_name` | `Permission` |
| `tbl_role_permission` | `role_id`, `permission_id` | `Role.permissions` (`@JoinTable`) |
| `tbl_user_role` | `user_id`, `role_id` | `User.roles` (`@JoinTable`) |

Both links are **unidirectional** (User → Role → Permission). There is no `Role.users` and no
`Permission.roles`. This avoids the `toString()` / `hashCode()` recursion that bidirectional
`@Data` entities cause.

### 3.1 Enums (`model/enums/`)

```java
public enum Operation {
    READ,
    CREATE,
    UPDATE,
    DELETE
}
```

```java
public enum PermissionLevel {
    OBJECT,   // access to the entity itself (with scope)
    FIELD,    // access to one field
    TOTAL     // access to all fields of the entity
}
```

```java
public enum PermissionScope {
    ALL,      // every row
    OWN       // only rows the user created
}
```

### 3.2 `model/Permission.java`

```java
@Entity
@Table(name = "tbl_permission",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"operation", "level", "scope", "entity_name", "field_name"}))
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(of = {"operation", "level", "scope", "entityName", "fieldName"})
@ToString
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PermissionLevel level;

    @Enumerated(EnumType.STRING)
    private PermissionScope scope;          // only for OBJECT, null otherwise

    @Column(name = "entity_name", nullable = false)
    private String entityName;              // e.g. "User"

    @Column(name = "field_name")
    private String fieldName;               // only for FIELD, null otherwise
}
```

Rules:

- **`equals` / `hashCode` cover only the 5 tuple columns, never `id`.** The seeder compares
  freshly built (unsaved, `id = null`) permissions against loaded ones with a `Set` difference.
  If `id` were included, nothing would ever match and every restart would insert duplicates.
- **Do not use `@Data`** on this class, for the same reason.
- The `@UniqueConstraint` is documentation more than protection; see Section 11.1.

### 3.3 `model/Role.java` (permissions link)

```java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
        name = "tbl_role_permission",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id"))
@Builder.Default
@ToString.Exclude
@EqualsAndHashCode.Exclude
private Set<Permission> permissions = new HashSet<>();
```

- **`LAZY` on purpose.** `User.roles` is `EAGER` (the JWT converter reads it on every request).
  If `Role.permissions` were also `EAGER`, every authenticated request would load hundreds of
  permission rows. Permission checks use a dedicated `EXISTS` query instead (Section 6).
- **`@Builder.Default`** — without it, `Role.builder().build()` leaves `permissions = null`.
- **`@ToString.Exclude` / `@EqualsAndHashCode.Exclude`** — `Role` uses `@Data`; without the
  excludes, hashing a role would hash its whole permission set.
- Accessing `role.getPermissions()` requires an open transaction (`@Transactional`), otherwise
  `LazyInitializationException`.

---

## 4. Seeding: `initializer/PermissionsInitializer.java`

A `CommandLineRunner` that runs on every startup, builds the full desired set of permissions from
Hibernate's metamodel, and inserts only what is missing.

### 4.1 What gets seeded per entity

| Level | Rows | Count |
|---|---|---|
| `OBJECT` | 4 operations × 2 scopes | 8 |
| `TOTAL` | `READ`, `UPDATE` | 2 |
| `FIELD` | `READ`, `UPDATE` × each field | 2 × fields |

Example: `User` with 15 attributes → 8 + 2 + 30 = **40 rows**.

Fields come from `EntityType.getAttributes()`, which **includes inherited `@MappedSuperclass`
fields** (`createdDate`, `createdBy`, `lastModifiedDate`, `lastModifiedBy` from `Auditable`) and
association fields (`User.roles`, `Role.permissions`).

New entities (e.g. `Product`, `Order`) get their permissions automatically on the next restart.
No code change is needed.

### 4.2 Code

```java
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PermissionsInitializer implements CommandLineRunner {

    private static final List<Operation> FIELD_OPERATIONS = List.of(Operation.READ, Operation.UPDATE);

    private final EntityManagerFactory entityManagerFactory;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Set<Permission> desired = buildDesiredPermissions();
        Set<Permission> current = new HashSet<>(permissionRepository.findAll());

        Set<Permission> toCreate = new HashSet<>(desired);
        toCreate.removeAll(current);

        Set<Permission> orphans = new HashSet<>(current);
        orphans.removeAll(desired);

        if (!toCreate.isEmpty()) {
            permissionRepository.saveAll(toCreate);
        }
        log.info("Permissions synced: {} created, {} total", toCreate.size(), desired.size());

        if (!orphans.isEmpty()) {
            // Never auto-delete: a removed field may still be granted to roles
            log.warn("{} orphan permissions found (entity/field no longer exists): {}", orphans.size(), orphans);
        }
    }

    private Set<Permission> buildDesiredPermissions() {
        Set<Permission> desired = new HashSet<>();

        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            String entityName = entity.getJavaType().getSimpleName();

            // OBJECT: every operation x every scope
            for (Operation op : Operation.values()) {
                for (PermissionScope scope : PermissionScope.values()) {
                    desired.add(permission(op, PermissionLevel.OBJECT, scope, entityName, null));
                }
            }

            // TOTAL: all fields, READ and UPDATE (CREATE uses UPDATE)
            for (Operation op : FIELD_OPERATIONS) {
                desired.add(permission(op, PermissionLevel.TOTAL, null, entityName, null));
            }

            // FIELD: one row per field, READ and UPDATE. Includes Auditable fields.
            for (Attribute<?, ?> attribute : entity.getAttributes()) {
                for (Operation op : FIELD_OPERATIONS) {
                    desired.add(permission(op, PermissionLevel.FIELD, null, entityName, attribute.getName()));
                }
            }
        }
        return desired;
    }

    private Permission permission(Operation op, PermissionLevel level, PermissionScope scope,
                                  String entityName, String fieldName) {
        return Permission.builder()
                .operation(op)
                .level(level)
                .scope(scope)
                .entityName(entityName)
                .fieldName(fieldName)
                .build();
    }
}
```

### 4.3 Behaviour

| Situation | Result |
|---|---|
| First start | Inserts every permission. Log: `Permissions synced: N created, N total` |
| Restart, nothing changed | Inserts nothing. Log: `0 created` |
| New entity or field added | Inserts only the new rows |
| Field or entity removed | Logs a warning listing the orphans; **does not delete** |

Orphans are never deleted automatically because they may still be linked to roles in
`tbl_role_permission`. Clean them up manually after checking.

---

## 5. Assigning permissions to a role

### 5.1 Service

`findAllById` is inherited from `JpaRepository`; no custom repository method is needed.

```java
@Override
@Transactional
public boolean assignPermissionToRole(Long roleId, List<Long> permissionIds) {
    Role role = roleService.findByRoleId(roleId);

    List<Permission> permissions = permissionRepository.findAllById(permissionIds);

    if (permissions.size() != new HashSet<>(permissionIds).size()) {
        Set<Long> foundIds = permissions.stream().map(Permission::getId).collect(Collectors.toSet());
        List<Long> missingIds = permissionIds.stream().filter(id -> !foundIds.contains(id)).distinct().toList();

        throw new AppException("Permissions not found with ids: " + missingIds,
                HttpStatus.NOT_FOUND,
                "PERMISSION_NOT_FOUND");
    }

    role.getPermissions().addAll(permissions);
    return true;
}
```

Notes:

- **`findAllById` silently skips unknown IDs** (`WHERE id IN (...)`). The size check turns a
  missing ID into a 404 instead of a partial assignment. The `HashSet` stops duplicate IDs in the
  request from causing false 404s.
- **`@Transactional` is required**: `role.getPermissions()` is lazy, and the transaction also
  flushes the new `tbl_role_permission` rows on commit (dirty checking, no `save()` needed).
- **Idempotent**: `permissions` is a `Set` and `Permission.equals` compares the tuple, so
  re-assigning an existing permission is a no-op.
- Consider returning `RoleResponse` (or `void` + 204) instead of `boolean`, which is always `true`.

### 5.2 Removing permissions

Same shape, with `role.getPermissions().removeAll(permissions)`.

### 5.3 Finding permission IDs

```sql
-- Object-level READ on all users
SELECT id FROM tbl_permission
WHERE entity_name = 'User' AND level = 'OBJECT' AND operation = 'READ' AND scope = 'ALL';

-- All permissions for an entity
SELECT id, operation, level, scope, field_name FROM tbl_permission
WHERE entity_name = 'User' ORDER BY level, operation, scope, field_name;
```

A `GET /api/permissions?entityName=User` endpoint backed by
`PermissionRepository.findByEntityName(String)` makes this usable from a frontend.

---

## 6. Permission check query: `repository/PermissionRepository.java`

```java
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
                        @Param("level") PermissionLevel level,
                        @Param("operation") Operation operation,
                        @Param("entityName") String entityName,
                        @Param("scopes") Collection<PermissionScope> scopes);
}
```

Imports: `org.springframework.data.jpa.repository.Query`,
`org.springframework.data.repository.query.Param`, `java.util.Collection`.

It is a single `EXISTS`-style query; no permission rows are loaded into memory. The enum is passed
as a parameter (not a JPQL literal) so no fully-qualified enum names are needed in the query.

---

## 7. Enforcing on endpoints: `@RequirePermission`

### 7.1 How a request flows

```
Request with Bearer token
  → BearerTokenAuthenticationFilter validates JWT (Keycloak)
  → DbAuthoritiesJwtConverter resolves User from DB (sub → keycloak_id)
  → Controller proxy
      → RequirePermissionAspect (@Before)
          → PermissionChecker.hasPermission(entity, operation, scope)
              - any role isAdmin?            → allow
              - no roles?                    → deny
              - existsGrant(OBJECT, ...)     → allow / deny
          → deny: throw AccessDeniedException
  → GlobalExceptionHandler maps AccessDeniedException → 403 ACCESS_DENIED
```

### 7.2 Scope rules

| Annotation `scope` | Accepted grant scopes | Use for |
|---|---|---|
| `OWN` (default) | `ALL` or `OWN` | "user may use this endpoint at all"; service filters rows |
| `ALL` | `ALL` only | endpoints that expose every row (admin lists, reports) |

### 7.3 Dependency (`pom.xml`)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
```

Spring Boot 4 renamed `spring-boot-starter-aop` to `spring-boot-starter-aspectj`. If it does
not resolve, try the old name.

### 7.4 Annotation: `annotation/RequirePermission.java`

```java
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

    // OWN = any grant (ALL or OWN) is enough; ALL = must be allowed on every row
    PermissionScope scope() default PermissionScope.OWN;
}
```

`entity` is a `Class<?>`, not a `String`: typos fail at compile time and renames are refactor-safe.
The checker uses `getSimpleName()`, which is exactly what the seeder writes to `entity_name`.

### 7.5 Checker: `security/PermissionChecker.java`

```java
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
import java.util.Set;

@Component
@RequiredArgsConstructor
public class PermissionChecker {

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;

    public boolean hasPermission(Class<?> entity, Operation operation, PermissionScope requiredScope) {
        User user = currentUser();

        if (user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getIsAdmin()))) {
            return true;
        }
        if (user.getRoles().isEmpty()) {
            return false;
        }

        Set<PermissionScope> acceptedScopes = requiredScope == PermissionScope.ALL
                ? Set.of(PermissionScope.ALL)
                : Set.of(PermissionScope.ALL, PermissionScope.OWN);

        List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();

        return permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation,
                entity.getSimpleName(), acceptedScopes);
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return userRepository.findByKeycloakId(jwtAuth.getToken().getSubject())
                    .orElseThrow(() -> new AccessDeniedException("User not found"));
        }
        throw new AccessDeniedException("Not authenticated");
    }
}
```

The checker is a separate bean (not inlined into the aspect) so services can reuse it, e.g. to
decide whether to restrict a query to the user's own rows (Section 9).

### 7.6 Aspect: `aop/RequirePermissionAspect.java`

```java
package com.pavan.furniture_ecom.aop;

import com.pavan.furniture_ecom.annotation.RequirePermission;
import com.pavan.furniture_ecom.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {

    private final PermissionChecker permissionChecker;

    @Before("@annotation(requirePermission)")
    public void checkPermission(RequirePermission requirePermission) {
        boolean allowed = permissionChecker.hasPermission(
                requirePermission.entity(),
                requirePermission.operation(),
                requirePermission.scope());

        if (!allowed) {
            throw new AccessDeniedException("Missing permission: " + requirePermission.operation()
                    + " on " + requirePermission.entity().getSimpleName());
        }
    }
}
```

`@Before("@annotation(requirePermission)")` binds the annotation instance directly to the method
parameter, so no reflection is needed. This supports method-level annotations only. For
class-level support, add `@Target({METHOD, TYPE})` and a second pointcut
`@within(requirePermission)`, letting the method-level annotation win.

### 7.7 403 handling: `exception/GlobalExceptionHandler.java`

Must exist **above** the catch-all `Exception.class` handler, and must import
`org.springframework.security.access.AccessDeniedException` (**not** `java.nio.file.AccessDeniedException`).
Otherwise denials surface as 500 `INTERNAL_ERROR`.

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
    log.warn("Access denied at {}", request.getRequestURI());
    return buildResponse(HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "You do not have permission to access this resource.",
            request);
}
```

### 7.8 Usage

```java
@GetMapping
@RequirePermission(entity = User.class, operation = Operation.READ, scope = PermissionScope.ALL)
public ResponseEntity<List<UserResponse>> getAllUsers() {
    return ResponseEntity.ok(userService.getAllUsers());
}

@GetMapping("/{id}")
@RequirePermission(entity = User.class, operation = Operation.READ)
public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
    return ResponseEntity.ok(userService.getUserById(id));
}

@PutMapping("/{id}")
@RequirePermission(entity = User.class, operation = Operation.UPDATE)
public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @RequestBody @Valid UserUpdateInput input) {
    ...
}
```

Once an endpoint uses `@RequirePermission`, remove any `@PreAuthorize("hasRole('ADMIN')")` on it.
Admins still pass via `isAdmin`; other roles pass when granted.

---

## 8. Relationship to `@PreAuthorize` / `hasRole`

Both mechanisms work side by side:

| Mechanism | Checks | Source |
|---|---|---|
| `@PreAuthorize("hasRole('ADMIN')")` | Role **name** | Authorities built by `DbAuthoritiesJwtConverter` |
| `@RequirePermission(...)` | Role **permissions** + `isAdmin` flag | `tbl_role_permission` |

`hasRole('ADMIN')` looks for the authority `ROLE_ADMIN`, so the converter must add the prefix:

```java
.map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName().toUpperCase()))
```

(Alternatively use `hasAuthority('ADMIN')`, which compares the exact string. Pick one convention.)

Prefer `@RequirePermission` for new endpoints. Keep `hasRole` only for things that are truly
role-name based.

Bootstrap note: when role/permission management endpoints are locked down, an admin must already
exist. Seed the first one directly:

```sql
INSERT INTO tbl_user_role (user_id, role_id) VALUES (<user id>, <ADMIN role id>);
UPDATE tbl_role SET is_admin = true WHERE name = 'ADMIN';
```

---

## 9. Row-level permissions (`OWN` scope)

`@RequirePermission` answers only "may this user call this endpoint at all?". It runs before the
controller method and never sees the rows. Row-level checks ("may this user see / change **this**
order?") must happen in the **service**, where the rows are loaded.

### 9.1 The rule

For an entity and an operation, a user's **effective scope** is:

| User has | Effective scope | Service behaviour |
|---|---|---|
| a role with `isAdmin = true` | `ALL` | no row restriction |
| an `OBJECT / ALL` grant | `ALL` | no row restriction |
| only an `OBJECT / OWN` grant | `OWN` | only rows the user owns |
| no `OBJECT` grant | none | 403 (already stopped by `@RequirePermission`) |

`ALL` beats `OWN`: if any of the user's roles grants `ALL`, the user gets `ALL`.

Scope is resolved **per operation**. A role can have `READ / ALL` but `UPDATE / OWN`
("see every order, edit only your own"). Always resolve the scope for the operation the service
method actually performs.

### 9.2 Implementation checklist

1. Store a stable owner id in `createdBy` (9.3).
2. Add the `Ownable` interface; `Auditable` implements it (9.4).
3. Add `resolveScope()` and `currentUserId()` to `PermissionChecker` (9.5).
4. Add the `RowAccessGuard` helper (9.6).
5. Add owner-filtered repository methods (9.7).
6. Apply the service patterns for list / get / create / update / delete (9.8).
7. Keep the controller annotation at the default `scope = OWN` (9.9).

### 9.3 Owner id: use the Keycloak `sub`, not the email

Ownership is matched on `Auditable.createdBy`. Today `JpaAuditingConfig` writes the JWT `email`
claim there. Emails can change: `UserServiceImpl.syncProfile` updates the stored email when the
token's email changes. After that, every row the user created no longer matches, so they
"lose" their own data. The Keycloak `sub` never changes, so use it as the owner id.

Change `config/JpaAuditingConfig.java`:

```java
@Bean
AuditorAware<String> auditorAware() {
    return () -> {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return Optional.of(jwtAuthenticationToken.getToken().getSubject());
        }
        return Optional.of("system");
    };
}
```

Existing rows hold emails in `created_by` / `last_modified_by`. Convert them once, per table
that extends `Auditable`:

```sql
UPDATE tbl_role r SET created_by = u.keycloak_id FROM tbl_user u WHERE r.created_by = u.email;
UPDATE tbl_role r SET last_modified_by = u.keycloak_id FROM tbl_user u WHERE r.last_modified_by = u.email;
-- repeat for tbl_user and every future Auditable table (tbl_order, ...)
```

Responses can still show a readable name: resolve `createdBy` → user email in the DTO mapper if
needed.

If you decide to keep emails instead, everything below still works; just replace
`currentUserId()` with the current email and accept that email changes break ownership.

### 9.4 Who owns a row: `model/Ownable.java`

Most entities are owned by whoever created them. Some are not:

| Entity | Owner |
|---|---|
| `Order`, `Address`, `CartItem`, `Review` … | the creator (`createdBy`) |
| `User` | the user themself (`keycloakId`) |
| `Order` created by staff on behalf of a customer | the customer, not the staff member |

Model this with an interface that returns the owner's Keycloak `sub`:

```java
package com.pavan.furniture_ecom.model;

public interface Ownable {

    /** Keycloak {@code sub} of the user who owns this row. */
    String getOwnerId();
}
```

`Auditable` implements it with the default rule:

```java
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable implements Ownable {

    // ... existing fields ...

    @Override
    @Transient
    public String getOwnerId() {
        return createdBy;
    }
}
```

`@Transient` (`jakarta.persistence.Transient`) stops Hibernate from treating `ownerId` as a column
and stops the permission seeder from creating `FIELD` rows for it. Also add `@JsonIgnore` if an
entity is ever serialized directly (it should not be; see 11.4).

Entities with a different owner override it:

```java
// model/User.java
@Override
@Transient
public String getOwnerId() {
    return keycloakId;
}
```

```java
// model/Order.java, when an order belongs to a customer rather than its creator
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "customer_id")
private User customer;

@Override
@Transient
public String getOwnerId() {
    return customer.getKeycloakId();
}
```

Each repository's owner-filtered query (9.7) must use the **same** field as `getOwnerId()`.

### 9.5 `security/PermissionChecker.java`: resolve the scope

Add two public methods and rewrite `hasPermission` on top of them:

```java
/**
 * Effective scope of the current user for this entity and operation:
 * ALL (admin or ALL grant), OWN (only OWN grants), or empty (no OBJECT grant).
 */
public Optional<PermissionScope> resolveScope(Class<?> entity, Operation operation) {
    User user = currentUser();

    if (user.getRoles().stream().anyMatch(role -> Boolean.TRUE.equals(role.getIsAdmin()))) {
        return Optional.of(PermissionScope.ALL);
    }
    if (user.getRoles().isEmpty()) {
        return Optional.empty();
    }

    List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
    String entityName = entity.getSimpleName();

    if (permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation,
            entityName, Set.of(PermissionScope.ALL))) {
        return Optional.of(PermissionScope.ALL);
    }
    if (permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation,
            entityName, Set.of(PermissionScope.OWN))) {
        return Optional.of(PermissionScope.OWN);
    }
    return Optional.empty();
}

public boolean hasPermission(Class<?> entity, Operation operation, PermissionScope requiredScope) {
    return resolveScope(entity, operation)
            .map(scope -> requiredScope == PermissionScope.OWN || scope == PermissionScope.ALL)
            .orElse(false);
}

/** Keycloak {@code sub} of the current user; the value stored as owner id. */
public String currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth instanceof JwtAuthenticationToken jwtAuth) {
        return jwtAuth.getToken().getSubject();
    }
    throw new AccessDeniedException("Not authenticated");
}
```

Needs `import java.util.Optional;`. `hasPermission` keeps its old behaviour, so the aspect from
Section 7 does not change.

### 9.6 `security/RowAccessGuard.java`: one place for row checks

Services should not repeat the scope logic. This helper does the two things every service needs:
"what scope do I filter with?" and "may the user touch this row?".

```java
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

    /** Scope to filter queries with. Throws 403 if the user has no grant at all. */
    public PermissionScope requireScope(Class<?> entity, Operation operation) {
        return permissionChecker.resolveScope(entity, operation)
                .orElseThrow(() -> new AccessDeniedException(
                        "Missing permission: " + operation + " on " + entity.getSimpleName()));
    }

    /** True if the current user owns the row. */
    public boolean isOwner(Ownable row) {
        return Objects.equals(row.getOwnerId(), permissionChecker.currentUserId());
    }

    /**
     * Throws 404 unless the user has ALL scope or owns the row.
     * Call it right after loading the row, before reading or changing it.
     */
    public <T extends Ownable> T checkRow(T row, Class<?> entity, Operation operation) {
        PermissionScope scope = requireScope(entity, operation);

        if (scope == PermissionScope.OWN && !isOwner(row)) {
            String name = entity.getSimpleName();
            throw new AppException(name + " not found",
                    HttpStatus.NOT_FOUND,
                    name.toUpperCase() + "_NOT_FOUND");
        }
        return row;
    }
}
```

**Why 404 and not 403 for a row the user does not own:** a 403 on `GET /api/orders/57` tells the
caller order 57 exists. A 404 is indistinguishable from "no such order", so IDs of other users'
rows cannot be probed. Use 403 only when the user has no grant for the entity at all.

### 9.7 Repositories: filter by owner in the query

List endpoints must filter **in SQL**. Never load all rows and filter in Java: it is slow, and it
breaks pagination (a page of 20 would come back with 3 items and a wrong total count).

The owner field in each method must match that entity's `getOwnerId()`:

```java
// Owner = creator (default Auditable rule)
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    List<Order> findByCreatedBy(String ownerId);

    Page<Order> findByCreatedBy(String ownerId, Pageable pageable);

    Optional<Order> findByIdAndCreatedBy(Long id, String ownerId);
}
```

```java
// Owner = the customer (Order overrides getOwnerId() with customer.keycloakId)
List<Order> findByCustomer_KeycloakId(String ownerId);
Page<Order> findByCustomer_KeycloakId(String ownerId, Pageable pageable);
```

```java
// Owner = the user themself: already exists
Optional<User> findByKeycloakId(String keycloakId);
```

### 9.8 Service patterns

Examples use `Order` (owner = creator). The same shape applies to every owned entity.

#### List

```java
@Override
public List<OrderResponse> getOrders() {
    PermissionScope scope = rowAccessGuard.requireScope(Order.class, Operation.READ);

    List<Order> orders = scope == PermissionScope.ALL
            ? orderRepository.findAll()
            : orderRepository.findByCreatedBy(permissionChecker.currentUserId());

    return orders.stream().map(this::mapToOrderResponse).toList();
}
```

Paged:

```java
@Override
public Page<OrderResponse> getOrders(Pageable pageable) {
    PermissionScope scope = rowAccessGuard.requireScope(Order.class, Operation.READ);

    Page<Order> page = scope == PermissionScope.ALL
            ? orderRepository.findAll(pageable)
            : orderRepository.findByCreatedBy(permissionChecker.currentUserId(), pageable);

    return page.map(this::mapToOrderResponse);
}
```

#### Search with filters (Specifications)

When a list endpoint also has filters (status, date range, …), add the owner condition as one more
`Specification` so it combines with the others in a single query:

```java
@Override
public Page<OrderResponse> searchOrders(OrderSearchInput input, Pageable pageable) {
    PermissionScope scope = rowAccessGuard.requireScope(Order.class, Operation.READ);

    Specification<Order> spec = Specification.where(OrderSpecifications.fromInput(input));

    if (scope == PermissionScope.OWN) {
        String me = permissionChecker.currentUserId();
        spec = spec.and((root, query, cb) -> cb.equal(root.get("createdBy"), me));
    }

    return orderRepository.findAll(spec, pageable).map(this::mapToOrderResponse);
}
```

#### Get one

```java
@Override
public OrderResponse getOrderById(Long id) {
    Order order = orderRepository.findById(id).orElseThrow(() -> new AppException(
            "Order not found", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    rowAccessGuard.checkRow(order, Order.class, Operation.READ);

    return mapToOrderResponse(order);
}
```

"Missing" and "not yours" return the **same** 404 message and code on purpose (9.6).

A one-query alternative is `findByIdAndCreatedBy(id, me)` when the scope is `OWN`. It saves loading
a row the user cannot see, but `checkRow` is simpler and keeps every entity on the same pattern.

#### Update

```java
@Override
@Transactional
public OrderResponse updateOrder(Long id, OrderUpdateInput input) {
    Order order = orderRepository.findById(id).orElseThrow(() -> new AppException(
            "Order not found", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    rowAccessGuard.checkRow(order, Order.class, Operation.UPDATE);   // UPDATE, not READ

    order.setStatus(input.getStatus());
    // ... other fields ...
    return mapToOrderResponse(order);
}
```

Check **before** changing anything. With `@Transactional` and dirty checking, a change made before
a failed check could still be flushed if the exception were caught somewhere.

#### Delete

```java
@Override
@Transactional
public void deleteOrder(Long id) {
    Order order = orderRepository.findById(id).orElseThrow(() -> new AppException(
            "Order not found", HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    rowAccessGuard.checkRow(order, Order.class, Operation.DELETE);

    orderRepository.delete(order);
}
```

#### Create

There is no existing row to check, so there is no ownership check. The object-level `CREATE` check
is done by `@RequirePermission`. `createdBy` is filled automatically by JPA auditing with the
current user's `sub`, so the new row is owned by its creator.

If the request can name a **different** owner (e.g. staff placing an order for `customerId`),
only users with `ALL` scope may do that:

```java
@Override
@Transactional
public OrderResponse createOrder(OrderCreateInput input) {
    PermissionScope scope = rowAccessGuard.requireScope(Order.class, Operation.CREATE);

    User customer;
    if (input.getCustomerId() == null || scope == PermissionScope.OWN) {
        // OWN users always order for themselves; any customerId they send is ignored
        customer = userRepository.findByKeycloakId(permissionChecker.currentUserId()).orElseThrow();
    } else {
        customer = userService.findUserById(input.getCustomerId());
    }

    Order order = Order.builder().customer(customer) /* ... */ .build();
    return mapToOrderResponse(orderRepository.save(order));
}
```

#### Example with the existing `User` entity

`User` is owned by the user themself (`getOwnerId()` returns `keycloakId`). With a CUSTOMER role
holding only `READ / OBJECT / OWN / User`:

```java
@Override
public List<UserResponse> getAllUsers() {
    PermissionScope scope = rowAccessGuard.requireScope(User.class, Operation.READ);

    List<User> users = scope == PermissionScope.ALL
            ? userRepository.findAll()
            : userRepository.findByKeycloakId(permissionChecker.currentUserId()).stream().toList();

    return users.stream().map(this::mapToUserResponse).toList();
}

@Override
public UserResponse getUserById(Long id) {
    User user = findUserById(id);
    rowAccessGuard.checkRow(user, User.class, Operation.READ);
    return mapToUserResponse(user);
}
```

Result: admins and `ALL` roles see everyone; the customer sees a list containing only themself,
and gets 404 for any other user's id.

### 9.9 Controllers

Controllers stay thin. Use the default `scope = OWN` on endpoints whose service applies row
filtering:

```java
@GetMapping
@RequirePermission(entity = Order.class, operation = Operation.READ)          // OWN: ALL or OWN grant
public ResponseEntity<List<OrderResponse>> getOrders() { ... }

@GetMapping("/{id}")
@RequirePermission(entity = Order.class, operation = Operation.READ)
public ResponseEntity<OrderResponse> getOrderById(@PathVariable Long id) { ... }

@PutMapping("/{id}")
@RequirePermission(entity = Order.class, operation = Operation.UPDATE)
public ResponseEntity<OrderResponse> updateOrder(...) { ... }

@DeleteMapping("/{id}")
@RequirePermission(entity = Order.class, operation = Operation.DELETE)
public ResponseEntity<Void> deleteOrder(@PathVariable Long id) { ... }
```

Use `scope = PermissionScope.ALL` only for endpoints whose service does **no** row filtering
(admin reports, exports). Otherwise an `OWN` user would be let in and see everything.

The annotation check and the service check overlap on purpose. The annotation gives a fast 403
before any work; the service decides which rows. The service check is the one that must never
be skipped: a new endpoint that forgets the annotation is still protected, but one that forgets
the service check leaks data.

### 9.10 Rules to keep

- Resolve the scope for the operation actually performed (`UPDATE` in update methods, not `READ`).
- Filter lists in the query, never in Java after loading.
- Call `checkRow` right after loading a row and before reading or modifying it.
- Return 404 for rows that exist but are not owned.
- Keep each repository's owner field in sync with that entity's `getOwnerId()`.
- Never accept an owner id from the request unless the user has `ALL` scope.
- Nested data counts too: `GET /api/orders/{id}/items` must check the parent order with
  `checkRow` first.

### 9.11 Alternatives considered

| Approach | Why not (for now) |
|---|---|
| Hibernate `@Filter` enabled per request with the owner id | Automatic, but applies to every query on the entity, including internal ones (e.g. the JWT converter's user lookup), and is easy to forget to enable or disable. Harder to debug. |
| Postgres Row-Level Security (RLS) | Strongest guarantee, but needs the user id pushed into every DB session (`SET app.user_id`) and policies written in SQL outside the codebase. |
| Checking ownership in the aspect | The aspect runs before the method and does not have the row; it would have to load it again and know each endpoint's id parameter. |

The explicit service pattern is chosen because it is visible in code review and easy to test.

### 9.12 Testing

Set up a CUSTOMER role with `READ / OBJECT / OWN / Order`, `UPDATE / OBJECT / OWN / Order` and two
customer users, A and B, each with one order.

| Caller | Request | Expected |
|---|---|---|
| A | `GET /api/orders` | 200, only A's order |
| A | `GET /api/orders/{A's id}` | 200 |
| A | `GET /api/orders/{B's id}` | 404 `ORDER_NOT_FOUND` |
| A | `GET /api/orders/99999` (missing) | 404 `ORDER_NOT_FOUND`, same body as above |
| A | `PUT /api/orders/{B's id}` | 404, B's order unchanged in the DB |
| A | `DELETE /api/orders/{A's id}` | 403 (CUSTOMER has no `DELETE` grant) |
| Role with `READ / OBJECT / ALL / Order` | `GET /api/orders` | 200, every order |
| Admin | any of the above | 200 / 204 on every row |
| A after changing their email in Keycloak | `GET /api/orders` | still sees their order (owner id is `sub`) |

---

## 10. Field-level enforcement (to do)

`FIELD` and `TOTAL` rows are seeded but not enforced yet. Intended behaviour:

| Operation | Rule |
|---|---|
| `READ` | A role sees a field if it has `TOTAL/READ` for the entity **or** `FIELD/READ` for that field. Other fields are removed (or nulled) in the response. |
| `UPDATE` | Every field present in the request body must be covered by `TOTAL/UPDATE` or `FIELD/UPDATE`. Otherwise 403 listing the forbidden fields. |
| `CREATE` | Same as `UPDATE` (CREATE borrows UPDATE field grants). |
| Admin | Bypasses both. |

Suggested query to add to `PermissionRepository`:

```java
@Query("""
        select p.fieldName
        from Role r join r.permissions p
        where r.id in :roleIds
          and p.level = com.pavan.furniture_ecom.model.enums.PermissionLevel.FIELD
          and p.operation = :operation
          and p.entityName = :entityName
        """)
Set<String> findGrantedFieldNames(@Param("roleIds") Collection<Long> roleIds,
                                  @Param("operation") Operation operation,
                                  @Param("entityName") String entityName);
```

Implementation options for READ masking: a Jackson `BeanSerializerModifier` / `@JsonFilter`, or
masking in the DTO mapper (`mapToUserResponse`) by nulling fields not in the granted set. The
mapper approach is simpler and explicit; the Jackson approach is automatic but harder to debug.

Note DTO field names must match entity field names for this to work without a mapping table.

---

## 11. Gotchas

### 11.1 Duplicates are not blocked by the database

Postgres 14 treats `NULL`s as distinct in `UNIQUE` constraints, and every permission row has a
`NULL` in `scope` or `field_name`. So the `@UniqueConstraint` does **not** reject duplicate tuples.
Duplicate protection relies on the seeder's set difference (and on running a single instance).
Postgres 15+ supports `UNIQUE NULLS NOT DISTINCT`, which would fix this at the DB level;
Hibernate cannot generate it, so it would need a manual DDL or a migration.

### 11.2 Schema drift with `ddl-auto: update`

`update` adds tables and columns but never drops columns or relaxes `NOT NULL`. When an entity
changes shape (e.g. the old `Permission.name NOT NULL` column), inserts fail until the stale
column or table is dropped manually. Long term, move to Flyway or Liquibase.

### 11.3 Lombok on entities

- Never put `@Data` on both sides of a bidirectional relation (infinite `toString`/`hashCode`).
- Use `@Builder.Default` on every initialized collection, or the builder sets it to `null`.
- Exclude collections from `@EqualsAndHashCode` / `@ToString` on `@Data` entities.

### 11.4 Never return entities from controllers

Map to DTOs (`UserResponse`, `RoleResponse`, …). Returning entities leaks fields such as
`keycloakId` and `password`, and bidirectional links cause infinite JSON recursion.

### 11.5 Extra query per request

`PermissionChecker.currentUser()` looks the user up by `keycloak_id` on every annotated request,
on top of the converter's lookup. Acceptable at this scale. If it matters later, have the
converter put the user id (or the `User`) on the authentication token as the principal.

---

## 12. Testing checklist

| Caller | Endpoint | Expected |
|---|---|---|
| User with an `isAdmin = true` role | any `@RequirePermission` endpoint | 200 |
| User with no roles | any `@RequirePermission` endpoint | 403 `ACCESS_DENIED` |
| User with CUSTOMER role, no grants | `GET /api/users` | 403 |
| Same user after granting `READ / OBJECT / ALL / User` | `GET /api/users` | 200 |
| Same user with only `READ / OBJECT / OWN / User` | `GET /api/users` (`scope = ALL`) | 403 |
| Same user with only `READ / OBJECT / OWN / User` | `GET /api/users/{id}` (`scope = OWN`) | 200 |
| Any user | `GET /api/auth` | 200 (public via `permitAll`, not affected) |

After a restart, the log should show `Permissions synced: 0 created` when no entity changed.

---

## 13. Reference

`daxwell-scm-server` implements the full version of this model:

| Topic | Location in `daxwell-scm-server` |
|---|---|
| Design doc | `docs/platform/permissions.md`, `docs/reference/standards/rbac-and-permissions.md` |
| Permission entity / enums | `model/auth/permission/Permission.java`, `PermissionLevel.java`, `PermissionScope.java`, `model/auth/DbOperation.java` |
| Seeder | `initializer/PermissionsInitializer.java` |
| Resolver (admin bypass, caching) | `service/auth/permission/PermissionResolverService.java` |
| Annotation + aspect | `annotation/security/RequirePermission.java`, `aop/aspect/RequirePermissionAspect.java` |
| Field READ masking | `graphql/instrumentation/FieldMaskingInstrumentation.java` |
| Field WRITE checks | `service/auth/permission/FieldAccessService.java` |

Features there that are intentionally left out here: Redis grant caching, `@DistributedLock` on
the seeder, `FILE` permissions for S3, metamodel snapshot diffing, `default_permission` (granted
to every role), mutation and route permissions, and a per-request active role (`X-Current-Role`).
