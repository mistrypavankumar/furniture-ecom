# Authentication & Authorization Setup

**Model:** Keycloak owns *authentication* (identity, credentials, tokens). Postgres owns
*authorization* (users mirrored locally, roles, permissions). The Spring Boot API trusts the
Keycloak JWT for "who is this", then looks up "what may they do" entirely in its own database.

Keycloak roles are deliberately **not** used for authorization. The token is an identity
assertion and nothing more.

---

## 1. Responsibility split

| Concern | Owner | Where it lives |
|---|---|---|
| Login, password, MFA, sessions, token issuance | Keycloak | `keycloak` DB (Postgres) |
| Stable user identifier (`sub`) | Keycloak | JWT claim, mirrored to `tbl_user.keycloak_id` |
| Profile (name, contact, flags) | Application | `tbl_user` in `db_local` |
| Roles, permissions, assignments | Application | `tbl_role`, `tbl_permission`, join tables |
| Access decisions (`@PreAuthorize`) | Application | Authorities loaded from DB per request |

The single link between the two systems is the `sub` claim. Everything else about a user is
application-owned.

---

## 2. Current infrastructure

Already running via `deploy/docker/docker-compose.yml`:

| Service | Address | Notes |
|---|---|---|
| Postgres 14 | `localhost:8899` | user `postgres` / `postgres123` |
| — `db_local` | | application schema (JPA, `ddl-auto: update`) |
| — `keycloak` | | Keycloak's own schema, created by `postgres/init/01-create-keycloak-db.sql` |
| Keycloak 26.7.4 | `http://localhost:9999` | dev mode, admin `admin` / `admin` |
| Spring Boot API | `http://localhost:8081` | `server.port: 8081` |

Bring it up:

```bash
cd deploy/docker
docker compose up -d
```

Keycloak's `master` realm has `sslRequired=NONE` so the console works over plain HTTP. That is a
**local-development-only** setting — see §10.

---

## 3. Keycloak realm and client setup

### 3.1 Create the realm

Everything below uses `kcadm` inside the container, which authenticates over loopback. The admin
console at http://localhost:9999 does the same things if you prefer clicking.

```bash
# authenticate once per container restart
docker exec keyclock /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin

# create the application realm
docker exec keyclock /opt/keycloak/bin/kcadm.sh create realms \
  -s realm=furniture-ecom \
  -s enabled=true \
  -s sslRequired=NONE \
  -s registrationAllowed=true \
  -s loginWithEmailAllowed=true \
  -s duplicateEmailsAllowed=false \
  -s accessTokenLifespan=900
```

`sslRequired=NONE` is needed here for the same reason as on `master`: requests reach the container
through Docker's port mapping and are not treated as loopback.

### 3.2 Public client — the frontend logs in through this

Authorization Code + PKCE. No client secret, because a browser cannot keep one.

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh create clients -r furniture-ecom \
  -s clientId=furniture-ecom-web \
  -s enabled=true \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=true \
  -s 'redirectUris=["http://localhost:3000/*","http://localhost:8081/*"]' \
  -s 'webOrigins=["http://localhost:3000","http://localhost:8081"]' \
  -s 'attributes={"pkce.code.challenge.method":"S256"}'
```

`directAccessGrantsEnabled=true` enables the password grant, which is convenient for `curl` testing.
Turn it off before any deployed environment.

### 3.3 Confidential client — the API uses this to administer users

Only needed if the API creates Keycloak users itself (§7, Option B).

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh create clients -r furniture-ecom \
  -s clientId=furniture-ecom-admin \
  -s enabled=true \
  -s publicClient=false \
  -s standardFlowEnabled=false \
  -s serviceAccountsEnabled=true

# grant its service account permission to manage users in this realm
docker exec keyclock /opt/keycloak/bin/kcadm.sh add-roles -r furniture-ecom \
  --uusername service-account-furniture-ecom-admin \
  --cclientid realm-management \
  --rolename manage-users --rolename view-users
```

Read the generated secret — it goes into the application config:

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients -r furniture-ecom \
  -q clientId=furniture-ecom-admin --fields id,clientId
# then, using the returned id:
docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients/<ID>/client-secret -r furniture-ecom
```

### 3.4 Audience mapper

Keycloak does not put your API in the token's `aud` claim by default. If you want the resource
server to validate audience (recommended), add a mapper to the public client:

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh create \
  clients/<WEB_CLIENT_ID>/protocol-mappers/models -r furniture-ecom \
  -s name=furniture-ecom-audience \
  -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config={"included.client.audience":"furniture-ecom-api","access.token.claim":"true"}'
```

