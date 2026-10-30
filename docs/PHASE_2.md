# Phase 2 — inventory-service & order-service on PostgreSQL (Step-by-step Guide)

## Context
In Phase 1 you built product-service on MongoDB. Now you'll add two more services, each with **its own PostgreSQL database**:

- **inventory-service**: knows how many units of each SKU are in stock. Answers "is `IPHONE-17-BLK` in stock for quantity 2?"
- **order-service**: accepts and stores orders.

In this phase the two services **don't talk to each other yet**. order-service saves every order without checking stock. Phase 3 connects them. Keeping them separate now lets you focus on the new ideas: a relational database, JPA, schema migrations with Flyway, and **database per service**.

**At the end of this phase:**
- Two new child modules: `inventory-service` (port 8082) and `order-service` (port 8083)
- Each has its own PostgreSQL container, and its schema is created by **Flyway** migrations, not by Hibernate
- inventory-service starts with seed data for the SKUs you created in product-service
- `GET /api/inventory?skuCode=...&quantity=...` → whether that quantity is in stock
- `POST /api/orders` → `201`; `GET /api/orders/{orderNumber}` → the order
- Same error handling style as product-service (ProblemDetail, field errors, 404, 409)

You already know the controller → service → repository pattern and DTOs from Phase 1. This guide spends its words on what's **new**.

---

## Concepts first

**Database per service (the main lesson of this phase).** Each service owns its data, and **no other service reads or writes its tables**. Other services must go through its API.
- Why: if order-service read inventory's tables directly, inventory could never rename a column or change its schema without breaking order-service. The two would have to be deployed together, which defeats the point of microservices.
- The cost: no SQL `JOIN` across services and no single transaction across services. You'll feel this in Phase 3 (network calls) and Phase 9 (eventual consistency with Kafka).
- Each service also picks the database that fits it: MongoDB for the flexible product catalog; PostgreSQL for inventory and orders, because stock counts and orders need **transactions** and strict consistency.

**JPA and Hibernate.**
- **JPA** (Jakarta Persistence) is the standard Java API for mapping classes to relational tables. **Hibernate** is the implementation Spring Boot uses.
- **Spring Data JPA** sits on top, giving you the same repository interfaces you used with MongoDB (`JpaRepository` instead of `MongoRepository`). The method-name queries like `existsBySkuCode` work the same way.
- **Big difference from Phase 1:** a JPA entity **can't be a record**. Hibernate needs a no-argument constructor and must be able to set fields after creating the object, and records are immutable. Entities are regular classes. Your DTOs stay records.

**Flyway (schema migrations).** Your database schema is code, so it should be versioned like code.
- You write numbered SQL files: `V1__create_inventory_table.sql`, `V2__seed_inventory.sql`, ...
- On startup, Flyway checks its history table (`flyway_schema_history`) and runs only the files it hasn't run yet, in order.
- **Rule: never edit a migration that has already run.** Flyway stores a checksum of each file and refuses to start if one changed. To change the schema, add a *new* file (`V3__add_column...`).
- Why not let Hibernate create tables (`ddl-auto=update`)? It can't rename columns, drop things safely or migrate data, and you can't review what it will do to production. Real teams use migrations. Here Hibernate only **validates** that your entities match the tables Flyway created (`ddl-auto=validate`).

**Transactions (`@Transactional`).** A transaction groups database operations so they either **all** happen or **none** do. You'll use it on service methods that write. It matters a lot in Phase 3, when placing an order has to reduce stock reliably.

**Package by feature vs by layer.** In Phase 1 you grouped classes by layer (`controller/`, `service/`, ...). Another common style groups by feature (`inventory/` containing its controller, service, repository, entity). Feature packages scale better in big services, because everything about one feature sits together. Each of these services has only one feature, so **by layer is fine**. Keep the Phase 1 style for consistency.

---

## The APIs you'll build

**inventory-service** (port 8082)

| Method | Path | Success | Errors |
|---|---|---|---|
| `GET` | `/api/inventory?skuCode=IPHONE-17-BLK&quantity=2` | `200` `{"skuCode": "IPHONE-17-BLK", "inStock": true}` | `400` missing/invalid params |

- Unknown SKU → `inStock: false` (nothing to sell), not 404. Discuss in your notes whether you agree.
- Inventory table: `id`, `sku_code` (unique), `quantity`.

