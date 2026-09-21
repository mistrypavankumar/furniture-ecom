# Register & Login API — Implementation Guide

Companion to [`authentication-setup.md`](./authentication-setup.md). That document covers the
architecture; this one is the build list for the endpoints the frontend calls.

**Approach:** the frontend never talks to Keycloak. It posts credentials to your API, and the API
relays them to Keycloak's token endpoint (Direct Access Grant) and returns the tokens. This is a
backend-for-frontend, and it is what you asked for. The tradeoff is in §10 — worth reading before
this reaches real users.

---

## 1. Endpoint contracts

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/auth/register` | public | Create Keycloak identity + local user row |
| `POST` | `/api/auth/login` | public | Exchange email/password for tokens |
| `POST` | `/api/auth/refresh` | public | Exchange refresh token for a new access token |
| `POST` | `/api/auth/logout` | public | Invalidate the refresh token |
| `GET` | `/api/users/me` | Bearer | Current user, roles and permissions |

### Register

```http
POST /api/auth/register
Content-Type: application/json

{ "email": "a@b.com", "password": "secret123",
  "firstName": "Ada", "lastName": "Lovelace", "contactNumber": "+91..." }
```

`201 Created` → `UserResponse`. `409` if the email already exists locally or in Keycloak.

### Login

```http
POST /api/auth/login
{ "email": "a@b.com", "password": "secret123" }
```

`200 OK`:

```json
{ "accessToken": "eyJ...", "refreshToken": "eyJ...",
  "expiresIn": 900, "refreshExpiresIn": 1800, "tokenType": "Bearer" }