### 3.5 Export the realm so this is reproducible

Manual console clicking is not a setup procedure. Once the realm is right, export it and commit it:

```bash
docker exec keyclock /opt/keycloak/bin/kc.sh export \
  --realm furniture-ecom --file /opt/keycloak/data/furniture-ecom-realm.json

docker cp keyclock:/opt/keycloak/data/furniture-ecom-realm.json \
  deploy/docker/keycloak/furniture-ecom-realm.json
```

To import on a fresh volume, mount that directory and add `--import-realm` to the start command in
`deploy/docker/keycloak/start-dev.sh`.

---

## 4. Database schema

### 4.1 Target shape

```
tbl_user
  id              bigserial PK
  keycloak_id     varchar   UNIQUE NOT NULL   -- the JWT 'sub' claim
  email           varchar   UNIQUE NOT NULL
  first_name, last_name, contact_number
  active          boolean
  created_date, created_by, last_modified_date, last_modified_by

tbl_role
  id, name UNIQUE (CUSTOMER | STAFF | ADMIN), description, audit columns

tbl_permission
  id, name UNIQUE ('product:read', 'order:refund', ...), description

tbl_user_role         (user_id, role_id)      -- many-to-many
tbl_role_permission   (role_id, permission_id) -- many-to-many
```

### 4.2 Changes to the existing entities

`User.java` needs three edits:

- **Add** `keycloakId` — unique, not null. This is the join key to Keycloak.
- **Remove** `password`. Keycloak owns credentials; a password column here is a liability, not a
  feature.
- **Remove** `isAdmin`. It is superseded by role assignment, and two parallel sources of "is this
  person an admin" will drift.

`Role.java` currently maps users as:

📄 **`src/main/java/com/pavan/furniture_ecom/model/Role.java`** — current, to be replaced

```java
@OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
@JoinColumn(name = "user_id")
private List<User> users;
```

This is almost certainly not what you want — it makes a role own a one-way list of users through a
FK column on `tbl_user`, so a user can hold exactly one role and `cascade = ALL` means deleting a
role deletes its users. Replace it with a many-to-many owned by `User`:

📄 **`src/main/java/com/pavan/furniture_ecom/model/User.java and model/Role.java`** — change

```java
// User.java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
    name = "tbl_user_role",
    joinColumns = @JoinColumn(name = "user_id"),
    inverseJoinColumns = @JoinColumn(name = "role_id"))
@Builder.Default
private Set<Role> roles = new HashSet<>();

// Role.java
@ManyToMany(mappedBy = "roles")
@Builder.Default
private Set<User> users = new HashSet<>();

@ManyToMany(fetch = FetchType.EAGER)
@JoinTable(
    name = "tbl_role_permission",
    joinColumns = @JoinColumn(name = "role_id"),
    inverseJoinColumns = @JoinColumn(name = "permission_id"))
@Builder.Default
private Set<Permission> permissions = new HashSet<>();
```

New `Permission` entity: `id`, `name` (unique), `description`.

> **Note on `ddl-auto: update`:** Hibernate will *add* the new columns and tables but will never
> drop `password`, `is_admin`, or the stray `user_id` FK. You will need to drop those by hand, or
> move to Flyway (§10) and do it in a migration.

### 4.3 Seed roles and permissions

These are reference data, not user data — they belong in a migration or a seed script, not in
manual inserts:

📄 **`src/main/resources/db/migration/V1__seed_roles.sql`** — seed data

```sql
INSERT INTO tbl_permission (name, description) VALUES
  ('product:read',   'View products'),
  ('product:write',  'Create and edit products'),
  ('order:read',     'View orders'),
  ('order:write',    'Place and edit orders'),
  ('order:refund',   'Issue refunds'),
  ('user:manage',    'Manage users and role assignments');

INSERT INTO tbl_role (name, description) VALUES
  ('CUSTOMER', 'Default role for self-registered shoppers'),
  ('STAFF',    'Catalogue and order operations'),
  ('ADMIN',    'Full administrative access');
```

Then map `CUSTOMER → product:read, order:read, order:write`, `STAFF → + product:write`,
`ADMIN → all`, via `tbl_role_permission`.

---

## 5. Spring Boot configuration

### 5.1 Dependencies

Add to `pom.xml`. These artifact names are the Spring Boot 4 style, matching the
`spring-boot-starter-webmvc` convention already in your POM — both were verified against the
`spring-boot-dependencies:4.1.1` BOM:

📄 **`pom.xml`** — add dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>

<!-- only if the API calls the Keycloak Admin REST API (§7 Option B) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-client</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

