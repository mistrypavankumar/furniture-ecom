# Authentication, Roles & Permissions — Step-by-Step Guide

A practical guide to adding login, roles and fine-grained permissions to a **Spring Boot** app.
It follows the design already built in `furniture-ecom`, written so you can repeat it in any new
project from scratch.

> **Stack:** Java 21 · Spring Boot 4 · Spring Security (OAuth2 Resource Server) · Keycloak ·
> PostgreSQL · Spring Data JPA · Lombok · AspectJ
>
> **Package used in examples:** `com.example.app`. Replace it with your own package.

For the deeper design notes (field-level permissions, Specifications, alternatives we
considered), see `rbac-permissions.md`. This guide is the "how do I build it again" version.

---

## Table of contents

0. [The big picture (read this first)](#0-the-big-picture-read-this-first)
1. [Set up Keycloak](#1-set-up-keycloak)
2. [Create the Spring Boot project](#2-create-the-spring-boot-project)
3. [Configure `application.yml`](#3-configure-applicationyml)
4. [Error handling basics](#4-error-handling-basics)
5. [Auditing base class (who created this row?)](#5-auditing-base-class-who-created-this-row)
6. [Entities: User, Role, Permission](#6-entities-user-role-permission)
7. [Repositories](#7-repositories)
8. [Talk to Keycloak (register, login)](#8-talk-to-keycloak-register-login)
9. [Security config: validate the token](#9-security-config-validate-the-token)
10. [Turn the token into a local User + roles](#10-turn-the-token-into-a-local-user--roles)
11. [Auth API: register, login, me](#11-auth-api-register-login-me)
12. [Roles: create them and assign to users](#12-roles-create-them-and-assign-to-users)
13. [Permissions: auto-seed them at startup](#13-permissions-auto-seed-them-at-startup)
14. [Assign permissions to a role](#14-assign-permissions-to-a-role)
15. [Check permissions with `@RequirePermission`](#15-check-permissions-with-requirepermission)
16. [Row-level access: "only my own data"](#16-row-level-access-only-my-own-data)
17. [Create the first admin](#17-create-the-first-admin)
18. [Test everything with curl](#18-test-everything-with-curl)
19. [Common mistakes](#19-common-mistakes)
20. [Checklist for a new project](#20-checklist-for-a-new-project)

---

## 0. The big picture (read this first)

Three different questions, three different layers:

| Question | Name | Who answers it | Fails with |
|---|---|---|---|
| **Who are you?** | Authentication | Keycloak (checks password, issues a JWT) + Spring (verifies the JWT) | `401 Unauthorized` |
| **What group are you in?** | Role | Our database (`tbl_user_role`) | `403 Forbidden` |
| **What exactly may you do?** | Permission | Our database (`tbl_role_permission`) | `403 Forbidden` |

Easy way to remember:

- **User** = a person (Pavan).
- **Role** = a job title (`ADMIN`, `CUSTOMER`, `STAFF`). A user can have many roles.
- **Permission** = one allowed action ("READ **User** rows, **ALL** of them"). A role has many permissions.

```
User ──< tbl_user_role >── Role ──< tbl_role_permission >── Permission
 (many-to-many)                       (many-to-many)
```

### Why Keycloak?

We do **not** store or check passwords ourselves. Keycloak does that and gives us a signed
token (JWT). Our app only has to verify the signature. Our own database keeps the **roles and
permissions**, because those are business rules we want to control from our own APIs.

### What happens on every request

```
Client                    Spring Boot app                                   Database
  │  GET /api/users         │                                                  │
  │  Authorization: Bearer  │                                                  │
  │ ───────────────────────►│ 1. Resource server checks JWT signature          │
  │                         │    (public key from Keycloak). Bad? → 401        │
  │                         │ 2. DbAuthoritiesJwtConverter:                    │
  │                         │    find User by token "sub" ───────────────────► │
  │                         │    user inactive? → 401                          │
  │                         │    roles → "ROLE_ADMIN", "ROLE_CUSTOMER"         │
  │                         │ 3. URL rules in SecurityConfig (authenticated?)  │
  │                         │ 4. @PreAuthorize("hasRole('ADMIN')") if present  │
  │                         │ 5. @RequirePermission aspect:                    │
  │                         │    does any of my roles have this permission? ──►│
  │                         │    no → 403                                      │
  │                         │ 6. Service: RowAccessGuard → own row only? → 404 │
  │ ◄───────────────────────│ 7. Response                                      │
```

### Permission = 5 columns, not a string

| Column | Meaning | Values |
|---|---|---|
| `operation` | What action | `READ`, `CREATE`, `UPDATE`, `DELETE` |
| `level` | How detailed | `OBJECT` (whole entity), `TOTAL` (all fields), `FIELD` (one field) |
| `scope` | Which rows (only for `OBJECT`) | `ALL` (every row), `OWN` (only rows I created) |
| `entity_name` | Which table/entity | `User`, `Role`, `Product` … |
| `field_name` | Which field (only for `FIELD`) | `email`, `price` … |

Example: "a seller can update their **own** products" =
`UPDATE · OBJECT · OWN · Product · null`.

You never type these rows by hand. The app **creates them automatically at startup** for every
entity (Step 13). You only decide **which role gets which rows** (Step 14).

---

## 1. Set up Keycloak

### 1.1 Run Keycloak with Docker

```bash
docker run -d --name keycloak -p 9999:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:latest start-dev
```

Open http://localhost:9999 and log in with `admin` / `admin`.

### 1.2 Create a realm

A realm is a separate "world" of users. One per project.

1. Top-left dropdown → **Create realm**.
2. Name: `my-app` (in this project: `furniture-ecom`). → **Create**.

### 1.3 Create a client (our backend)

1. **Clients** → **Create client**.
2. Client ID: `backend-client` (in this project: `oauth2-client-credential`). → Next.
3. Turn on:
   - **Client authentication** = ON (makes it a confidential client with a secret)
   - **Direct access grants** = ON (lets our `/login` send email + password → token)
   - **Service accounts roles** = ON (lets the backend call Keycloak's admin API to create users)
4. Save.
5. Tab **Credentials** → copy the **Client secret**.

### 1.4 Let the backend create users

1. Same client → tab **Service accounts roles** → **Assign role**.
2. Filter by clients → choose `realm-management` → select **`manage-users`** (and `view-users`).
3. Assign.

Without this, `POST /api/auth/register` fails with `403` from Keycloak.

### 1.5 Put the secret in `.env`

In the project root (add `.env` to `.gitignore`!):

```properties
KEYCLOAK_CLIENT_SECRET=paste-the-secret-here
```

---

## 2. Create the Spring Boot project

Use https://start.spring.io (Maven, Java 21) or copy these dependencies into `pom.xml`:

```xml
<dependencies>
    <!-- Database -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>

    <!-- REST API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Security: verify incoming JWTs -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
    </dependency>
    <!-- Security: get our own token to call the Keycloak admin API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
    </dependency>

    <!-- For our @RequirePermission annotation -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aspectj</artifactId>
    </dependency>

    <!-- Less boilerplate -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

> On Spring Boot 3.x the web starter is called `spring-boot-starter-web` and the AOP starter is
> `spring-boot-starter-aop`.

Remember to add Lombok to the compiler `annotationProcessorPaths` (see this project's `pom.xml`).

### Folder layout

```
com.example.app
├── annotation/     RequirePermission
├── aop/            RequirePermissionAspect
├── config/         SecurityConfig, DbAuthoritiesJwtConverter, JpaAuditingConfig, Keycloak*, RestClientConfig, OAuth2ClientConfig
├── controller/     AuthController, UserController, RoleController, PermissionController
├── dto/            request/response classes (never return entities!)
├── exception/      AppException, GlobalExceptionHandler
├── initializer/    PermissionsInitializer, AdminInitializer
├── keycloak/       KeycloakAuthClient, KeycloakAdminClient
├── model/          User, Role, Permission, Auditable, Ownable, enums/
├── repository/     UserRepository, RoleRepository, PermissionRepository
├── security/       PermissionChecker, RowAccessGuard
└── service/        interfaces + impl/
```

---

## 3. Configure `application.yml`

```yaml
spring:
  application:
    name: my-app

  config:
    import: optional:file:.env[.properties]   # loads KEYCLOAK_CLIENT_SECRET

  security:
    oauth2:
      # (A) Verify tokens that clients send us
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9999/realms/my-app
      # (B) Get a token for ourselves, to call Keycloak admin API
      client:
        registration:
          keycloak-admin:
            provider: keycloak
            client-id: backend-client
            client-secret: ${KEYCLOAK_CLIENT_SECRET}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            issuer-uri: http://localhost:9999/realms/my-app

  datasource:
    url: jdbc:postgresql://localhost:5432/my_app
    username: postgres
    password: ${DB_PASSWORD}

  jpa:
    hibernate:
      ddl-auto: update   # fine for learning; use Flyway/Liquibase in real projects

# Our own settings (read by KeycloakProperties)
keycloak:
  admin:
    base-url: http://localhost:9999
    realm: my-app
  client-id: backend-client
  client-secret: ${KEYCLOAK_CLIENT_SECRET}

app:
  bootstrap-admin-email: you@example.com   # see Step 17

server:
  port: 8081
```

**In easy words:**
- `issuer-uri` tells Spring "trust tokens signed by this Keycloak realm". Spring downloads the
  public keys automatically.
- The `client` block lets our backend log in *as itself* (no user) to create users in Keycloak.

---

## 4. Error handling basics

One exception type for all "expected" errors, and one handler that turns them into JSON.

`exception/AppException.java`

```java
@Getter
public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public AppException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }
}
```

`dto/error/ErrorResponse.java`

```java
@Data @Builder @AllArgsConstructor @NoArgsConstructor
public class ErrorResponse {
    private LocalDateTime timestamp;
    private String message;
    private HttpStatus status;
    private String error;
    private String path;
    private String errorCode;
}
```

`exception/GlobalExceptionHandler.java`

```java
import org.springframework.security.access.AccessDeniedException; // ← Spring's, NOT java.nio.file

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
        log.warn("{} at {} : {}", ex.getErrorCode(), request.getRequestURI(), ex.getMessage());
        return build(ex.getStatus(), ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to access this resource.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Please try again later.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .errorCode(code)
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build());
    }
}
```

---

## 5. Auditing base class (who created this row?)

We need to know **who owns a row** for the `OWN` scope later. Spring Data can fill
`createdBy` automatically from the logged-in user.

### 5.1 `model/Ownable.java`

```java
public interface Ownable {
    String getOwnerEmail();
}
```

### 5.2 `model/Auditable.java`

Every entity extends this, so every table gets 4 audit columns for free.

```java
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable implements Ownable {

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdDate;

    @CreatedBy
    @Column(updatable = false)
    private String createdBy;

    @LastModifiedDate
    private LocalDateTime lastModifiedDate;

    @LastModifiedBy
    private String lastModifiedBy;

    // The person who created the row is its owner
    @Override
    @Transient
    public String getOwnerEmail() {
        return createdBy;
    }
}
```

### 5.3 `config/JpaAuditingConfig.java`

Tells Spring **how** to find the current user's name: read `email` from the JWT.

```java
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth instanceof JwtAuthenticationToken jwtAuth) {
                String email = jwtAuth.getToken().getClaimAsString("email");
                return Optional.of(email != null ? email : jwtAuth.getToken().getSubject());
            }
            return Optional.of("system"); // startup jobs, seeders
        };
    }
}
```

> **Tip:** the email can change in Keycloak; the `sub` (Keycloak user id) never does. Owning rows
> by `sub` is safer. `rbac-permissions.md` §9.3 explains how to switch.

---

## 6. Entities: User, Role, Permission

### 6.1 Enums (`model/enums/`)

```java
public enum Operation       { READ, CREATE, UPDATE, DELETE }
public enum PermissionLevel { OBJECT, FIELD, TOTAL }
public enum PermissionScope { ALL, OWN }
public enum PowerRole       { ADMIN }
```

(One file per enum.)

### 6.2 `model/Permission.java`

```java
@Entity
@Table(name = "tbl_permission")
@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor @ToString
// Two permissions are "the same" if these 5 columns match (id is ignored).
// The seeder depends on this to avoid creating duplicates.
@EqualsAndHashCode(of = {"operation", "level", "scope", "entityName", "fieldName"})
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
    private PermissionScope scope;      // only for OBJECT

    @Column(nullable = false)
    private String entityName;          // e.g. "User"

    private String fieldName;           // only for FIELD
}
```

> Always use `EnumType.STRING`. The default (`ORDINAL`) stores 0/1/2 and breaks when you reorder
> the enum.

### 6.3 `model/Role.java`

```java
@Entity
@Table(name = "tbl_role")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Role extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;               // "ADMIN", "CUSTOMER"

    private String description;

    @Builder.Default
    private Boolean isAdmin = false;   // true = skip all permission checks

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tbl_role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Set<Permission> permissions = new HashSet<>();
}
```

### 6.4 `model/User.java`

```java
@Entity
@Table(name = "tbl_user")
@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor
public class User extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Link to Keycloak: the "sub" claim of the JWT. Never changes.
    @Column(unique = true, nullable = false)
    private String keycloakId;

    @Column(unique = true)
    private String email;

    private String firstName;
    private String lastName;
    private String contactNumber;

    @Builder.Default
    private Boolean isAdmin = false;

    @Builder.Default
    private Boolean active = false;    // inactive users get 401

    // EAGER because we need roles on every request anyway
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "tbl_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();
}
```

**Notes in easy words:**
- **No password column.** Keycloak keeps passwords. Storing them twice is a security risk.
- `User` is the **owning side** of the user↔role link (it has `@JoinTable`). So to give a user a
  role you change `user.getRoles()`, not the role.
- Avoid `@Data` on entities with relations — its `equals/hashCode/toString` walk the relations
  and can cause infinite loops or huge queries. Use `@Getter @Setter`.

---

## 7. Repositories

```java
public interface UserRepository extends JpaRepository<User, Long> {
    boolean existsByEmail(String email);
    Optional<User> findByKeycloakId(String keycloakId);
    Optional<User> findByEmail(String email);
    List<User> findByRoles_Id(Long roleId);          // users that have a role
}

public interface RoleRepository extends JpaRepository<Role, Long> {
    boolean existsByName(String name);
    Optional<Role> findByName(String name);
}

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    List<Permission> findByEntityName(String entityName);

    // "Does ANY of these roles have this permission?" — one fast query, returns true/false
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

---

## 8. Talk to Keycloak (register, login)

### 8.1 `config/KeycloakProperties.java` — read the `keycloak:` block

```java
@Data
@Component
@ConfigurationProperties(prefix = "keycloak")
public class KeycloakProperties {
    private Admin admin = new Admin();
    private String clientId;
    private String clientSecret;

    @Data
    public static class Admin {
        private String baseUrl;
        private String realm;
    }

    public String tokenUrl()      { return admin.getBaseUrl() + "/realms/" + admin.getRealm() + "/protocol/openid-connect/token"; }
    public String logoutUri()     { return admin.getBaseUrl() + "/realms/" + admin.getRealm() + "/protocol/openid-connect/logout"; }
    public String adminUsersUri() { return admin.getBaseUrl() + "/admin/realms/" + admin.getRealm() + "/users"; }
}
```

### 8.2 HTTP client + OAuth2 client manager

`config/RestClientConfig.java`

```java
@Configuration
public class RestClientConfig {
    @Bean
    RestClient restClient() {
        return RestClient.builder().build();
    }
}
```

`config/OAuth2ClientConfig.java` — fetches and **caches** the backend's own admin token
(client-credentials), refreshing it when it expires.

```java
@Configuration
public class OAuth2ClientConfig {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrations, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
```

### 8.3 `keycloak/KeycloakAdminClient.java` — create / delete users in Keycloak

```java
@Component
@RequiredArgsConstructor
public class KeycloakAdminClient {
    private final RestClient restClient;
    private final KeycloakProperties props;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    /** Creates the user in Keycloak and returns the new Keycloak user id. */
    public String createUser(String email, String firstName, String lastName, String password) {
        Map<String, Object> payload = Map.of(
                "username", email,
                "email", email,
                "firstName", firstName,
                "lastName", lastName,
                "enabled", true,
                "emailVerified", false,
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", password,
                        "temporary", false)));

        ResponseEntity<Void> response = restClient.post()
                .uri(props.adminUsersUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(status -> status.value() == 409, (req, res) -> {
                    throw new AppException("Email already registered", HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
                })
                .toBodilessEntity();

        // Keycloak returns the new id only in the Location header: .../users/{id}
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new AppException("Keycloak did not return a user id",
                    HttpStatus.INTERNAL_SERVER_ERROR, "KEYCLOAK_ERROR");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Undo step if saving the local user fails. */
    public void deleteUser(String keycloakId) {
        restClient.delete()
                .uri(props.adminUsersUri() + "/" + keycloakId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .retrieve()
                .toBodilessEntity();
    }

    private String adminToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId("keycloak-admin")   // matches application.yml
                .principal("my-app-backend")
                .build();

        OAuth2AuthorizedClient client = authorizedClientManager.authorize(request);
        if (client == null) {
            throw new AppException("Could not obtain Keycloak admin token",
                    HttpStatus.SERVICE_UNAVAILABLE, "KEYCLOAK_UNAVAILABLE");
        }
        return client.getAccessToken().getTokenValue();
    }
}
```

### 8.4 `keycloak/KeycloakAuthClient.java` — login, refresh, logout

```java
@Component
@RequiredArgsConstructor
public class KeycloakAuthClient {
    private final RestClient restClient;
    private final KeycloakProperties props;

    public TokenResponse passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("scope", "openid profile email");
        return postForToken(form, "Invalid email or password");
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = baseForm();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("scope", "openid profile email");
        return postForToken(form, "Refresh token is invalid or expired");
    }

    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = baseForm();
        form.add("refresh_token", refreshToken);
        restClient.post().uri(props.logoutUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().toBodilessEntity();
    }

    private MultiValueMap<String, String> baseForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        return form;
    }

    private TokenResponse postForToken(MultiValueMap<String, String> form, String message) {
        return restClient.post().uri(props.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new AppException(message, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
                })
                .body(TokenResponse.class);
    }
}
```

`dto/auth/TokenResponse.java`

```java
@Data @Builder @AllArgsConstructor @NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenResponse {
    @JsonProperty("access_token")       private String accessToken;
    @JsonProperty("refresh_token")      private String refreshToken;
    @JsonProperty("expires_in")         private Long expiresIn;
    @JsonProperty("refresh_expires_in") private Long refreshExpiresIn;
    @JsonProperty("token_type")         private String tokenType;
}
```

---

## 9. Security config: validate the token

`config/SecurityConfig.java`

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity                // enables @PreAuthorize on methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final DbAuthoritiesJwtConverter jwtConverter;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)          // no cookies → no CSRF risk
                .cors(Customizer.withDefaults())                // use the bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("http://localhost:5173"));   // your frontend
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
```

**Line by line in easy words:**
- `STATELESS` — no HTTP session. Every request must carry its own token.
- `permitAll()` — register/login/refresh must be open, otherwise nobody could ever log in.
  Don't open the whole `/api/auth/**` if `GET /api/auth` ("who am I") lives there.
- `oauth2ResourceServer().jwt()` — Spring reads `Authorization: Bearer <token>`, checks the
  signature with Keycloak's public key, checks it isn't expired. Bad token → **401**.
- `jwtAuthenticationConverter(jwtConverter)` — our hook to load the user's roles from **our**
  database instead of Keycloak (next step).

---

## 10. Turn the token into a local User + roles

### 10.1 `config/DbAuthoritiesJwtConverter.java`

Runs on **every** authenticated request.

```java
@Component
@RequiredArgsConstructor
public class DbAuthoritiesJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserService userService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = userService.resolveUser(jwt);           // find or create local row

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new DisabledException("User is disabled");  // → 401
        }

        // Spring convention: roles are authorities starting with "ROLE_"
        Set<GrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName().toUpperCase()))
                .collect(Collectors.toSet());

        return new JwtAuthenticationToken(jwt, authorities);
    }
}
```

### 10.2 `UserServiceImpl.resolveUser`

```java
@Override
public User resolveUser(Jwt jwt) {
    return userRepository.findByKeycloakId(jwt.getSubject())
            .map(existing -> syncProfile(existing, jwt))
            .orElseGet(() -> createFromToken(jwt));
}

// Someone exists in Keycloak but not in our DB (e.g. created by an admin in the Keycloak UI)
private User createFromToken(Jwt jwt) {
    User user = User.builder()
            .keycloakId(jwt.getSubject())
            .email(jwt.getClaimAsString("email"))
            .firstName(jwt.getClaimAsString("given_name"))
            .lastName(jwt.getClaimAsString("family_name"))
            .active(false)                 // safe default: an admin must activate them
            .build();
    try {
        return userRepository.save(user);
    } catch (DataIntegrityViolationException ex) {
        // Two requests arrived at the same time and both tried to insert
        return userRepository.findByKeycloakId(jwt.getSubject()).orElseThrow();
    }
}

private User syncProfile(User user, Jwt jwt) {
    String email = jwt.getClaimAsString("email");
    if (email != null && !email.equals(user.getEmail())) {
        user.setEmail(email);
        userRepository.save(user);
    }
    return user;
}
```

> Choose your default: `active(false)` = "invite only, admin approves"; `active(true)` = "anyone
> with a Keycloak account can use the app". Users who sign up through **our** `/register` are
> created with `active(true)` (Step 11).

---

## 11. Auth API: register, login, me

### 11.1 DTOs

```java
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RegisterRequest {
    @NotBlank @Email                                            private String email;
    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") private String password;
    @NotBlank                                                   private String firstName;
    @NotBlank                                                   private String lastName;
    private String contactNumber;
}

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class LoginRequest {
    @NotBlank private String email;
    @NotBlank private String password;
}

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshRequest {
    @NotBlank private String refreshToken;
}
```

`UserResponse` / `RoleResponse` — plain DTOs with the fields you want to show (see
`dto/user/UserResponse.java`, `dto/role/RoleResponse.java`). **Never return entities** from
controllers: you'd leak data and trigger lazy-loading errors.

### 11.2 `AuthServiceImpl`

Register = **Keycloak first, then our DB**. If our DB insert fails, delete the Keycloak user again
(a "compensating action"), so the two systems don't drift apart.

```java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAuthClient keycloakAuthClient;

    @Override
    public UserResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new AppException("Email already registered", HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
        }

        String keycloakId = keycloakAdminClient.createUser(
                req.getEmail(), req.getFirstName(), req.getLastName(), req.getPassword());

        try {
            User user = User.builder()
                    .keycloakId(keycloakId)
                    .email(req.getEmail())
                    .firstName(req.getFirstName())
                    .lastName(req.getLastName())
                    .contactNumber(req.getContactNumber())
                    .active(true)
                    .build();

            // Optional: give every new sign-up a default role
            roleRepository.findByName("CUSTOMER").ifPresent(user.getRoles()::add);

            return UserResponse.from(userRepository.save(user));
        } catch (RuntimeException ex) {
            keycloakAdminClient.deleteUser(keycloakId);   // undo
            throw ex;
        }
    }

    @Override
    public TokenResponse login(LoginRequest req) {
        return keycloakAuthClient.passwordGrant(req.getEmail(), req.getPassword());
    }
}
```

### 11.3 `AuthController`

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final UserService userService;
    private final KeycloakAuthClient keycloakAuthClient;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return ResponseEntity.ok(keycloakAuthClient.refresh(req.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        keycloakAuthClient.logout(req.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")              // needs a token (not in permitAll)
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(userService.getCurrentAppUser());
    }
}
```

✅ **Checkpoint:** register → login → call `/api/auth/me` with the token. If this works,
authentication is done.

---

## 12. Roles: create them and assign to users

### 12.1 Service (important parts)

```java
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    private final RoleRepository roleRepository;
    private final UserService userService;

    @Override
    public RoleResponse createRole(RoleCreateInput input) {
        if (roleRepository.existsByName(input.getName())) {
            throw new AppException("Role already exists with name: " + input.getName(),
                    HttpStatus.CONFLICT, "ROLE_ALREADY_EXISTS");
        }
        Role role = Role.builder()
                .name(input.getName().toUpperCase())
                .description(input.getDescription())
                .build();
        return RoleResponse.from(roleRepository.save(role));
    }

    @Override
    @Transactional
    public RoleResponse assignUserToRole(Long roleId, Long userId) {
        Role role = findByRoleId(roleId);
        User user = userService.findUserById(userId);

        if (Boolean.TRUE.equals(role.getIsAdmin())) {
            user.setIsAdmin(true);
        }
        user.getRoles().add(role);   // User is the owning side → this writes tbl_user_role
        return RoleResponse.from(role);
    }                                // @Transactional commits the change here

    @Override
    public Role findByRoleId(Long roleId) {
        return roleRepository.findById(roleId).orElseThrow(() -> new AppException(
                "Role not found with id: " + roleId, HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND"));
    }

    // getAllRoles, updateRole, deleteRoleById, getUsersByRoleId — plain CRUD
}
```

### 12.2 Controller — **admins only**

Role-based check with `@PreAuthorize`. `hasRole('ADMIN')` matches the authority `ROLE_ADMIN`
that our converter created in Step 10.

```java
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")           // applies to every method in this class
public class RoleController {
    private final RoleService roleService;

    @PostMapping
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody RoleCreateInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roleService.createRole(input));
    }

    @GetMapping
    public ResponseEntity<List<RoleResponse>> getAllRoles() {
        return ResponseEntity.ok(roleService.getAllRoles());
    }

    @PutMapping("/{id}/assign/{userId}")
    public ResponseEntity<RoleResponse> assignUserToRole(@PathVariable Long id, @PathVariable Long userId) {
        return ResponseEntity.ok(roleService.assignUserToRole(id, userId));
    }

    @GetMapping("/{id}/users")
    public ResponseEntity<List<UserResponse>> getUsersByRoleId(@PathVariable Long id) {
        return ResponseEntity.ok(roleService.getUsersByRoleId(id));
    }

    // PUT /{id} update, DELETE /{id} delete ...
}
```

> **Role vs permission — when to use which?**
> - `@PreAuthorize("hasRole('ADMIN')")` — simple, hard-coded. Good for admin-only screens.
> - `@RequirePermission(...)` — flexible. An admin can change who may do what **at runtime**
>   without a code change. Use it for business endpoints (products, orders, users…).

---

## 13. Permissions: auto-seed them at startup

Instead of typing hundreds of rows, the app looks at **every JPA entity** and creates all
possible permission rows for it. Add a new entity → restart → its permissions exist.

For each entity, e.g. `User`, it creates:

| Level | Rows | Example |
|---|---|---|
| `OBJECT` | 4 operations × 2 scopes = **8** | `READ · OBJECT · ALL · User` |
| `TOTAL` | READ + UPDATE = **2** | `UPDATE · TOTAL · null · User` |
| `FIELD` | 2 per field | `READ · FIELD · null · User · email` |

`initializer/PermissionsInitializer.java`

```java
@Slf4j
@Order(1)
@Component
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

        // desired − current = new rows to insert (works because of @EqualsAndHashCode on 5 columns)
        Set<Permission> toCreate = new HashSet<>(desired);
        toCreate.removeAll(current);

        // current − desired = rows for entities/fields that no longer exist
        Set<Permission> orphans = new HashSet<>(current);
        orphans.removeAll(desired);

        if (!toCreate.isEmpty()) {
            permissionRepository.saveAll(toCreate);
        }
        log.info("Permissions synced: {} created, {} total", toCreate.size(), current.size() + toCreate.size());

        if (!orphans.isEmpty()) {
            // Never auto-delete: roles may still reference them
            log.warn("{} orphan permissions found: {}", orphans.size(), orphans);
        }
    }

    private Set<Permission> buildDesiredPermissions() {
        Set<Permission> desired = new HashSet<>();

        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            String entityName = entity.getJavaType().getSimpleName();

            for (Operation op : Operation.values()) {
                for (PermissionScope scope : PermissionScope.values()) {
                    desired.add(permission(op, PermissionLevel.OBJECT, scope, entityName, null));
                }
            }
            for (Operation op : FIELD_OPERATIONS) {
                desired.add(permission(op, PermissionLevel.TOTAL, null, entityName, null));
            }
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
                .operation(op).level(level).scope(scope)
                .entityName(entityName).fieldName(fieldName)
                .build();
    }
}
```

✅ **Checkpoint:** start the app, then `select * from tbl_permission;` — you should see rows for
`User`, `Role`, `Permission`.

---

## 14. Assign permissions to a role

### 14.1 Request DTO

```java
@Data
public class RolePermissionsRequest {
    @NotNull           private Long roleId;
    @NotEmpty          private List<Long> permissionIds;
}
```

### 14.2 Service

```java
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {
    private final RoleService roleService;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional                                // ← REQUIRED, or the change is never saved
    public void assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        Role role = roleService.findByRoleId(roleId);
        role.getPermissions().addAll(loadAll(permissionIds));
    }

    @Override
    @Transactional
    public void removePermissionsFromRole(Long roleId, List<Long> permissionIds) {
        Role role = roleService.findByRoleId(roleId);
        loadAll(permissionIds).forEach(role.getPermissions()::remove);
    }

    @Override
    public List<Permission> findByEntity(String entityName) {
        return permissionRepository.findByEntityName(entityName);
    }

    // Fail loudly if any id doesn't exist
    private List<Permission> loadAll(List<Long> ids) {
        List<Permission> found = permissionRepository.findAllById(ids);
        Set<Long> foundIds = found.stream().map(Permission::getId).collect(Collectors.toSet());
        List<Long> missing = ids.stream().filter(id -> !foundIds.contains(id)).distinct().toList();

        if (!missing.isEmpty()) {
            throw new AppException("Permissions not found with ids: " + missing,
                    HttpStatus.NOT_FOUND, "PERMISSIONS_NOT_FOUND");
        }
        return found;
    }
}
```

**Why `@Transactional`?** Inside a transaction, JPA watches loaded entities. When the method
ends, it sees `role.permissions` changed and writes to `tbl_role_permission` automatically
("dirty checking"). Without a transaction the `Role` is detached and nothing is written.

### 14.3 Controller — admins only

```java
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PermissionController {
    private final PermissionService permissionService;

    // List permissions so the admin can find the ids: GET /api/permissions?entity=User
    @GetMapping
    public ResponseEntity<List<PermissionResponse>> list(@RequestParam String entity) {
        return ResponseEntity.ok(permissionService.findByEntity(entity).stream()
                .map(PermissionResponse::from).toList());
    }

    @PostMapping
    public ResponseEntity<Void> assign(@Valid @RequestBody RolePermissionsRequest req) {
        permissionService.assignPermissionsToRole(req.getRoleId(), req.getPermissionIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> remove(@Valid @RequestBody RolePermissionsRequest req) {
        permissionService.removePermissionsFromRole(req.getRoleId(), req.getPermissionIds());
        return ResponseEntity.noContent().build();
    }
}
```

> `@RequestBody` is required. Without it Spring tries to read `roleId` and `permissionIds` from
> query params and you get `null`s.
> `204 No Content` must have **no body** — use `noContent().build()`.

---

## 15. Check permissions with `@RequirePermission`

Goal: put one line above a controller method and let the framework do the check.

```java
@RequirePermission(entity = User.class, operation = Operation.READ, scope = PermissionScope.ALL)
```

### 15.1 The annotation — `annotation/RequirePermission.java`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)     // must survive to runtime so the aspect can see it
public @interface RequirePermission {
    Class<?> entity();
    Operation operation();

    // OWN (default) = having ALL or OWN is enough (the service will filter rows)
    // ALL           = you must be allowed on every row (e.g. "list all users")
    PermissionScope scope() default PermissionScope.OWN;
}
```

### 15.2 The checker — `security/PermissionChecker.java`

The brain. Answers "which scope does the current user have for (entity, operation)?"

```java
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

    /** ALL, OWN, or empty (= no permission at all). */
    public Optional<PermissionScope> resolveScope(Class<?> entity, Operation operation) {
        User user = currentUser();

        // 1. Admin role → everything allowed
        if (user.getRoles().stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsAdmin()))) {
            return Optional.of(PermissionScope.ALL);
        }
        if (user.getRoles().isEmpty()) {
            return Optional.empty();
        }

        List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
        String entityName = entity.getSimpleName();

        // 2. Best scope wins: check ALL first, then OWN
        if (permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation, entityName,
                Set.of(PermissionScope.ALL))) {
            return Optional.of(PermissionScope.ALL);
        }
        if (permissionRepository.existsGrant(roleIds, PermissionLevel.OBJECT, operation, entityName,
                Set.of(PermissionScope.OWN))) {
            return Optional.of(PermissionScope.OWN);
        }
        return Optional.empty();
    }

    public String currentUserEmail() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getClaimAsString("email");
        }
        throw new AccessDeniedException("Not authenticated");
    }

    private User currentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            return userRepository.findByKeycloakId(jwt.getToken().getSubject())
                    .orElseThrow(() -> new AccessDeniedException("User not found"));
        }
        throw new AccessDeniedException("Not authenticated");
    }
}
```

**The scope rule as a table:**

| Endpoint asks for | User has `ALL` | User has `OWN` | User has nothing |
|---|---|---|---|
| `scope = OWN` (default) | ✅ | ✅ (service filters rows) | ❌ 403 |
| `scope = ALL` | ✅ | ❌ 403 | ❌ 403 |

### 15.3 The aspect — `aop/RequirePermissionAspect.java`

An **aspect** is code that runs automatically *before* any method carrying our annotation.

```java
import org.springframework.security.access.AccessDeniedException;   // ← Spring's!

@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {
    private final PermissionChecker permissionChecker;

    // "Before any method annotated with @RequirePermission, call me and pass the annotation"
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

> ⚠️ Two easy-to-miss mistakes (both were in the first version of this project):
> 1. `@Before("@annotation(requirePermission")` — missing `)` → app fails to start.
> 2. `import java.nio.file.AccessDeniedException` — wrong class! It's a file-system exception,
>    so our handler doesn't catch it and the user gets **500** instead of **403**. Always import
>    `org.springframework.security.access.AccessDeniedException`.

### 15.4 Use it

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    // Only people who may read EVERY user
    @GetMapping
    @RequirePermission(entity = User.class, operation = Operation.READ, scope = PermissionScope.ALL)
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    // ALL or OWN is fine; the service decides which row they may see (Step 16)
    @GetMapping("/{id}")
    @RequirePermission(entity = User.class, operation = Operation.READ)
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }
}
```

---

## 16. Row-level access: "only my own data"

`@RequirePermission` only says "you may call this endpoint". If you only have `OWN`, the
**service** must still make sure you only touch rows you own.

### 16.1 `security/RowAccessGuard.java`

```java
@Component
@RequiredArgsConstructor
public class RowAccessGuard {
    private final PermissionChecker permissionChecker;

    /** Returns ALL or OWN, or throws 403. */
    public PermissionScope requireScope(Class<?> entity, Operation operation) {
        return permissionChecker.resolveScope(entity, operation)
                .orElseThrow(() -> new AccessDeniedException(
                        "Missing permission: " + operation + " on " + entity.getSimpleName()));
    }

    public boolean isOwner(Ownable row) {
        return Objects.equals(row.getOwnerEmail(), permissionChecker.currentUserEmail());
    }

    /**
     * Call right after loading a row. With OWN scope and someone else's row → 404.
     * (404, not 403, so we don't reveal that the row exists.)
     */
    public <T extends Ownable> T checkRow(T row, Class<T> entity, Operation operation) {
        PermissionScope scope = requireScope(entity, operation);

        if (scope == PermissionScope.OWN && !isOwner(row)) {
            String name = entity.getSimpleName();
            throw new AppException(name + " not found", HttpStatus.NOT_FOUND,
                    name.toUpperCase() + "_NOT_FOUND");
        }
        return row;
    }
}
```

### 16.2 Use it in services

Example with a `Product` entity that extends `Auditable`. Imagine a marketplace: a **SELLER**
role has `OWN` scope (only their own products), a **STAFF** role has `ALL` scope.

```java
// Get one
public ProductResponse getProduct(Long id) {
    Product product = productRepository.findById(id)
            .orElseThrow(() -> new AppException("Product not found", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    rowAccessGuard.checkRow(product, Product.class, Operation.READ);
    return ProductResponse.from(product);
}

// List: filter IN THE QUERY, not in Java (paging stays correct)
public List<ProductResponse> getProducts() {
    PermissionScope scope = rowAccessGuard.requireScope(Product.class, Operation.READ);
    List<Product> products = (scope == PermissionScope.ALL)
            ? productRepository.findAll()
            : productRepository.findByCreatedBy(permissionChecker.currentUserEmail());
    return products.stream().map(ProductResponse::from).toList();
}

// Update / delete: load → checkRow → change
@Transactional
public ProductResponse updateProduct(Long id, ProductUpdateRequest req) {
    Product product = rowAccessGuard.checkRow(findOrThrow(id), Product.class, Operation.UPDATE);
    product.setPrice(req.getPrice());
    return ProductResponse.from(product);
}
```

Repository:

```java
List<Product> findByCreatedBy(String createdBy);
```

> **Special case — `User`:** a user row is usually created by "system" or an admin, so
> `createdBy` isn't the person. For `User`, override ownership:
> ```java
> @Override @Transient
> public String getOwnerEmail() { return email; }   // I own my own user row
> ```

---

## 17. Create the first admin

Chicken-and-egg problem: only admins can assign roles, but nobody is admin yet. Seed an `ADMIN`
role and promote one email from config at startup.

`initializer/AdminInitializer.java`

```java
@Slf4j
@Order(2)                         // after PermissionsInitializer
@Component
@RequiredArgsConstructor
public class AdminInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;

    @Value("${app.bootstrap-admin-email:}")
    private String adminEmail;

    @Override
    @Transactional
    public void run(String... args) {
        Role admin = roleRepository.findByName(PowerRole.ADMIN.name())
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name(PowerRole.ADMIN.name())
                        .description("Full access")
                        .isAdmin(true)
                        .build()));

        roleRepository.findByName("CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .name("CUSTOMER")
                        .description("Default role for sign-ups")
                        .build()));

        if (adminEmail.isBlank()) return;

        userRepository.findByEmail(adminEmail).ifPresentOrElse(user -> {
            if (user.getRoles().add(admin)) {
                user.setIsAdmin(true);
                user.setActive(true);
                log.info("Promoted {} to ADMIN", adminEmail);
            }
        }, () -> log.warn("Bootstrap admin {} not found yet — register, then restart", adminEmail));
    }
}
```

**How to use:** set `app.bootstrap-admin-email`, register that email via `/api/auth/register`,
restart the app. That user is now admin.

---

## 18. Test everything with curl

```bash
BASE=http://localhost:8081