```

`401` with `errorCode: INVALID_CREDENTIALS` on a bad password. Return the same response for an
unknown email — distinguishing the two tells an attacker which accounts exist.

The frontend then sends `Authorization: Bearer <accessToken>` on every subsequent call.

---

## 2. Folder structure

### 2.1 Target layout

`+` is a file you still need to create, `~` one you need to change, and an unmarked file is already
correct. Paths are under `src/main/java/com/pavan/furniture_ecom/`.

```
furniture-ecom/
├── deploy/docker/
│   ├── docker-compose.yml               postgres + keycloak
│   ├── keycloak/start-dev.sh            keycloak entrypoint
│   └── postgres/init/
│       └── 01-create-keycloak-db.sql    creates the 'keycloak' database
├── docs/
│   ├── authentication-setup.md          architecture and Keycloak setup
│   └── auth-api-implementation.md       this document
└── src/main/
    ├── java/com/pavan/furniture_ecom/
    │   ├── FurnitureEcomApplication.java
    │   │
    │   ├── config/                      cross-cutting Spring configuration
    │   │   ├── SecurityConfig.java              ~ add SecurityFilterChain + CORS   §7.1
    │   │   ├── DbAuthoritiesJwtConverter.java   ~ finish convert()                 §7.2
    │   │   ├── KeycloakProperties.java          + @ConfigurationProperties         §5.2
    │   │   ├── OAuth2ClientConfig.java          + AuthorizedClientManager bean     §6.3
    │   │   ├── RestClientConfig.java            + RestClient bean                  §6.1
    │   │   └── RoleSeeder.java                  + seeds roles/permissions          §9
    │   │
    │   ├── keycloak/                    everything that talks to Keycloak over HTTP
    │   │   ├── KeycloakAuthClient.java          + token endpoint: login/refresh/logout  §6.2
    │   │   └── KeycloakAdminClient.java         + Admin REST API: create/delete user     §6.3
    │   │
    │   ├── controller/
    │   │   ├── AuthController.java              + /api/auth/**                     §8.2
    │   │   └── UserController.java              ~ replace body with GET /me        §8.2
    │   │
    │   ├── service/
    │   │   ├── UserService.java                 resolveUser(Jwt)
    │   │   ├── AuthService.java                 + register/login contract          §8.1
    │   │   └── impl/
    │   │       ├── UserServiceImpl.java         ~ fix active default               §7.2
    │   │       └── AuthServiceImpl.java         + registration orchestration       §8.1
    │   │
    │   ├── repository/
    │   │   ├── UserRepository.java              existsByEmail, findByKeycloakId
    │   │   ├── RoleRepository.java              findByName
    │   │   └── PermissionRepository.java        findByName
    │   │
    │   ├── model/
    │   │   ├── User.java                        ~ keycloakId, roles M2M            §4.4
    │   │   ├── Role.java                        ~ M2M mapping + permissions        §4.4
    │   │   └── Permission.java                  authority strings
    │   │
    │   ├── dto/
    │   │   ├── auth/
    │   │   │   ├── RegisterRequest.java
    │   │   │   ├── LoginRequest.java
    │   │   │   ├── RefreshRequest.java
    │   │   │   └── TokenResponse.java
    │   │   ├── user/
    │   │   │   ├── UserResponse.java            ~ isAdmin -> roles/permissions     §8.3
    │   │   │   └── UserCreateRequest.java       superseded by RegisterRequest
    │   │   └── error/
    │   │       └── ErrorResponse.java
    │   │
    │   └── exception/
    │       ├── AppException.java
    │       └── GlobalExceptionHandler.java      ~ add validation handler           §8.4
    │
    └── resources/
        └── application.yaml                     ~ fix oauth2.client nesting        §4.3
```

### 2.2 What already exists

These are on disk now, written while working through this design. Review them against §4 before
trusting them — `User.java` and `Role.java` replaced earlier versions of yours:

```
model/Permission.java              new
model/User.java                    rewritten: keycloakId + roles M2M
model/Role.java                    rewritten: M2M mapping + permissions
repository/PermissionRepository.java   new
repository/RoleRepository.java     findByName added
dto/auth/RegisterRequest.java      new
dto/auth/LoginRequest.java         new
dto/auth/RefreshRequest.java       new
dto/auth/TokenResponse.java        new
```

Still missing entirely: the whole `keycloak/` package, `AuthController`, `AuthService`,
`AuthServiceImpl`, `KeycloakProperties`, `OAuth2ClientConfig`, `RestClientConfig`, `RoleSeeder`.

### 2.3 Why `keycloak/` is its own package

Every class that speaks HTTP to Keycloak lives in one package, and nothing outside it imports
`RestClient` or knows a Keycloak URL. `AuthServiceImpl` depends on `KeycloakAdminClient`, not on
the Admin REST API's shape.

That boundary is what makes the §10 migration cheap. Moving from the password grant to
Authorization Code rewrites `KeycloakAuthClient` and adds a callback endpoint; the service layer,
the entities and every other controller are untouched. If token-endpoint calls were scattered
through `AuthServiceImpl` and the controllers, that same change would reach across the codebase.

The same reasoning keeps `DbAuthoritiesJwtConverter` in `config/`: it is wiring between Spring
Security and your domain, not domain logic. The actual lookup lives in `UserService.resolveUser`,
which knows nothing about JWTs beyond the claims it reads.

---

## 3. Prerequisites — already applied to your Keycloak

I made these changes to the `furniture-ecom` realm while working out the commands. They are
verified working; no action needed unless you want them different.

| Setting on `oauth2-client-credential` | Value | Why |
|---|---|---|
| `publicClient` | `false` | so the client can hold a secret |
| `directAccessGrantsEnabled` | `true` | the password grant behind `/api/auth/login` |
| `serviceAccountsEnabled` | `true` | the client-credentials grant for the Admin API |
| service account roles | `manage-users`, `view-users` on `realm-management` | permission to create users |

Confirm any time with:

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients -r furniture-ecom \
  -q clientId=oauth2-client-credential \
  --fields clientId,publicClient,directAccessGrantsEnabled,serviceAccountsEnabled
```

Export the secret before running the app (the variable name matches your `application.yaml`):

```bash
CID=$(docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients -r furniture-ecom \
  -q clientId=oauth2-client-credential --fields id --format csv --noquotes | tr -d '\r')

export KEYCLOCK_ADMIN_SECRET=$(docker exec keyclock /opt/keycloak/bin/kcadm.sh \
  get clients/$CID/client-secret -r furniture-ecom --fields value --format csv --noquotes | tr -d '\r')
```

### 3.1 Making the secret reach the JVM

`export` only affects the shell you typed it in. If you run the app from IntelliJ, the JVM does
**not** inherit it, and Spring's configuration binder leaves an unresolvable `${...}` as **literal
text** rather than failing at startup. The literal string `${KEYCLOCK_ADMIN_SECRET}` is then sent to
Keycloak as the secret, and you get:

```
[invalid_token_response] ... 401 Unauthorized ...
{"error":"unauthorized_client","error_description":"Invalid client or Invalid client credentials"}
```

The two Keycloak error codes distinguish the cause precisely:

| Response | Meaning |
|---|---|
| `invalid_client` | the **client id** is wrong or unknown |
| `unauthorized_client` | client id is fine; the **secret** is wrong, empty, or an unresolved placeholder |

**Fix — have Spring read `.env` itself.** One line, and it works identically in the IDE and from
the command line:

📄 **`src/main/resources/application.yaml`** — add at the top level

```yaml
spring:
  config:
    import: optional:file:.env[.properties]
```

The `[.properties]` suffix tells Spring to parse the extensionless `.env` as a properties file.
`.env` is already in `.gitignore`, so the secret stays out of the repo. Keep it in strict
`KEY=value` form — no `export`, no quotes, no spaces around `=`; properties format treats `\` as an
escape and a leading `#` or `!` as a comment.

Alternatives, if you prefer:

- **IntelliJ run config** → Edit Configurations → Environment variables →
  `KEYCLOCK_ADMIN_SECRET=<value>`. Per-machine, not shared with teammates.
- **Shell only:** `set -a; . ./.env; set +a; ./mvnw spring-boot:run` — works for CLI runs, does
  nothing for the IDE.

To confirm what the running JVM actually has:

```bash
PID=$(lsof -ti tcp:8081 | head -1)
ps eww -p $PID | tr ' ' '\n' | grep '^KEYCLOCK_ADMIN_SECRET='
```

No output means the variable is absent, and the placeholder is being sent literally.


---

## 4. Fix these first — the build is currently red

`./mvnw compile` fails on two errors, and there are two more that only surface at runtime.

**4.1 `DbAuthoritiesJwtConverter.convert` has no return statement.** Completed in §7.2.

**4.2 `UserController.createUser` calls a method no longer on `UserService`.** You removed
`createUser` from the interface when you added `resolveUser`. Replace the controller body with the
`/me` endpoint in §8.2, and let `/api/auth/register` own user creation.

**4.3 `application.yaml` — `client:` is nested one level too high.** You have:

📄 **`src/main/resources/application.yaml`** — current, broken

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ...
    client:            # <-- sibling of oauth2, so Spring never reads it
      registration:
```

It must sit under `oauth2`:

📄 **`src/main/resources/application.yaml`** — corrected

```yaml
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9999/realms/furniture-ecom
      client:                          # <-- 6 spaces, under oauth2
        registration:
          keycloak-admin:
            provider: keycloak
            client-id: oauth2-client-credential
            client-secret: ${KEYCLOCK_ADMIN_SECRET}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            issuer-uri: http://localhost:9999/realms/furniture-ecom
```

As written, `ClientRegistrationRepository` never gets created and the admin client fails at
startup with "no qualifying bean".

**4.4 `User.keycloak_id` will break `findByKeycloakId`.** Spring Data derives the property name
`keycloakId` from the method and finds no such property on the entity, so the repository fails to
initialise at startup. Rename the Java field to `keycloakId` and pin the column explicitly:

📄 **`src/main/java/com/pavan/furniture_ecom/model/User.java`** — change

```java
@Column(name = "keycloak_id", unique = true, nullable = false)
private String keycloakId;
```

Keeps the snake_case column, gives Spring Data the camelCase property it needs.

---

## 5. Additional config

### 5.1 `pom.xml`

📄 **`pom.xml`** — add dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

Needed for `@Valid` and the `@NotBlank` / `@Email` annotations on the request DTOs.

### 5.2 `application.yaml` — add the client used for the login proxy

📄 **`src/main/resources/application.yaml`** — add

```yaml
keycloak:
  admin:
    base-url: http://localhost:9999
    realm: furniture-ecom
  client-id: oauth2-client-credential
  client-secret: ${KEYCLOCK_ADMIN_SECRET}
```

Bound by:

📄 **`src/main/java/com/pavan/furniture_ecom/config/KeycloakProperties.java`** — new

```java
@ConfigurationProperties(prefix = "keycloak")
@Component
@Data
public class KeycloakProperties {
    private Admin admin = new Admin();
    private String clientId;
    private String clientSecret;

    @Data
    public static class Admin {
        private String baseUrl;
        private String realm;
    }

    public String tokenUri() {
        return admin.getBaseUrl() + "/realms/" + admin.getRealm()
             + "/protocol/openid-connect/token";
    }

    public String logoutUri() {
        return admin.getBaseUrl() + "/realms/" + admin.getRealm()
             + "/protocol/openid-connect/logout";
    }

    public String adminUsersUri() {
        return admin.getBaseUrl() + "/admin/realms/" + admin.getRealm() + "/users";
    }
}
```

---

## 6. Keycloak clients

Two thin classes over `RestClient`. One for the token endpoint (login/refresh/logout), one for the
Admin REST API (create/delete user).

### 6.1 `RestClient` bean

📄 **`src/main/java/com/pavan/furniture_ecom/config/RestClientConfig.java`** — new

```java
@Configuration
public class RestClientConfig {
    @Bean
    RestClient keycloakRestClient() {
        return RestClient.builder().build();
    }
}
```

### 6.2 `KeycloakAuthClient` — the login proxy

📄 **`src/main/java/com/pavan/furniture_ecom/keycloak/KeycloakAuthClient.java`** — new

```java
@Component
@RequiredArgsConstructor
public class KeycloakAuthClient {

    private final RestClient restClient;
    private final KeycloakProperties props;

    public TokenResponse passwordGrant(String username, String rawPassword) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("username", username);
        form.add("password", rawPassword);
        form.add("scope", "openid");
        return postForToken(form, "INVALID_CREDENTIALS", "Invalid email or password");
    }

    public TokenResponse refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("refresh_token", refreshToken);
        return postForToken(form, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }

    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        form.add("refresh_token", refreshToken);

        restClient.post()
            .uri(props.logoutUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .toBodilessEntity();
    }

    private TokenResponse postForToken(MultiValueMap<String, String> form,
                                       String errorCode, String message) {
        return restClient.post()
            .uri(props.tokenUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .onStatus(HttpStatusCode::isError, (req, res) -> {
                throw new AppException(message, HttpStatus.UNAUTHORIZED, errorCode);
            })
            .body(TokenResponse.class);
    }
}
```

Never log `form` — it holds raw passwords and refresh tokens.

### 6.3 `KeycloakAdminClient` — creating the identity

The service-account token comes from the `keycloak-admin` registration you configured. Let Spring
manage it rather than calling the token endpoint yourself; it caches and refreshes on expiry.

📄 **`src/main/java/com/pavan/furniture_ecom/config/OAuth2ClientConfig.java`** — new

```java
@Configuration
public class OAuth2ClientConfig {
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clientService) {
        OAuth2AuthorizedClientProvider provider =
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build();
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
            registrations, clientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }
}
```

📄 **`src/main/java/com/pavan/furniture_ecom/keycloak/KeycloakAdminClient.java`** — new

```java
@Component
@RequiredArgsConstructor
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final KeycloakProperties props;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    /** @return the new user's Keycloak id (the 'sub' claim) */
    public String createUser(String email, String firstName, String lastName, String password) {
        Map<String, Object> payload = Map.of(
            "username", email,
            "email", email,
            "firstName", firstName,
            "lastName", lastName,
            "enabled", true,
            "emailVerified", false,
            "credentials", List.of(Map.of(
                "type", "password", "value", password, "temporary", false)));

        ResponseEntity<Void> response = restClient.post()
            .uri(props.adminUsersUri())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .onStatus(status -> status.value() == 409, (req, res) -> {
                throw new AppException("Email already registered",
                    HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
            })
            .toBodilessEntity();

        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new AppException("Keycloak did not return a user id",
                HttpStatus.INTERNAL_SERVER_ERROR, "KEYCLOAK_ERROR");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Compensating action when the local insert fails after the Keycloak user was created. */
    public void deleteUser(String keycloakId) {
        restClient.delete()
            .uri(props.adminUsersUri() + "/" + keycloakId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .retrieve()
            .toBodilessEntity();
    }

    private String adminToken() {
        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
            .withClientRegistrationId("keycloak-admin")
            .principal("furniture-ecom-backend")
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

The `201` response carries the new id only in the `Location` header — there is no response body.

---

## 7. Security wiring

### 7.1 `SecurityConfig` — you currently have no `SecurityFilterChain` bean

Without one, Spring Security's defaults lock every endpoint including `/api/auth/**`, and your
injected converter is never used.

📄 **`src/main/java/com/pavan/furniture_ecom/config/SecurityConfig.java`** — replace

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final DbAuthoritiesJwtConverter jwtConverter;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

Add `.cors(Customizer.withDefaults())` to the chain once the frontend origin is real. CSRF is
disabled because this is a stateless bearer-token API with no cookies — if you later move tokens
into cookies, CSRF protection has to come back.

### 7.2 Completing `DbAuthoritiesJwtConverter`

📄 **`src/main/java/com/pavan/furniture_ecom/config/DbAuthoritiesJwtConverter.java`** — replace convert()

```java
@Override
public AbstractAuthenticationToken convert(Jwt jwt) {
    User user = userService.resolveUser(jwt);

    if (!Boolean.TRUE.equals(user.getActive())) {
        throw new DisabledException("User account is deactivated");
    }

    Set<GrantedAuthority> authorities = new HashSet<>();
    for (Role role : user.getRoles()) {
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
        for (Permission permission : role.getPermissions()) {
            authorities.add(new SimpleGrantedAuthority(permission.getName()));
        }
    }
    return new JwtAuthenticationToken(jwt, authorities, user.getKeycloakId());
}
```

> **Your `createFromToken` sets `active(false)`.** Combined with the check above, every
> JIT-provisioned user is locked out with a 403 they cannot resolve. Either default to `true`, or
> keep `false` deliberately as a manual-approval gate and give admins an activation endpoint. Pick
> one — the two halves currently disagree.

---

## 8. Service and controller

### 8.1 `AuthService.register` — ordering is the whole design

📄 **`src/main/java/com/pavan/furniture_ecom/service/AuthService.java`** — new

```java
package com.pavan.furniture_ecom.service;

import com.pavan.furniture_ecom.dto.auth.LoginRequest;
import com.pavan.furniture_ecom.dto.auth.RegisterRequest;
import com.pavan.furniture_ecom.dto.auth.TokenResponse;
import com.pavan.furniture_ecom.dto.user.UserResponse;

public interface AuthService {
    UserResponse register(RegisterRequest request);
    TokenResponse login(LoginRequest request);
}
```

📄 **`src/main/java/com/pavan/furniture_ecom/service/impl/AuthServiceImpl.java`** — new

```java
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAuthClient keycloakAuthClient;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Override
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException("Email already registered",
                HttpStatus.CONFLICT, "USER_ALREADY_EXISTS");
        }

        Role defaultRole = roleRepository.findByName("CUSTOMER")
            .orElseThrow(() -> new AppException("CUSTOMER role not seeded",
                HttpStatus.INTERNAL_SERVER_ERROR, "ROLE_NOT_FOUND"));

        // 1. Keycloak first — it owns the identity
        String keycloakId = keycloakAdminClient.createUser(
            request.getEmail(), request.getFirstName(),
            request.getLastName(), request.getPassword());

        // 2. local row second
        try {
            User user = userRepository.save(User.builder()
                .keycloakId(keycloakId)
                .email(request.getEmail())
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .contactNumber(request.getContactNumber())
                .active(true)
                .roles(new HashSet<>(Set.of(defaultRole)))
                .build());
            return toResponse(user);
        } catch (RuntimeException ex) {
            // compensating delete: a DB rollback cannot undo the remote call
            keycloakAdminClient.deleteUser(keycloakId);
            throw ex;
        }
    }

    @Override
    public TokenResponse login(LoginRequest request) {
        return keycloakAuthClient.passwordGrant(request.getEmail(), request.getPassword());
    }
}
```

Keycloak first, database second. Reverse it and a failure leaves a local user who can never log in,
which is unrecoverable without manual surgery. This way a failure leaves an orphaned Keycloak
identity, which the JIT path in `resolveUser` adopts on first login anyway — self-healing.

Do **not** put `@Transactional` around the whole method. It would hold a DB transaction open across
two HTTP calls to Keycloak, and it cannot roll them back regardless.

### 8.2 Controllers

📄 **`src/main/java/com/pavan/furniture_ecom/controller/AuthController.java`** — new

```java
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final KeycloakAuthClient keycloakAuthClient;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(keycloakAuthClient.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        keycloakAuthClient.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }
}
```

Replace the body of `UserController` (its `createUser` no longer compiles):

📄 **`src/main/java/com/pavan/furniture_ecom/controller/UserController.java`** — replace

```java
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        User user = userRepository.findByKeycloakId(jwt.getSubject())
            .orElseThrow(() -> new AppException("User not found",
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        return ResponseEntity.ok(toResponse(user));
    }
}
```

### 8.3 `UserResponse`

Your DTO has `isAdmin`, which the role model replaces. Swap it for the resolved roles and
permissions so the frontend can drive its own UI:

📄 **`src/main/java/com/pavan/furniture_ecom/dto/user/UserResponse.java`** — change

```java
private Set<String> roles;
private Set<String> permissions;
```

### 8.4 Validation errors

`@Valid` throws `MethodArgumentNotValidException`, which your `GlobalExceptionHandler` currently
catches only via the catch-all `Exception` handler — so a bad email returns `500`, not `400`. Add:

📄 **`src/main/java/com/pavan/furniture_ecom/exception/GlobalExceptionHandler.java`** — add method

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidation(
        MethodArgumentNotValidException ex, HttpServletRequest request) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage())
        .collect(Collectors.joining(", "));
    return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
}
```

---

## 9. Seeding roles

`register` fails until a `CUSTOMER` role exists. For local development:

📄 **`src/main/java/com/pavan/furniture_ecom/config/RoleSeeder.java`** — new

```java
@Component
@RequiredArgsConstructor
public class RoleSeeder implements ApplicationRunner {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (roleRepository.count() > 0) return;

        Map<String, String> perms = Map.of(
            "product:read",  "View products",
            "product:write", "Create and edit products",
            "order:read",    "View own orders",
            "order:write",   "Place and edit orders",
            "order:refund",  "Issue refunds",
            "user:manage",   "Manage users and role assignments");

        Map<String, Permission> saved = new HashMap<>();
        perms.forEach((name, desc) -> saved.put(name,
            permissionRepository.save(
                Permission.builder().name(name).description(desc).build())));

        roleRepository.save(Role.builder()
            .name("CUSTOMER").description("Default role for registered shoppers")
            .permissions(new HashSet<>(List.of(
                saved.get("product:read"), saved.get("order:read"), saved.get("order:write"))))
            .build());

        roleRepository.save(Role.builder()
            .name("ADMIN").description("Full administrative access")
            .permissions(new HashSet<>(saved.values()))
            .build());
    }
}
```

This is a stopgap. Reference data belongs in a Flyway migration — see §10 of the setup doc.

---

## 10. The tradeoff you are accepting

Proxying login through your backend means the password grant (ROPC), and that has real costs:

- **Your server handles raw passwords.** Any request logging, APM tool or stack trace that captures
  request bodies now captures credentials.
- **No SSO, social login, MFA or "forgot password".** Those flows live in Keycloak's login pages,
  which this design bypasses. Adding Google login later means adopting Authorization Code anyway.
- **ROPC is removed in OAuth 2.1** and Keycloak documents it as discouraged.

What you get is a simpler frontend: no redirect dance, no PKCE, no Keycloak JS adapter.

For a learning project and an admin panel this is a reasonable trade. Before real users, the
migration is: frontend redirects to Keycloak, gets a code, exchanges it via a
`POST /api/auth/callback` endpoint on your backend. `/api/auth/register` and everything in §6–§9
stays; only the login path changes.

Storing tokens in the frontend: `localStorage` is XSS-readable. `httpOnly` cookies set by your
backend are the safer option, at the cost of needing CSRF protection back.

---

## 11. Testing the flow

```bash
# with spring.config.import in place (§3.1), .env is picked up automatically
./mvnw spring-boot:run
```

```bash
# register
curl -s -X POST http://localhost:8081/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"secret123",
       "firstName":"Ada","lastName":"Lovelace"}' | jq