**order-service** (port 8083)

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/orders` | `201` + `Location` + body | `400` invalid input |
| `GET` | `/api/orders/{orderNumber}` | `200` | `404` not found |

- Request: `skuCode`, `price`, `quantity`. Keep it to **one product per order** for now (see the optional extras at the end).
- Stored order: `id` (database key, internal), `order_number` (a UUID, the public id), `sku_code`, `price`, `quantity`, `status` (`PLACED` for now), `created_at`.
- **Why two ids:** the auto-increment `id` (1, 2, 3...) is efficient for the database, but exposing it tells anyone how many orders you have and lets them guess other customers' order URLs. The random `orderNumber` is what the API uses.

---

## Steps

### Step 1 — Generate both modules on start.spring.io
Same settings as Phase 1 (Maven, Java, Boot 4.1.1, Java 25, Group `com.shoplite`).

| | inventory-service | order-service |
|---|---|---|
| Artifact | `inventory-service` | `order-service` |
| Package | `com.shoplite.inventory` | `com.shoplite.order` |
| Dependencies | Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration, Validation, Spring Boot Actuator, Docker Compose Support, Testcontainers | same |

Set the package name yourself. Initializr would otherwise generate `com.shoplite.inventory_service`, like it did in Phase 1. Either works, but pick deliberately.

Check the generated `pom.xml`: with Flyway + PostgreSQL, Initializr should add `spring-boot-starter-flyway` **and** `flyway-database-postgresql`. Since Flyway 10, database support is a separate module. Without it, startup fails with "Unsupported Database: PostgreSQL".

### Step 2 — Make them child modules
Exactly as in Phase 1, Step 2, for each module:
1. In the module `pom.xml`:
   - Point `<parent>` at `shoplite-parent` with `<relativePath>../pom.xml</relativePath>`
   - Delete `<java.version>`, `<groupId>` and `<version>`
   - Set `<name>`
2. Delete the module's `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `HELP.md`, `compose.yaml`, `static/`, `templates/`.
3. Add the springdoc dependency (no version; the root manages it).
4. Add both to `<modules>` in the root `pom.xml`.
5. **Reload Maven in IntelliJ** (you learned why in Phase 1), then run `./mvnw -q test-compile` from the root.

### Step 3 — One compose file, three databases, using **Compose profiles**
Add two PostgreSQL services to the root `compose.yaml`, next to `mongodb`:
- `inventory-db`: image `postgres:18`, host port `5432:5432`
- `order-db`: image `postgres:18`, host port `5433:5432`
  - Inside the container Postgres always listens on 5432. Only the host side differs, so the two don't clash on your Mac.
- Each needs `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` environment variables (e.g. `inventory` / `inventory` / `secret`) and its own named volume. Postgres 18 stores data at `/var/lib/postgresql`. Check the image's docs on Docker Hub for the exact mount path; it changed in version 18.
- Declare the new volumes in the top-level `volumes:` mapping (remember the Phase 1 mistake).

**The problem you must solve:** Spring Boot's Docker Compose support starts **every** service in the file and creates a connection for each. If inventory-service started both Postgres containers, Spring would find two PostgreSQL databases and fail, because it can't know which one is "its" database.

**The solution: Compose profiles.** A profile is a label on a compose service; profiled services only start when that profile is requested.
1. Give each database service a `profiles:` list with one entry: `mongodb` → `product`, `inventory-db` → `inventory`, `order-db` → `order`.
2. In each service's `application.properties`, set `spring.docker.compose.profiles.active` to its profile (`product`, `inventory`, `order`).
   - **Don't forget product-service.** Once `mongodb` has a profile, product-service won't start it without this property.
3. Each service also needs `spring.docker.compose.file=../compose.yaml`, and in IntelliJ, a working directory of its module folder (Phase 1 lesson).

Check the file with `docker compose config`. To start one database by hand: `docker compose --profile inventory up -d`.

This is database per service enforced by the setup: each service can only even *see* its own database.

### Step 4 — `application.properties` for each service
For both (adjust names and ports):
- `spring.application.name`, `server.port` (8082 / 8083)
- `spring.threads.virtual.enabled=true`
- `spring.docker.compose.file=../compose.yaml` and `spring.docker.compose.profiles.active=...`
- `spring.jpa.hibernate.ddl-auto=validate`: Hibernate checks your entities match the tables, but never changes the schema. Flyway owns the schema.
- `spring.jpa.open-in-view=false`
  - Why: by default Spring keeps the database session open until the HTTP response is written. That hides lazy-loading mistakes and holds database connections longer than needed. Spring even logs a warning about it at startup. Turning it off is standard practice.
- `management.endpoints.web.exposure.include=health,info` and `spring.mvc.problemdetails.enabled=true`

Again: **no database URL, username or password** in the properties. Docker Compose support provides them.