The legacy `spring-boot-starter-oauth2-resource-server` coordinate still resolves in 4.1.1, but
prefer the `security-` prefixed name for consistency.

### 5.2 `application.yaml`

Your current file has an empty `security.oauth:` placeholder — replace that whole block:

📄 **`src/main/resources/application.yaml`** — replace

```yaml
spring:
  application:
    name: furniture-ecom

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9999/realms/furniture-ecom
          audiences: furniture-ecom-api      # only if you added the mapper in §3.4

      # only for Option B in §7
      client:
        registration:
          keycloak-admin:
            provider: keycloak
            client-id: furniture-ecom-admin
            client-secret: ${KEYCLOAK_ADMIN_SECRET}
            authorization-grant-type: client_credentials
        provider:
          keycloak:
            issuer-uri: http://localhost:9999/realms/furniture-ecom

  datasource:
    url: jdbc:postgresql://localhost:8899/db_local
    username: postgres
    password: postgres123
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true

keycloak:
  admin:
    base-url: http://localhost:9999
    realm: furniture-ecom

server:
  port: 8081
```

`issuer-uri` must match the `iss` claim in the token **byte for byte**. Keycloak builds `iss` from
`KC_HOSTNAME`, which is `http://localhost:9999` in your compose file. If you later run the Spring
app *inside* Docker, it will reach Keycloak at `http://keyclock:8080` while tokens still say
`localhost:9999` — validation then fails. The fix is `issuer-uri` (for validation) plus
`jwk-set-uri` (for fetching keys) pointing at the internal address.

### 5.3 Getting the client secret

**A secret only exists for a confidential client.** Public clients have none, by design — a browser
cannot keep a secret, so Keycloak does not issue one. If the Credentials tab is missing in the
console, or the API returns `{"type": "secret"}` with no `value`, the client is public. That is the
cause, not a permissions problem:

```bash
# returns publicClient: true  ->  there is no secret to find
docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients -r furniture-ecom \
  -q clientId=oauth2-client-credential --fields clientId,publicClient,serviceAccountsEnabled
```

Make it confidential — in the console this is **Clients → your client → Settings → Client
authentication → On**; the equivalent command is:

```bash
CID=$(docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients -r furniture-ecom \
  -q clientId=oauth2-client-credential --fields id --format csv --noquotes | tr -d '\r')

docker exec keyclock /opt/keycloak/bin/kcadm.sh update clients/$CID -r furniture-ecom \
  -s publicClient=false
```

Keycloak generates a secret at that moment. Read it:

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh get clients/$CID/client-secret \
  -r furniture-ecom --fields value --format csv --noquotes | tr -d '\r'
```

In the console the same value is at **Clients → your client → Credentials → Client secret**.

To rotate it — do this whenever a secret has been pasted into a chat, a ticket, or a commit:

```bash
docker exec keyclock /opt/keycloak/bin/kcadm.sh create clients/$CID/client-secret \
  -r furniture-ecom
```

The old secret stops working immediately, so update every consumer in the same change.

> **`client_credentials` needs one more flag.** A confidential client can hold a secret, but the
> client-credentials grant also requires a service account:
> `-s serviceAccountsEnabled=true`. Without it the token endpoint rejects the grant with
> `unauthorized_client`. See §3.3 for the role grants that service account then needs.

### 5.4 Passing the secret to Spring

`application.yaml` references `${KEYCLOAK_ADMIN_SECRET}`, so the value stays out of the repo:

```bash
export KEYCLOAK_ADMIN_SECRET=$(docker exec keyclock /opt/keycloak/bin/kcadm.sh \
  get clients/$CID/client-secret -r furniture-ecom --fields value --format csv --noquotes | tr -d '\r')

./mvnw spring-boot:run
```

For day-to-day work put it in a git-ignored `.env` or your IDE run configuration rather than
re-exporting each time. Confirm `.env` is covered by `.gitignore` before writing a secret into it.

Verify the client can actually get a token:

```bash
curl -s -X POST \
  http://localhost:9999/realms/furniture-ecom/protocol/openid-connect/token \
  -d grant_type=client_credentials \
  -d client_id=oauth2-client-credential \
  -d client_secret=$KEYCLOAK_ADMIN_SECRET | jq -r '.access_token // .'
```

A JWT means the client is configured correctly. `unauthorized_client` means service accounts are
still off; `invalid_client` means the secret is wrong or the client is still public.

---

## 6. Wiring the request path

### 6.1 Security filter chain

📄 **`src/main/java/com/pavan/furniture_ecom/config/SecurityConfig.java`** — new

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
            .csrf(csrf -> csrf.disable())                 // stateless bearer-token API
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/public/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
            .build();
    }
}
```

