# Field-Level Permissions — Step-by-Step Guide

**What this is:** show or hide *single fields* (like a product's `costPrice`) and allow or block
*changing* single fields (like `price`), depending on the user's role.

**Before you start:** finish `auth-roles-permissions-guide.md` first. This guide builds on it
(`Permission`, `PermissionChecker`, `RowAccessGuard`, `@RequirePermission`, the seeder).

> **Package used in examples:** `com.example.app`. Replace it with your own package.

---

## Table of contents

0. [The idea in plain words](#0-the-idea-in-plain-words)
1. [Our example: the `Product` entity](#1-our-example-the-product-entity)
2. [Which permission rows do we need?](#2-which-permission-rows-do-we-need)
3. [Step 1 — Two new queries in `PermissionRepository`](#3-step-1--two-new-queries-in-permissionrepository)
4. [Step 2 — `FieldAccess`: "which fields may I touch?"](#4-step-2--fieldaccess-which-fields-may-i-touch)
5. [Step 3 — Teach `PermissionChecker` about fields](#5-step-3--teach-permissionchecker-about-fields)
6. [Step 4 — `FieldGuard`: hide fields and block changes](#6-step-4--fieldguard-hide-fields-and-block-changes)
7. [Step 5 — DTOs for `Product`](#7-step-5--dtos-for-product)
8. [Step 6 — Use it in `ProductService`](#8-step-6--use-it-in-productservice)
9. [Step 7 — `ProductController`](#9-step-7--productcontroller)
10. [Step 8 — Tell the frontend which fields it may show](#10-step-8--tell-the-frontend-which-fields-it-may-show)
11. [Step 9 — Give roles their field permissions](#11-step-9--give-roles-their-field-permissions)
12. [Step 10 — Test it](#12-step-10--test-it)
13. [Common mistakes and gotchas](#13-common-mistakes-and-gotchas)
14. [Checklist](#14-checklist)

---

## 0. The idea in plain words

So far our permissions work on **whole rows**:

> "Can this user READ products?" → yes or no.

Sometimes that is not enough. A customer should see a product, but **not** what the shop paid
for it. A warehouse worker may change the stock count, but **not** the price.

That is field-level permission:

> "Can this user READ **the `costPrice` field** of products?"
> "Can this user UPDATE **the `price` field** of products?"

### Two checks, always in this order

```
Request comes in
   │
   ▼
① OBJECT check  (@RequirePermission / RowAccessGuard)   → "May I touch products at all?"   no → 403
   │
   ▼
② FIELD check   (FieldGuard)                             → "Which fields of it?"
        READ:   fields you can't see are removed from the JSON
        UPDATE: if you send a field you can't change → 403
```

The field check **never replaces** the object check. You need both.

### The three permission levels (reminder)

| Level | Means | Example row |
|---|---|---|
| `OBJECT` | the whole entity (can I call the endpoint?) | `READ · OBJECT · ALL · Product` |
| `TOTAL` | **every** field of the entity (the "all fields" shortcut) | `READ · TOTAL · Product` |
| `FIELD` | **one** field | `READ · FIELD · Product · price` |

A user may use a field if **any** of their roles has **`TOTAL`** for that operation **or**
**`FIELD`** for that exact field. Admins skip all checks.

### Which operations exist on fields?

| Operation | Field rule |
|---|---|
| `READ` | Fields you can't read are **hidden** in the response (not an error). |
| `UPDATE` | Sending a field you can't update is an **error (403)**. |
| `CREATE` | Uses the **UPDATE** field rules (if you may set a field later, you may set it at the start). |
| `DELETE` | No field rule. You delete a whole row, not a field. |

Why hide on READ but error on UPDATE? Hiding is friendly: the screen still works, just with less
data. For UPDATE, silently ignoring a field would confuse the user ("I changed the price, why
didn't it save?"), so we say clearly *no*.

---

## 1. Our example: the `Product` entity

`model/Product.java`

```java
@Entity
@Table(name = "tbl_product")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;          // what the customer pays   → everyone sees

    private BigDecimal costPrice;      // what the shop paid       → secret

    private Integer stock;             // how many in the warehouse

    private String supplierName;       // who we buy from          → secret
}
```

`repository/ProductRepository.java`

```java
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByCreatedBy(String createdBy);
}
```

**Restart the app.** The `PermissionsInitializer` from the main guide sees the new entity and
creates its permission rows automatically — including one `READ` and one `UPDATE` `FIELD` row for
every field (`id`, `name`, `description`, `price`, `costPrice`, `stock`, `supplierName`, and the
audit fields `createdBy`, `createdDate`, …).

Check:

```sql
select id, operation, level, scope, field_name
from tbl_permission
where entity_name = 'Product'
order by level, field_name, operation;
```

---

## 2. Which permission rows do we need?

Let's decide the rules for our shop **before** writing code.

| Field | CUSTOMER | WAREHOUSE | STAFF | ADMIN |
|---|---|---|---|---|
| `id`, `name`, `description`, `price` | 👁 see | 👁 see | 👁 see ✏️ edit | everything |
| `stock` | 👁 see | 👁 see ✏️ edit | 👁 see ✏️ edit | everything |
| `costPrice`, `supplierName` | ❌ hidden | ❌ hidden | 👁 see ✏️ edit | everything |

Turned into permission rows:

| Role | OBJECT rows | Field rows |
|---|---|---|
| **CUSTOMER** | `READ · OBJECT · ALL` | `READ · FIELD` for `id, name, description, price, stock` |
| **WAREHOUSE** | `READ · OBJECT · ALL`, `UPDATE · OBJECT · ALL` | `READ · FIELD` for `id, name, description, price, stock` + `UPDATE · FIELD · stock` |
| **STAFF** | `READ/CREATE/UPDATE/DELETE · OBJECT · ALL` | `READ · TOTAL` + `UPDATE · TOTAL` |
| **ADMIN** | *(none needed — `isAdmin = true`)* | *(none needed)* |

See how `TOTAL` saves work for STAFF: two rows instead of eighteen.

Step 9 shows how to insert these.

---

## 3. Step 1 — Two new queries in `PermissionRepository`

We need to ask the database two questions:

1. "Does any of my roles have **TOTAL** for this entity + operation?" (→ all fields)
2. "If not, **which exact fields** do my roles have?"

Add to `repository/PermissionRepository.java`:

```java
// Question 1: "all fields" shortcut?
@Query("""
    select count(p) > 0
      from Role r join r.permissions p
     where r.id in :roleIds
       and p.level = :level
       and p.operation = :operation
       and p.entityName = :entityName
""")
boolean existsLevelGrant(@Param("roleIds") Collection<Long> roleIds,
                         @Param("level") PermissionLevel level,
                         @Param("operation") Operation operation,
                         @Param("entityName") String entityName);

// Question 2: list of allowed field names
@Query("""
    select distinct p.fieldName
      from Role r join r.permissions p
     where r.id in :roleIds
       and p.level = :level
       and p.operation = :operation
       and p.entityName = :entityName
""")
Set<String> findGrantedFieldNames(@Param("roleIds") Collection<Long> roleIds,
                                  @Param("level") PermissionLevel level,
                                  @Param("operation") Operation operation,
                                  @Param("entityName") String entityName);
```

> **Why not reuse `existsGrant`?** `existsGrant` filters `p.scope in :scopes`. `TOTAL` and
> `FIELD` rows have `scope = NULL`, and in SQL `NULL in (...)` is never true — so it would never
> find them. That's why we need a query without the scope filter.

---

## 4. Step 2 — `FieldAccess`: "which fields may I touch?"

A tiny object that holds the answer. Think of it as a **guest list**: either "everyone is
allowed" (`allFields = true`) or "only these names".

`security/FieldAccess.java`

```java
public record FieldAccess(boolean allFields, Set<String> fields) {

    public static FieldAccess all() {
        return new FieldAccess(true, Set.of());
    }

    public static FieldAccess only(Set<String> fields) {
        return new FieldAccess(false, Set.copyOf(fields));
    }

    public static FieldAccess none() {
        return new FieldAccess(false, Set.of());
    }

    public boolean allows(String field) {
        return allFields || fields.contains(field);
    }
}
```

---

## 5. Step 3 — Teach `PermissionChecker` about fields

Add **one public method** (and a small helper) to `security/PermissionChecker.java`. It reuses
`currentUser()` that is already there.

```java
/**
 * Which fields of this entity may the current user READ or UPDATE?
 * CREATE uses the UPDATE rules. DELETE has no field rules.
 */
public FieldAccess fieldAccess(Class<?> entity, Operation operation) {
    if (operation == Operation.DELETE) {
        throw new IllegalArgumentException("DELETE has no field-level permissions");
    }
    Operation fieldOperation = (operation == Operation.CREATE) ? Operation.UPDATE : operation;

    User user = currentUser();

    // 1. Admin → everything
    if (isAdmin(user)) {
        return FieldAccess.all();
    }
    if (user.getRoles().isEmpty()) {
        return FieldAccess.none();
    }

    List<Long> roleIds = user.getRoles().stream().map(Role::getId).toList();
    String entityName = entity.getSimpleName();

    // 2. TOTAL → every field
    if (permissionRepository.existsLevelGrant(roleIds, PermissionLevel.TOTAL, fieldOperation, entityName)) {
        return FieldAccess.all();
    }

    // 3. Otherwise only the FIELD rows they have
    return FieldAccess.only(permissionRepository.findGrantedFieldNames(
            roleIds, PermissionLevel.FIELD, fieldOperation, entityName));
}

private boolean isAdmin(User user) {
    return user.getRoles().stream().anyMatch(r -> Boolean.TRUE.equals(r.getIsAdmin()));
}
```

You can also replace the admin check in `resolveScope` with `isAdmin(user)` so both methods use
the same rule.

---

## 6. Step 4 — `FieldGuard`: hide fields and block changes

`FieldGuard` does the actual work on DTOs. It has two jobs:

| Method | Used for | What it does |
|---|---|---|
| `mask(dto, access)` | READ | Sets every field you may **not** see to `null`. With `@JsonInclude(NON_NULL)` on the DTO, those fields disappear from the JSON. |
| `checkWritable(request, access)` | CREATE / UPDATE | Looks at every field you **sent** (not `null`). If any of them isn't allowed → 403 with the list of bad fields. |

It works for **any** DTO, as long as the **DTO field names are the same as the entity field
names** (`price` in `Product` ↔ `price` in `ProductResponse`). That's the one rule to remember.

`security/FieldGuard.java`

```java
@Component
public class FieldGuard {

    // Always visible, even without a FIELD permission. Without the id the frontend can't
    // even link to the row.
    private static final Set<String> ALWAYS_VISIBLE = Set.of("id");

    /** READ: remove the fields the user may not see. Returns the same object. */
    public <T> T mask(T dto, FieldAccess access) {
        if (dto == null || access.allFields()) {
            return dto;
        }
        for (Field field : instanceFields(dto.getClass())) {
            String name = field.getName();
            if (ALWAYS_VISIBLE.contains(name) || access.allows(name)) {
                continue;
            }
            if (field.getType().isPrimitive()) {
                // int/long/boolean can't be null → use Integer/Long/Boolean in DTOs
                throw new IllegalStateException(dto.getClass().getSimpleName() + "." + name
                        + " is a primitive; use a wrapper type so it can be hidden");
            }
            write(field, dto, null);
        }
        return dto;
    }

    /** Same as mask, for a list. */
    public <T> List<T> maskAll(List<T> dtos, FieldAccess access) {
        dtos.forEach(dto -> mask(dto, access));
        return dtos;
    }

    /** CREATE/UPDATE: 403 if the request sets a field the user may not change. */
    public void checkWritable(Object request, FieldAccess access) {
        if (access.allFields()) {
            return;
        }
        List<String> forbidden = new ArrayList<>();
        for (Field field : instanceFields(request.getClass())) {
            if (read(field, request) != null && !access.allows(field.getName())) {
                forbidden.add(field.getName());
            }
        }
        if (!forbidden.isEmpty()) {
            throw new AccessDeniedException("You are not allowed to change: " + forbidden);
        }
    }

    // ---- small reflection helpers ----

    private List<Field> instanceFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    result.add(f);
                }
            }
        }
        return result;
    }

    private Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Imports to watch:
- `java.lang.reflect.Field`, `java.lang.reflect.Modifier`
- `org.springframework.security.access.AccessDeniedException` (Spring's — so the global handler
  returns 403)

> **Why reflection?** So you write the rule **once** and it works for Product, User, Category…
> The simpler alternative is to check each field by hand in the mapper:
> ```java
> .costPrice(access.allows("costPrice") ? p.getCostPrice() : null)
> ```
> That's fine for one or two entities. Once you have many, `FieldGuard` saves a lot of typing
> and you can't forget a field.

---

## 7. Step 5 — DTOs for `Product`

### 7.1 Response (what we send back)

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)   // hidden (= null) fields are left out of the JSON
public class ProductResponse {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private BigDecimal costPrice;
    private Integer stock;
    private String supplierName;

    private String createdBy;
    private LocalDateTime createdDate;

    public static ProductResponse from(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .costPrice(p.getCostPrice())
                .stock(p.getStock())
                .supplierName(p.getSupplierName())
                .createdBy(p.getCreatedBy())
                .createdDate(p.getCreatedDate())
                .build();
    }
}
```

### 7.2 Create request

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCreateRequest {
    @NotBlank private String name;
    private String description;
    @NotNull @Positive private BigDecimal price;
    @PositiveOrZero private BigDecimal costPrice;
    @PositiveOrZero private Integer stock;
    private String supplierName;
}
```

### 7.3 Update request — every field optional

"Only send what you want to change" (PATCH style). `null` means "don't touch this field".

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductUpdateRequest {
    private String name;
    private String description;
    @Positive       private BigDecimal price;
    @PositiveOrZero private BigDecimal costPrice;
    @PositiveOrZero private Integer stock;
    private String supplierName;
}
```

**Rules for these DTOs:**
- Field names = entity field names.
- Use wrapper types (`Integer`, `BigDecimal`, `Boolean`), **never** `int` / `boolean`, so they can
  be `null`.
- **Never** put `id`, `createdBy`, `createdDate`… in a request DTO. The server sets those.

---

## 8. Step 6 — Use it in `ProductService`

The pattern is always:

```
READ:    object check → load → map to DTO → mask
CREATE:  object check → checkWritable(request) → save → map → mask
UPDATE:  object check + load + checkRow → checkWritable(request) → change → map → mask
DELETE:  object check + load + checkRow → delete               (no field check)
```

`service/impl/ProductServiceImpl.java`

```java
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final PermissionChecker permissionChecker;
    private final RowAccessGuard rowAccessGuard;
    private final FieldGuard fieldGuard;

    // ---------- READ ----------

    @Override
    public List<ProductResponse> getProducts() {
        PermissionScope scope = rowAccessGuard.requireScope(Product.class, Operation.READ);

        List<Product> products = (scope == PermissionScope.ALL)
                ? productRepository.findAll()
                : productRepository.findByCreatedBy(permissionChecker.currentUserEmail());

        // Ask "which fields?" ONCE for the whole list, not once per product
        FieldAccess canRead = permissionChecker.fieldAccess(Product.class, Operation.READ);

        return fieldGuard.maskAll(products.stream().map(ProductResponse::from).toList(), canRead);
    }

    @Override
    public ProductResponse getProduct(Long id) {
        Product product = rowAccessGuard.checkRow(findOrThrow(id), Product.class, Operation.READ);
        return toResponse(product);
    }

    // ---------- CREATE ----------

    @Override
    @Transactional
    public ProductResponse createProduct(ProductCreateRequest req) {
        rowAccessGuard.requireScope(Product.class, Operation.CREATE);
        fieldGuard.checkWritable(req, permissionChecker.fieldAccess(Product.class, Operation.CREATE));

        Product product = productRepository.save(Product.builder()
                .name(req.getName())
                .description(req.getDescription())
                .price(req.getPrice())
                .costPrice(req.getCostPrice())
                .stock(req.getStock())
                .supplierName(req.getSupplierName())
                .build());

        return toResponse(product);
    }

    // ---------- UPDATE ----------

    @Override
    @Transactional
    public ProductResponse updateProduct(Long id, ProductUpdateRequest req) {
        Product product = rowAccessGuard.checkRow(findOrThrow(id), Product.class, Operation.UPDATE);
        fieldGuard.checkWritable(req, permissionChecker.fieldAccess(Product.class, Operation.UPDATE));

        // Only copy what was sent
        if (req.getName() != null)         product.setName(req.getName());
        if (req.getDescription() != null)  product.setDescription(req.getDescription());
        if (req.getPrice() != null)        product.setPrice(req.getPrice());
        if (req.getCostPrice() != null)    product.setCostPrice(req.getCostPrice());
        if (req.getStock() != null)        product.setStock(req.getStock());
        if (req.getSupplierName() != null) product.setSupplierName(req.getSupplierName());

        return toResponse(product);   // @Transactional saves the changes at the end
    }

    // ---------- DELETE ----------

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        Product product = rowAccessGuard.checkRow(findOrThrow(id), Product.class, Operation.DELETE);
        productRepository.delete(product);
    }

    // ---------- helpers ----------

    // Every response goes through here, so we can't forget to mask
    private ProductResponse toResponse(Product product) {
        return fieldGuard.mask(ProductResponse.from(product),
                permissionChecker.fieldAccess(Product.class, Operation.READ));
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id).orElseThrow(() ->
                new AppException("Product not found", HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    }
}
```

**Two things people forget:**
1. **Mask the response of CREATE and UPDATE too.** The warehouse worker updates `stock`; the
   response must still hide `costPrice`. `toResponse` makes this automatic.
2. **Check writable fields *before* changing anything.** Otherwise half the changes happen and
   then you throw.

---

## 9. Step 7 — `ProductController`

The controller stays thin. `@RequirePermission` does the **object** check, the service does the
**field** check.

```java
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @RequirePermission(entity = Product.class, operation = Operation.READ)
    public ResponseEntity<List<ProductResponse>> getProducts() {
        return ResponseEntity.ok(productService.getProducts());
    }

    @GetMapping("/{id}")
    @RequirePermission(entity = Product.class, operation = Operation.READ)
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @PostMapping
    @RequirePermission(entity = Product.class, operation = Operation.CREATE)
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.createProduct(req));
    }

    @PatchMapping("/{id}")
    @RequirePermission(entity = Product.class, operation = Operation.UPDATE)
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable Long id,
                                                         @Valid @RequestBody ProductUpdateRequest req) {
        return ResponseEntity.ok(productService.updateProduct(id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(entity = Product.class, operation = Operation.DELETE)
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
```

> Add `"PATCH"` to `setAllowedMethods(...)` in `SecurityConfig`'s CORS config, or browsers will
> block the update call.

---

## 10. Step 8 — Tell the frontend which fields it may show

Nice extra: the frontend can ask "what may I see and edit?" and hide columns or disable inputs,
instead of discovering it through errors.

```java
// In ProductController
@GetMapping("/my-fields")
@RequirePermission(entity = Product.class, operation = Operation.READ)
public ResponseEntity<Map<String, FieldAccess>> myFields() {
    return ResponseEntity.ok(Map.of(
            "read",   permissionChecker.fieldAccess(Product.class, Operation.READ),
            "update", permissionChecker.fieldAccess(Product.class, Operation.UPDATE)));
}
```

Example answer for a WAREHOUSE user:

```json
{
  "read":   { "allFields": false, "fields": ["id", "name", "description", "price", "stock"] },
  "update": { "allFields": false, "fields": ["stock"] }
}
```

> This is only for a nicer screen. **The backend still checks everything** — never trust the
> frontend to hide or block things.

---

## 11. Step 9 — Give roles their field permissions

### Option A — with the API (from the main guide)

1. Find the ids: `GET /api/permissions?entity=Product`
2. Assign: `POST /api/permissions` with `{"roleId": 5, "permissionIds": [..]}`

### Option B — with SQL (quick for practice)

Create the roles first (`POST /api/roles` with `CUSTOMER`, `WAREHOUSE`, `STAFF`), then:

```sql
-- CUSTOMER: may read products, and see the public fields only
insert into tbl_role_permission (role_id, permission_id)
select r.id, p.id
from tbl_role r, tbl_permission p
where r.name = 'CUSTOMER'
  and p.entity_name = 'Product'
  and (
        (p.level = 'OBJECT' and p.operation = 'READ' and p.scope = 'ALL')
     or (p.level = 'FIELD'  and p.operation = 'READ'
         and p.field_name in ('id', 'name', 'description', 'price', 'stock'))
  )
on conflict do nothing;

-- WAREHOUSE: same as customer + may update, but ONLY the stock field
insert into tbl_role_permission (role_id, permission_id)
select r.id, p.id
from tbl_role r, tbl_permission p
where r.name = 'WAREHOUSE'
  and p.entity_name = 'Product'
  and (
        (p.level = 'OBJECT' and p.operation in ('READ', 'UPDATE') and p.scope = 'ALL')
     or (p.level = 'FIELD'  and p.operation = 'READ'
         and p.field_name in ('id', 'name', 'description', 'price', 'stock'))
     or (p.level = 'FIELD'  and p.operation = 'UPDATE' and p.field_name = 'stock')
  )
on conflict do nothing;

-- STAFF: everything on products, all fields (TOTAL)
insert into tbl_role_permission (role_id, permission_id)
select r.id, p.id
from tbl_role r, tbl_permission p
where r.name = 'STAFF'
  and p.entity_name = 'Product'
  and (
        (p.level = 'OBJECT' and p.scope = 'ALL')
     or (p.level = 'TOTAL')
  )
on conflict do nothing;
```

> `on conflict do nothing` only works if `tbl_role_permission` has a primary key on
> `(role_id, permission_id)`. Hibernate creates it for a `Set` mapping. If you get an error,
> just remove that line.

Then put test users in those roles: `PUT /api/roles/{roleId}/assign/{userId}`.

---

## 12. Step 10 — Test it

Create one product as admin:

```bash
curl -s -X POST $BASE/api/products -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"Oak Table","description":"Solid oak","price":499.00,"costPrice":210.00,"stock":12,"supplierName":"WoodCo"}'
```

### What each role sees — `GET /api/products/1`

**ADMIN / STAFF**

```json
{ "id": 1, "name": "Oak Table", "description": "Solid oak", "price": 499.00,
  "costPrice": 210.00, "stock": 12, "supplierName": "WoodCo",
  "createdBy": "admin@example.com", "createdDate": "2026-09-25T10:00:00" }
```

**CUSTOMER / WAREHOUSE** — `costPrice`, `supplierName`, `createdBy`, `createdDate` are gone

```json
{ "id": 1, "name": "Oak Table", "description": "Solid oak", "price": 499.00, "stock": 12 }
```

### Updates — `PATCH /api/products/1`

| Who | Body | Result |
|---|---|---|
| WAREHOUSE | `{"stock": 10}` | ✅ `200`, stock is 10, `costPrice` still hidden in response |
| WAREHOUSE | `{"price": 1}` | ❌ `403` "You are not allowed to change: [price]" |
| WAREHOUSE | `{"stock": 10, "price": 1}` | ❌ `403` — and **nothing** is changed, not even stock |
| CUSTOMER | `{"stock": 10}` | ❌ `403` from `@RequirePermission` (no `UPDATE · OBJECT` at all) |
| STAFF | `{"costPrice": 200}` | ✅ `200` |

If every row of this table behaves as written, field-level permissions work.

---

## 13. Common mistakes and gotchas

| Problem | Why | Fix |
|---|---|---|
| Hidden field still shows as `"costPrice": null` | DTO misses `@JsonInclude(NON_NULL)` | Add it on the response class |
| A field is **never** hidden | DTO field name differs from entity (`cost` vs `costPrice`) | Use the same names |
| `IllegalStateException ... is a primitive` | DTO uses `int`/`boolean` | Use `Integer`/`Boolean` |
| TOTAL grant does nothing | Used `existsGrant` (filters on scope) | Use `existsLevelGrant` (Step 1) |
| Customer can still **sort or filter** by `costPrice` (`?sort=costPrice`) and guess values | Masking only hides the output | Only allow sort/filter on fields in `fieldAccess(READ)` |
| New field added, nobody but admin sees it | Seeder creates the permission, but no role has it yet | That's the safe default — grant it to roles when ready |
| Can't tell "hidden" from "really empty" | Both are `null` → both left out | Fine for most apps; if it matters, use `/my-fields` (Step 8) |
| Response of UPDATE leaks hidden fields | Returned `ProductResponse.from(p)` without masking | Always go through `toResponse(...)` |
| Slow list endpoint | Called `fieldAccess` once per row | Call it once, then `maskAll` |

### Rules to keep

1. **Object check first, field check second.** Always both.
2. **READ hides, UPDATE/CREATE refuse.**
3. **Mask every response** — GET, POST, PATCH, all of them.
4. **Same names** in entity and DTOs, **wrapper types** in DTOs.
5. **Admins and `TOTAL` skip the field list.**

---

## 14. Checklist

- [ ] `Product` entity extends `Auditable`; restart → rows in `tbl_permission`
- [ ] `PermissionRepository.existsLevelGrant` + `findGrantedFieldNames`
- [ ] `FieldAccess` record
- [ ] `PermissionChecker.fieldAccess(entity, operation)` (CREATE → UPDATE, admin → all, TOTAL → all)
- [ ] `FieldGuard.mask`, `maskAll`, `checkWritable`
- [ ] `ProductResponse` with `@JsonInclude(NON_NULL)`, wrapper types, same field names
- [ ] `ProductCreateRequest` / `ProductUpdateRequest` (no id or audit fields)
- [ ] Service: `checkWritable` before changes, `toResponse` masks every response
- [ ] Controller: `@RequirePermission` on every method, PATCH allowed in CORS
- [ ] Roles CUSTOMER / WAREHOUSE / STAFF granted (Step 9)
- [ ] Test table in Step 10 passes