### Step 5 — Flyway migrations (inventory-service)
Location: `inventory-service/src/main/resources/db/migration/` (Flyway's default).

1. `V1__create_inventory_table.sql`: a table `inventory` with:
   - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` (the modern SQL standard way to auto-number; older tutorials use `SERIAL`)
   - `sku_code VARCHAR(100) NOT NULL UNIQUE`
   - `quantity INTEGER NOT NULL CHECK (quantity >= 0)`
2. `V2__seed_inventory.sql`: `INSERT` rows for the SKUs you created in product-service (check Compass for their exact SKU codes), with different quantities including one with `0`.

Note the naming: capital `V`, version number, **two** underscores, description. A single underscore is a classic mistake; Flyway then ignores or rejects the file.

**Why a `CHECK` and a `UNIQUE` in SQL** when Java also validates? The Phase 1 lesson: the database is the final guarantee. Application checks can race; constraints can't.

### Step 6 — Flyway migration (order-service)
`order-service/src/main/resources/db/migration/V1__create_orders_table.sql`: a table **`orders`** with:
- `id` (identity primary key, as above)
- `order_number UUID NOT NULL UNIQUE` (PostgreSQL has a native UUID type)
- `sku_code VARCHAR(100) NOT NULL`
- `price NUMERIC(12, 2) NOT NULL`: `NUMERIC` is exact; never `REAL`/`FLOAT` for money (same reason as `BigDecimal`)
- `quantity INTEGER NOT NULL CHECK (quantity > 0)`
- `status VARCHAR(20) NOT NULL`
- `created_at TIMESTAMPTZ NOT NULL`: always store timestamps with time zone

**Why `orders` and not `order`?** `ORDER` is a reserved SQL word (`ORDER BY`). A table named `order` causes confusing syntax errors.

### Step 7 — inventory-service code
- **Entity `Inventory`** (`model/`): a class with `@Entity` and `@Table(name = "inventory")`.
  - `@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)` on `Long id`. IDENTITY matches the `GENERATED ALWAYS AS IDENTITY` column: the database assigns the number.
  - Fields `skuCode` and `quantity`. Hibernate maps camelCase to snake_case automatically (`skuCode` → `sku_code`).
  - A `protected` no-argument constructor (for Hibernate), plus getters. Add setters only where you need them.
- **Repository** `InventoryRepository extends JpaRepository<Inventory, Long>` with a method that finds by SKU. Think about whether `findBySkuCode` should return `Optional<Inventory>` or whether you want a query that answers the question directly (e.g. `existsBySkuCodeAndQuantityGreaterThanEqual(...)`). Both work; the second lets the database do the comparison.
- **Service** `InventoryService.isInStock(skuCode, quantity)`: read-only, so mark it `@Transactional(readOnly = true)`. It lets Hibernate skip change-tracking work.
- **DTO** `InventoryResponse` record: `skuCode`, `inStock`.
- **Controller** `GET /api/inventory` with two `@RequestParam`s.
  - Validate them: `@NotBlank` on `skuCode`, `@Positive` on `quantity`. For validation on request **parameters** (not a body), method validation applies. In Spring 6.1+ it's built in, and a failure raises `HandlerMethodValidationException`, which `ResponseEntityExceptionHandler` already turns into a `400`.
  - Try `quantity=abc` and `quantity=-1` and see what happens before and after.
- **Exception handler**: copy the pattern from product-service, including the field-errors override. (Copying is normal in microservices. Each service is independent. Shared libraries come later and cost coupling; discuss in your notes.)

### Step 8 — order-service code
- **Entity `Order`** (`model/`): `@Entity`, `@Table(name = "orders")`.
  - `Long id` (IDENTITY), `UUID orderNumber`, `String skuCode`, `BigDecimal price`, `Integer quantity`, `OrderStatus status`, `Instant createdAt`
  - **`OrderStatus` enum** (`PLACED` for now; later `CONFIRMED`, `CANCELLED`) mapped with `@Enumerated(EnumType.STRING)`. Without it, JPA stores the enum's *position* (0, 1, 2), and reordering the enum silently corrupts every existing row.
  - Set `orderNumber` with `UUID.randomUUID()`, `status` and `createdAt` in the constructor (or a `@PrePersist` method), so an order can never be created without them.
- **Repository** `OrderRepository extends JpaRepository<Order, Long>` with `findByOrderNumber(UUID orderNumber)` returning `Optional<Order>`.
- **DTOs**:
  - `PlaceOrderRequest`: `skuCode` (`@NotBlank`), `price` (`@NotNull @Positive`), `quantity` (`@NotNull @Positive`)
  - `OrderResponse`: `orderNumber`, `skuCode`, `price`, `quantity`, `status`, `createdAt`. **Not** the internal `id`.
- **Service** `OrderService.placeOrder(request)` with `@Transactional`; `findByOrderNumber` with `@Transactional(readOnly = true)` → throw `OrderNotFoundException` if absent.
- **Controller**:
  - `POST /api/orders` → `201` + `Location` (built from `orderNumber`, not `id`)
  - `GET /api/orders/{orderNumber}`: take the path variable as `UUID`. Try a non-UUID like `/api/orders/abc` and see what status you get. Handle it so it returns `400`, not `500`.
- **Exception handler**: 404 for `OrderNotFoundException`, plus the field-errors override.

**Question to think about** (answer in your notes): the client sends `price`. Should order-service trust it? (Hint: no. Phase 3 will discuss who should decide the price.)

### Step 9 — Run and try it
1. Start each service (IntelliJ with the module working directory, or `./mvnw -pl inventory-service spring-boot:run`).
2. In the logs, look for:
   - the right compose service starting (only *its* database)
   - Flyway: `Successfully applied 2 migrations to schema "public"`
   - no Hibernate validation errors
3. Look at the data: connect to Postgres with IntelliJ's Database tool, or from the terminal:
   ```bash
   docker compose exec inventory-db psql -U inventory -d inventory
   ```
   Then `\dt` lists tables, including `flyway_schema_history`. `SELECT * FROM flyway_schema_history;` shows what Flyway ran.
4. Hit every endpoint, including the error cases. Create `requests.http` files if you like.
5. **Experiment:** edit `V1__create_inventory_table.sql` after it has run (add a space), restart, and read Flyway's error. Then undo the edit. This is the checksum protection in action.
6. Make sure **product-service still starts** with its new compose profile.

### Step 10 — Commit
Commit, `git tag phase-2`, push with `--tags`. (Phase 1's tag is still missing: `git tag phase-1 a8e3b7f`, then push tags.)

---

## Optional extras
- **Tests:** a `@DataJpaTest` repository test against a real Postgres (Testcontainers `postgres:18` with `@ServiceConnection`) runs your Flyway migrations and checks the queries work. It's the most valuable test in this phase.
- **Multiple items per order:** an `order_line_items` table and a `@OneToMany` relationship. Real orders need this, but it adds JPA relationship complexity. Worth doing after Phase 3 works.
- **Inventory admin endpoints:** `POST /api/inventory` to add stock.

---

## Final layout after Phase 2
```
MicroServices_example/
├── compose.yaml                  # mongodb (product), inventory-db (inventory), order-db (order)
├── pom.xml                       # 3 modules
├── product-service/              # + spring.docker.compose.profiles.active=product
├── inventory-service/
│   └── src/main/
│       ├── java/com/shoplite/inventory/   controller/ service/ repository/ model/ dto/ exception/
│       └── resources/
│           ├── application.properties
│           └── db/migration/   V1__create_inventory_table.sql, V2__seed_inventory.sql
└── order-service/
    └── src/main/
        ├── java/com/shoplite/order/       controller/ service/ repository/ model/ dto/ exception/
        └── resources/
            ├── application.properties
            └── db/migration/   V1__create_orders_table.sql