### 6.2 The converter — where Keycloak identity becomes database authority

This is the heart of the design. It runs on every authenticated request: takes the validated JWT,
resolves the local user (creating it on first sight), and returns authorities read from Postgres.

📄 **`src/main/java/com/pavan/furniture_ecom/config/DbAuthoritiesJwtConverter.java`** — new

```java
@Component
@RequiredArgsConstructor
public class DbAuthoritiesJwtConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserProvisioningService provisioningService;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = provisioningService.resolveUser(jwt);   // JIT provisioning, §6.3

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new DisabledException("User is deactivated");
        }

        Set<GrantedAuthority> authorities = new HashSet<>();
        for (Role role : user.getRoles()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
            for (Permission p : role.getPermissions()) {
                authorities.add(new SimpleGrantedAuthority(p.getName()));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, user.getKeycloakId());
    }
}
```

Note what is absent: nothing reads `realm_access.roles` from the token. Keycloak could hand you a
token claiming `ADMIN` and it would change nothing.

### 6.3 Just-in-time user provisioning

The first time a Keycloak user calls the API, they have no row in `tbl_user`. Create it then:

📄 **`src/main/java/com/pavan/furniture_ecom/service/impl/UserServiceImpl.java`** — new

```java
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    @Transactional
    public User resolveUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();

        return userRepository.findByKeycloakId(keycloakId)
            .map(existing -> syncProfile(existing, jwt))
            .orElseGet(() -> createFromToken(jwt));
    }

    private User createFromToken(Jwt jwt) {
        Role defaultRole = roleRepository.findByName("CUSTOMER")
            .orElseThrow(() -> new IllegalStateException("CUSTOMER role not seeded"));

        User user = User.builder()
            .keycloakId(jwt.getSubject())
            .email(jwt.getClaimAsString("email"))
            .firstName(jwt.getClaimAsString("given_name"))
            .lastName(jwt.getClaimAsString("family_name"))
            .active(true)
            .roles(new HashSet<>(Set.of(defaultRole)))
            .build();

        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException race) {
            // two concurrent first-requests; the other one won
            return userRepository.findByKeycloakId(jwt.getSubject()).orElseThrow();
        }
    }

    private User syncProfile(User user, Jwt jwt) {
        // refresh only Keycloak-owned fields; never overwrite app-owned data
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.equals(user.getEmail())) {
            user.setEmail(email);
        }
        return user;
    }
}
```

Add to `UserRepository`:

📄 **`src/main/java/com/pavan/furniture_ecom/repository/UserRepository.java`** — add method

```java
Optional<User> findByKeycloakId(String keycloakId);
```

> **Performance:** this runs per request, so every call costs a user + roles + permissions lookup.
> Fine at your current scale. When it matters, cache by `sub` with a short TTL and evict on role
> change — do not reach for a longer token lifetime instead, since that delays revocation.

### 6.4 Protecting endpoints

📄 **`src/main/java/com/pavan/furniture_ecom/controller/`** — usage example

```java
@PreAuthorize("hasAuthority('product:write')")
@PostMapping("/api/products")
public ResponseEntity<ProductResponse> create(@RequestBody ProductCreateRequest request) { ... }

@PreAuthorize("hasRole('ADMIN')")
@PostMapping("/api/users/{id}/roles")
public ResponseEntity<Void> assignRole(@PathVariable Long id, @RequestBody RoleAssignRequest r) { ... }
```

Prefer permission checks (`hasAuthority('order:refund')`) over role checks in business endpoints.
Roles then stay a grouping concept you can reshape without touching controllers.

---

## 7. Registration flows

### Option A — user registers in Keycloak, app catches up

Keycloak's registration page (`registrationAllowed=true` from §3.1) creates the identity. The local
row appears automatically on the user's first API call via §6.3.

Simple, and the JIT path must exist anyway as a safety net. Downside: you cannot collect
application-specific fields (contact number, marketing consent) at signup.

### Option B — app drives registration

Your `UserController.createUser` stub fits this. `UserServiceImpl.createUser` currently checks
`existsByEmail` and then returns `null`; the full flow is:

1. Validate the request; reject if `existsByEmail`.
2. `POST /admin/realms/furniture-ecom/users` on the Keycloak Admin API, using a service-account
   token from the `furniture-ecom-admin` client, with `enabled: true` and a temporary password or
   a required action such as `UPDATE_PASSWORD` / `VERIFY_EMAIL`.