# login
TOKEN=$(curl -s -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"secret123"}' | jq -r .accessToken)

# authenticated call
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/users/me | jq
```

Verify both sides actually got written:

```bash
docker exec postgres psql -U postgres -d db_local -c \
  "select u.email, r.name from tbl_user u
     join tbl_user_role ur on ur.user_id = u.id
     join tbl_role r on r.id = ur.role_id"

docker exec keyclock /opt/keycloak/bin/kcadm.sh get users -r furniture-ecom \
  -q email=ada@example.com --fields id,username,enabled
```

The `id` from Keycloak must equal `keycloak_id` in Postgres. If it does, the link between the two
systems is correct.

Worth asserting in tests:

- register with an existing email → `409`, and **no** orphaned Keycloak user
- login with a wrong password → `401`
- `/api/users/me` with no token → `401`
- `/api/users/me` after setting `active = false` in the DB → `403`, token still valid
- role removed from `tbl_user_role` → permissions disappear on the next request, no re-login

---

## 12. Stale columns

`ddl-auto: update` adds but never drops. After the model changes, `tbl_user` still carries
`password`, `is_admin` and the `user_id` FK left by the old `Role.users` mapping. All nullable, so
nothing breaks — but clear them out when you add Flyway:

📄 **`src/main/resources/db/migration/V2__drop_legacy_columns.sql`** — when you add Flyway

```sql
ALTER TABLE tbl_user DROP COLUMN IF EXISTS password;
ALTER TABLE tbl_user DROP COLUMN IF EXISTS is_admin;
ALTER TABLE tbl_user DROP COLUMN IF EXISTS user_id;
ALTER TABLE tbl_role DROP COLUMN IF EXISTS is_admin;
```