```

## Verification checklist (you run it, then I'll check)
- [ ] `./mvnw clean verify` from the root → BUILD SUCCESS for all 3 modules
- [ ] Each service starts **only its own** database container
- [ ] Flyway logs show the migrations applied; `flyway_schema_history` exists in both Postgres databases
- [ ] `GET /api/inventory?skuCode=<seeded>&quantity=1` → `inStock: true`; quantity above stock → `false`; unknown SKU → `false`
- [ ] Missing or negative `quantity` → `400`
- [ ] `POST /api/orders` valid → `201` + `Location` with the order number; invalid → `400` naming the fields
- [ ] `GET /api/orders/{orderNumber}` → `200`; unknown UUID → `404`; `abc` → `400`
- [ ] Responses never contain the internal `id`; `status` is stored as text in the database
- [ ] No database URL or password in any `application.properties`; `ddl-auto=validate`
- [ ] Commit + `phase-2` tag pushed

## Self-check questions (answer in your notes)
1. order-service needs to know if an item is in stock. Why can't it just query the `inventory` table?
2. Why does Flyway refuse to start when an already-applied migration file changed? What would go wrong without that check?
3. Why `ddl-auto=validate` instead of `update`?
4. Why can't a JPA entity be a Java record, when a MongoDB document can?
5. What does `@Enumerated(EnumType.STRING)` protect you from?
6. Why does the API expose `orderNumber` (UUID) instead of the database `id`?

## Next: Phase 3 — order-service calls inventory-service (RestClient, timeouts, failure handling)