3. Read the new user's id from the `Location` header of the 201 response — that id is the `sub`.
4. Save `tbl_user` with that `keycloak_id`, plus the app-only profile fields.
5. Assign the default role in `tbl_user_role`.

**The ordering matters.** Keycloak first, database second. If step 4 fails you have an orphaned
Keycloak identity, which the JIT path in §6.3 will quietly adopt on first login. Reverse the order
and a failure leaves a local user who can never log in.

Wrap steps 4–5 in `@Transactional` and, if step 4 throws, delete the Keycloak user in a compensating
action — a local transaction cannot roll back a remote HTTP call.

---

## 8. End-to-end request flow

```
  Browser / frontend                Keycloak :9999              Spring API :8081        Postgres :8899
        │                                 │                            │                      │
        │─ 1. login (Auth Code + PKCE) ──▶│                            │                      │
        │◀─ 2. access token (JWT, RS256) ─│                            │                      │
        │                                 │                            │                      │
        │─ 3. GET /api/orders ────────────────────────────────────────▶│                      │
        │    Authorization: Bearer <JWT>  │                            │                      │
        │                                 │◀─ 4. fetch JWKS (cached) ──│                      │
        │                                 │                            │                      │
        │                                 │      5. verify signature,  │                      │
        │                                 │         issuer, expiry,    │                      │
        │                                 │         audience           │                      │
        │                                 │                            │                      │
        │                                 │      6. sub ──────────────▶│── find/create user ─▶│
        │                                 │                            │◀─ roles+permissions ─│
        │                                 │                            │                      │
        │                                 │      7. @PreAuthorize on   │                      │
        │                                 │         DB authorities     │                      │
        │◀─ 8. 200 or 403 ────────────────────────────────────────────│                      │
```

Steps 4–5 are pure token validation and never touch your database. Steps 6–7 never touch Keycloak.
That separation is the whole point: Keycloak going down stops new logins but does not stop
authorization for tokens already issued.

---

## 9. Verifying it works

Get a token with the password grant (enabled in §3.2):

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:9999/realms/furniture-ecom/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=furniture-ecom-web \
  -d username=testuser \
  -d password=test123 | jq -r .access_token)

echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq   # inspect the claims
```

Call the API and confirm the local row appeared:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/users/me

docker exec postgres psql -U postgres -d db_local \
  -c "select id, keycloak_id, email from tbl_user"
```

Checks worth writing as tests:

- No `Authorization` header → **401**
- Token from the `master` realm → **401** (issuer mismatch)
- Valid token, missing permission → **403**
- Role removed in the DB → next request is **403**, with no new login needed
- User deactivated (`active = false`) → **401/403** while their token is still valid

That last pair is the payoff of DB-side authorization: revocation is immediate and does not wait
for token expiry.

---

## 10. Before this leaves local development

| Item | Current state | Needed |
|---|---|---|
| Keycloak mode | `start-dev`, H2-free but dev profile | `kc.sh start` with `--optimized`, real hostname |
| TLS | `sslRequired=NONE` on both realms | `EXTERNAL` or `ALL`, behind TLS termination |
| Secrets | `postgres123` and admin/admin in compose | env vars or a secret manager, not in the repo |
| Keycloak admin | bootstrap `admin` / `admin` | a real account; delete the bootstrap user |
| Schema | `ddl-auto: update` | Flyway migrations; `ddl-auto: validate` |
| Password grant | enabled for curl testing | disabled; Auth Code + PKCE only |
| Realm config | created by hand | the exported JSON from §3.5, imported on startup |
| Token lifespan | 15 min access token | keep it short; rely on refresh tokens |

`ddl-auto: update` deserves particular attention — it will not make the destructive changes §4.2
calls for (dropping `password`, `is_admin`, the stray `user_id` FK), so those columns will linger
until a migration removes them.

---

## 11. Design notes

**Why mirror users at all, rather than reading everything from the token?** Foreign keys. An order,
a cart, an address all need to reference a user row. Storing a raw Keycloak UUID on every table and
having no local user entity means no referential integrity and a join against an HTTP API.

**Why not use Keycloak roles?** They would work, and for simple cases they are less code. The
tradeoff you are making: DB-side authorization gives you instant revocation, permissions as
first-class data you can query and report on, and the ability to change the permission model
without touching realm configuration. What you give up is Keycloak's admin UI for role management —
you will build that yourself.

**Do not mix the two.** If some checks read token roles and others read DB roles, the two will
disagree eventually, and the resulting bug will be an access-control bug. Pick DB authorities and
keep every check there.

**`sub` is the key, not email.** Email changes. Keycloak's `sub` is stable for the life of the
identity. Never match users on email.