# 1. Register
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"Secret123!","firstName":"Pavan","lastName":"M"}'

# 2. Restart the app once so AdminInitializer promotes you, then log in
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"Secret123!"}' | jq -r .access_token)

# 3. Who am I?
curl -s $BASE/api/auth/me -H "Authorization: Bearer $TOKEN" | jq

# 4. Create a STAFF role
curl -s -X POST $BASE/api/roles -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"STAFF","description":"Shop staff"}' | jq

# 5. Find the permission ids for User
curl -s "$BASE/api/permissions?entity=User" -H "Authorization: Bearer $TOKEN" | jq

# 6. Give STAFF "READ User ALL" (say role id 3, permission id 12)
curl -s -X POST $BASE/api/permissions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"roleId":3,"permissionIds":[12]}' -w '%{http_code}\n'

# 7. Put a second user in STAFF and try GET /api/users with their token
curl -s -X PUT $BASE/api/roles/3/assign/2 -H "Authorization: Bearer $TOKEN" | jq
```

**What you should see:**

| Situation | Result |
|---|---|
| No token / expired token | `401` |
| Valid token, user `active = false` | `401` |
| Valid token, no matching permission | `403` `ACCESS_DENIED` |
| `OWN` permission, someone else's row | `404` |
| Admin | `200` everywhere |

You can decode any token at https://jwt.io to check `sub`, `email`, `exp`.

---

## 19. Common mistakes

| Symptom | Cause | Fix |
|---|---|---|
| Always `401` | `issuer-uri` doesn't exactly match the token's `iss` (e.g. `localhost` vs `127.0.0.1`) | Use the same host everywhere |
| `401` right after login | Local user has `active = false` | Activate the user, or change the default in `createFromToken` |
| Register fails with Keycloak `403` | Service account lacks `manage-users` | Step 1.4 |
| Login fails `invalid_grant` / "unauthorized_client" | Direct access grants OFF | Step 1.3 |
| `500` instead of `403` | Wrong `AccessDeniedException` import | Use Spring Security's |
| App won't start: pointcut error | Typo in `@Before("@annotation(...)")` | Check parentheses; parameter name must match |
| `hasRole('ADMIN')` never matches | Authority isn't prefixed `ROLE_` | Converter must add `"ROLE_"` |
| Assigning permissions "works" but nothing saved | No `@Transactional` on service | Add it |
| Assigning a role to a user not saved | Changed `role` side instead of `user.getRoles()` | Change the owning side (`User`) |
| `StackOverflowError` / huge logs | `@Data`/`@ToString` on entities with relations | `@Getter @Setter`, exclude relations |
| `LazyInitializationException` | Returning entities from controllers | Map to DTOs inside the service |
| Duplicate permission rows | `@EqualsAndHashCode` missing on `Permission` | Keep the 5-column equals; optionally add a DB unique index |
| `null` params in controller | Missing `@RequestBody` / `@RequestParam` | Add them |

---

## 20. Checklist for a new project

Copy this list and tick it off:

**Keycloak**
- [ ] Realm created
- [ ] Confidential client: Client auth ON, Direct access grants ON, Service accounts ON
- [ ] Service account has `realm-management → manage-users, view-users`
- [ ] Client secret in `.env` (and `.env` in `.gitignore`)

**Spring setup**
- [ ] Dependencies: data-jpa, web, validation, oauth2-resource-server, oauth2-client, aspectj, lombok
- [ ] `application.yml`: `issuer-uri`, `keycloak-admin` client registration, `keycloak:` block
- [ ] `AppException` + `GlobalExceptionHandler` (Spring's `AccessDeniedException`)

**Data model**
- [ ] `Ownable`, `Auditable` + `JpaAuditingConfig`
- [ ] Enums: `Operation`, `PermissionLevel`, `PermissionScope`, `PowerRole`
- [ ] `Permission` (5-column equals), `Role` (permissions M:N), `User` (keycloakId, roles M:N EAGER)
- [ ] Repositories incl. `PermissionRepository.existsGrant`

**Authentication**
- [ ] `KeycloakProperties`, `RestClientConfig`, `OAuth2ClientConfig`
- [ ] `KeycloakAdminClient` (create/delete user), `KeycloakAuthClient` (login/refresh/logout)
- [ ] `SecurityConfig` (stateless, permitAll for register/login/refresh, JWT converter)
- [ ] `DbAuthoritiesJwtConverter` + `UserService.resolveUser`
- [ ] `AuthController`: register, login, refresh, logout, me

**Roles**
- [ ] `RoleService` / `RoleController` with `@PreAuthorize("hasRole('ADMIN')")`
- [ ] `AdminInitializer` (ADMIN + CUSTOMER roles, bootstrap admin email)

**Permissions**
- [ ] `PermissionsInitializer` (auto-seed per entity)
- [ ] `PermissionService` (`@Transactional`) + `PermissionController` (list, assign, remove)
- [ ] `@RequirePermission` + `PermissionChecker` + `RequirePermissionAspect`
- [ ] `RowAccessGuard` used in every service method that reads/changes a single row
- [ ] Owner-filtered repository queries for list endpoints

**Test**
- [ ] 401 without token, 403 without permission, 404 for other people's rows, 200 for admin

---

### Next steps (when you're ready)

- **Field-level permissions** (`FIELD` / `TOTAL`): hide or block fields like `costPrice` or
  `email` per role — step-by-step in **`field-level-permissions-guide.md`**.
- **Caching** the permission lookup (it's one DB query per request today).
- **Flyway** migrations instead of `ddl-auto: update`.
- **Integration tests** with Testcontainers (Postgres + Keycloak).
