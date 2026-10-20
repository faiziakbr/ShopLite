# Phase 1 — First Service: product-service (Step-by-step Guide)

## Context
Phase 0 gave you an empty parent project. Now you'll build the first real microservice: **product-service**, which owns the product catalog. It's a REST API backed by its own MongoDB database.

Everything later phases build on starts here: package layout, DTOs, validation, error handling, tests. The other services in Phase 2 will copy the patterns you set up now, so take your time on this one.

**At the end of this phase:**
- `product-service` is a child module of `shoplite-parent`
- REST endpoints to create, list and fetch products
- MongoDB runs in Docker and starts automatically when you run the app
- Bad input returns clean `400` errors, unknown IDs return `404`, both in the standard ProblemDetail JSON format
- Virtual threads enabled; API browsable at `/swagger-ui`
- Fast controller tests + one full integration test against a real MongoDB (Testcontainers)

**Before you start:** Docker Desktop must be running (both the app and the integration test need it).

---

## Concepts first

**Layered architecture.** Each class has one job:
- **Controller**: HTTP only. Reads the request, calls the service, returns a response. No business logic.
- **Service**: business rules ("SKU codes must be unique"). Knows nothing about HTTP.
- **Repository**: database access. Spring Data writes the implementation for you from an interface.

Why: you can test each layer alone, and swapping something (e.g. MongoDB for PostgreSQL) touches only one layer.

**Entity (document) vs DTO.**
- The **document** (`Product`) is how data is stored in MongoDB.
- **DTOs** (Data Transfer Objects, e.g. `CreateProductRequest`, `ProductResponse`) are what goes over HTTP.

Why keep them separate: your API is a contract with other teams/services. If you return the document directly, any change to how you store data silently breaks your clients. It also stops clients from setting fields they shouldn't, like `id`. Use **Java records** for DTOs: immutable, one line each.

**Why MongoDB for products?** Products vary (a T-shirt has sizes, a laptop has RAM), so a flexible document store fits. Inventory and orders will use PostgreSQL in Phase 2, because they need transactions. This is "database per service": each service picks the database that fits its data, and **no other service touches it**.

**Bean Validation.** Annotations like `@NotBlank` and `@Positive` on the request DTO, plus `@Valid` in the controller, reject bad input before your code runs.

**ProblemDetail (RFC 9457).** The standard JSON format for HTTP errors (`type`, `title`, `status`, `detail`, `instance`). Spring supports it built in. Why: every service returns errors the same way, so clients and the gateway can handle them uniformly.

**Virtual threads.** Normally each request occupies one OS thread (a few hundred at most). Virtual threads are cheap JVM-managed threads, so you can have millions. When a request waits on the database, its virtual thread steps aside and frees the carrier thread. One property turns it on; your code doesn't change.

**Spring Boot Docker Compose support.** When you run the app, Spring Boot finds `compose.yaml`, runs `docker compose up`, and **connects to the database automatically**, with no connection URL in your config.

**Testcontainers.** Starts a real MongoDB in Docker for a test, then throws it away. Why: tests against a fake or in-memory database can pass while the real one fails. With `@ServiceConnection`, Spring wires the test to that container automatically.

**Test slices.**
- `@WebMvcTest` loads **only** the web layer (controller, validation, JSON, error handling). The service is replaced by a mock. Starts in about a second.
- `@SpringBootTest` loads **everything**. Slower, used for a few end-to-end tests.

Many fast slice tests plus a few full tests is the usual balance.

> **Spring Boot 4 heads-up:** a lot of tutorials online are for Boot 2/3. Some names changed in Boot 4:
> - The web starter is now `spring-boot-starter-webmvc` (was `spring-boot-starter-web`)
> - Test support is split into per-technology test starters
> - `@MockBean` is gone; use `@MockitoBean`
> - Testcontainers 2.x renamed its modules (`testcontainers-mongodb`, `testcontainers-junit-jupiter`)
> - Jackson 3 is the JSON library
>
> If an annotation won't import, check you're not following an old tutorial.

---

## The API you'll build

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/products` | `201 Created` + `Location: /api/products/{id}` header + body | `400` invalid input, `409` duplicate SKU |
| `GET` | `/api/products` | `200` list | |
| `GET` | `/api/products/{id}` | `200` product | `404` not found |

Product fields: `id` (generated), `name`, `description`, `skuCode` (unique, e.g. `IPHONE-17-BLK`), `price`.
`skuCode` matters: inventory and orders refer to products by SKU in Phase 2.

*Optional extra once everything works:* `PUT /api/products/{id}` and `DELETE /api/products/{id}` (`204 No Content`).

---

## Steps

### Step 1 — Generate the module with Spring Initializr
Go to [start.spring.io](https://start.spring.io) and choose:
- Project **Maven**, Language **Java**, Spring Boot **4.1.1**, Java **25**
- Group `com.shoplite`, Artifact `product-service`, Package name `com.shoplite.product`
- Dependencies: **Spring Web**, **Spring Data MongoDB**, **Validation**, **Spring Boot Actuator**, **Docker Compose Support**, **Testcontainers**

Download, unzip, and move the folder to `MicroServices_example/product-service/`.
Why Initializr: it picks matching starter and test-starter names for Boot 4, so you don't fight the renames above.

### Step 2 — Turn it into a child module
Initializr made a *standalone* project. Make it a child of your parent:
1. In `product-service/pom.xml`:
   - Change `<parent>` to `com.shoplite:shoplite-parent:0.0.1-SNAPSHOT`, with `<relativePath>../pom.xml</relativePath>`
   - Delete the `<java.version>` property; it's inherited now
   - Remove `<groupId>` and `<version>` if you like; they're inherited too
   - Keep `<artifactId>product-service</artifactId>` and the `spring-boot-maven-plugin`
2. Clean up what Initializr generated:
   - **Delete:** `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `HELP.md`, `target/`, `.idea/`. The root ones cover everything. Open the *root* folder in IntelliJ, not the module
   - **Move to the root:** `.gitattributes` (keeps `mvnw` line endings correct) and `compose.yaml` (your starting point for Step 4)
3. In the **root** `pom.xml`, uncomment `<module>product-service</module>`.
4. From the root, run `./mvnw -q validate`. No output means the structure is right. Then run `./mvnw -q test-compile`, which proves the dependencies resolve with their inherited versions (`validate` doesn't download dependencies).

Why: this is the payoff of Phase 0. Upgrading Spring Boot later means editing one line in the root POM.

### Step 3 — Add springdoc (OpenAPI / Swagger UI)
springdoc isn't on Initializr, and Spring Boot doesn't manage its version. Do it the "multi-module way":
- **Root `pom.xml`:** add a property `springdoc.version` = `3.1.1`, and an entry for `org.springdoc:springdoc-openapi-starter-webmvc-ui` in `<dependencyManagement>` using that property
- **`product-service/pom.xml`:** add the same dependency **without** a version

Why: every future service gets the same springdoc version from one place. Before picking a version, check springdoc's compatibility table for Boot 4.1 (springdoc 3.x is the Boot 4 line).

### Step 4 — Local MongoDB with Docker Compose
1. Edit the **`compose.yaml` you moved to the project root** (next to `pom.xml`):
   - Service `mongodb`: change the image `mongo:latest` → `mongo:8` (pin a major version so it doesn't change on you)
   - Port `'27017'` → `'27017:27017'` (a bare port gets a random host port; fixed lets tools like MongoDB Compass connect)
   - Add a named volume mounted at `/data/db`, so data survives restarts
   - Keep the generated `MONGO_INITDB_*` username/password variables; Spring Boot reads them from the container (local use only)
2. Point product-service at it:
   - In `product-service/src/main/resources/application.properties` (Initializr created it with `spring.application.name=product-service`), add `spring.docker.compose.file=../compose.yaml`
   - `..` means "one folder up" and `/` separates folders, so `../compose.yaml` is the file in the root folder
   - Why: Spring looks for `compose.yaml` in the *working directory*. When you run a module, that's `product-service/`, not the root.
   - In IntelliJ, check the run configuration's working directory is the `product-service` folder.
   - The compose file lives at the root because Phase 2 will add PostgreSQL to the same file.
3. **Don't** set a MongoDB URL anywhere. Spring Boot reads it from the running container.

### Step 5 — Application config (`application.properties`)
Add these to `application.properties`, one `key=value` per line:
- `spring.application.name=product-service` (already there; shows in logs and, later, in traces)
- `server.port=8081` (ports: gateway 8080, product 8081, inventory 8082, order 8083)
- `spring.threads.virtual.enabled=true`
- `spring.data.mongodb.database=product-service`
- `spring.data.mongodb.auto-index-creation=true`
  - Why: without it, `@Indexed(unique = true)` is **silently ignored** and duplicate SKUs get saved
- `management.endpoints.web.exposure.include=health,info`
- `spring.mvc.problemdetails.enabled=true` (ProblemDetail for Spring's own errors, e.g. a malformed JSON body)

Spring also accepts the same settings as `application.yaml`, a nested format that many projects use. Both work identically; just don't keep both files in one service, or you'll wonder which one wins.

### Step 6 — Package layout
Under `com.shoplite.product`, create:
```
controller/   ProductController
service/      ProductService
repository/   ProductRepository
model/        Product                (the MongoDB document)
dto/          CreateProductRequest, ProductResponse
exception/    ProductNotFoundException, DuplicateSkuException, GlobalExceptionHandler
```
Why packages by layer: easy to see the layers while learning. Real projects often group by feature instead. We'll discuss that in Phase 2.

### Step 7 — The document: `Product`
- Annotate with `@Document("products")`
- `id`: a `String` with `@Id`. MongoDB generates an ObjectId when it's `null`
- `skuCode`: `@Indexed(unique = true)`
- `price`: use `BigDecimal`, never `double` for money (`0.1 + 0.2 != 0.3` with doubles)
- A record or a class are both fine. A record is simpler; a class makes sense once you need mutable updates (`PUT`)

If you get a conversion error about `BigDecimal` when saving, that's Spring Data asking how to store decimals in MongoDB. Bring me the error and we'll look at it together; it's a good lesson.

### Step 8 — Repository: `ProductRepository`
An **interface** extending `MongoRepository<Product, String>`. Add `existsBySkuCode(String skuCode)`.
Why it works without code: Spring Data reads the method name and generates the query. Try to guess what query `findByNameContainingIgnoreCase` would produce.

### Step 9 — DTOs
- `CreateProductRequest` (record):
  - `name`: `@NotBlank` + `@Size(max = 100)`
  - `description`: `@Size(max = 1000)`
  - `skuCode`: `@NotBlank`
  - `price`: `@NotNull` + `@Positive`
- `ProductResponse` (record): `id`, `name`, `description`, `skuCode`, `price`
- Add a small mapping method, e.g. a static `ProductResponse.from(Product p)`. No mapping library needed at this size.

### Step 10 — Service: `ProductService`
- Annotate with `@Service`; inject the repository **through the constructor** (no `@Autowired` field injection)
  - Why: dependencies are explicit, fields can be `final`, and tests can just call `new ProductService(mockRepo)`
- `create(request)`:
  - If `existsBySkuCode` → throw `DuplicateSkuException`
  - Otherwise map to `Product`, save, return a `ProductResponse`
- `findAll()`: return all products as responses
- `findById(id)`: return the product, or throw `ProductNotFoundException`

The service returns DTOs, so the controller never sees the document.
*Thought exercise:* `existsBySkuCode` then `save` has a race (two requests at the same moment). The unique index is the real safety net. In Step 12 you'll also catch the database's `DuplicateKeyException`.

### Step 11 — Controller: `ProductController`
- `@RestController` + `@RequestMapping("/api/products")`
- `POST`: takes `@Valid @RequestBody CreateProductRequest`. Returns `ResponseEntity.created(location).body(response)`
  - Build `location` with `ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")`
  - Why `201` + `Location`: that's what REST clients expect when you create something
- `GET` (all) and `GET /{id}`: return the DTOs; Spring turns them into JSON

### Step 12 — Errors: `GlobalExceptionHandler`
- `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`
  - Why extend it: Spring's built-in errors, including validation failures, already come out as ProblemDetail
- `@ExceptionHandler(ProductNotFoundException.class)` → `ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ...)`
- `DuplicateSkuException` **and** Spring's `DuplicateKeyException` → `409 Conflict`
- **Never** leak stack traces or internal messages to the client

### Step 13 — Tests
1. **`ProductControllerTest`** (`@WebMvcTest(ProductController.class)`):
   - Mock the service with `@MockitoBean`
   - Use **`MockMvcTester`**, the modern AssertJ-style MockMvc
   - Cover: valid POST → `201` + `Location` header; blank name / negative price → `400` with ProblemDetail; unknown id → `404`
2. **`ProductServiceTest`** (plain JUnit + Mockito, no Spring at all): duplicate SKU → exception; happy path saves and maps correctly.
3. **`ProductIntegrationTest`** (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`):
   - A `static` `MongoDBContainer` using `mongo:8`, annotated `@Container` + `@ServiceConnection`
   - Test: POST a product, then GET it by the id from the `Location` header. Then POST the same SKU again → `409` (this proves the unique index really exists)
   - Initializr may have generated a `TestcontainersConfiguration` class and a test app class. Read them to see how they do it; you can use that approach or the annotations above
4. Delete the generated `ProductServiceApplicationTests` if it just duplicates your integration test.

Why the duplicate-SKU test must be an integration test: a mock can't tell you whether MongoDB really built the index.

### Step 14 — Try it by hand
- Run the app:
  ```bash
  ./mvnw -pl product-service spring-boot:run
  ```
  Watch the logs: Docker Compose starts MongoDB first.
- Create `product-service/requests.http` (IntelliJ HTTP client / VS Code REST Client) with a request for each endpoint. Or use curl.
- Open `http://localhost:8081/swagger-ui.html` and try the endpoints there
- Check `http://localhost:8081/actuator/health` returns `UP`

### Step 15 — Commit
```bash
./mvnw clean verify
git add .
git commit -m "Phase 1: product-service (REST, MongoDB, validation, tests)"
git tag phase-1
git push Shoplite main --tags
```
Then add a `## Phase 1` section to `docs/LEARNING_NOTES.md`.

---

## Final layout after Phase 1
```
MicroServices_example/
├── compose.yaml
├── pom.xml                       # + <module>product-service</module>, springdoc in dependencyManagement
└── product-service/
    ├── pom.xml
    ├── requests.http
    └── src/
        ├── main/java/com/shoplite/product/
        │   ├── ProductServiceApplication.java
        │   ├── controller/  service/  repository/  model/  dto/  exception/
        ├── main/resources/application.properties
        └── test/java/com/shoplite/product/
            ├── ProductControllerTest.java
            ├── ProductServiceTest.java
            └── ProductIntegrationTest.java
```

## Verification checklist (you run it, then I'll check)
- [ ] `./mvnw clean verify` from the root → BUILD SUCCESS, all tests pass (Docker running)
- [ ] App starts with `./mvnw -pl product-service spring-boot:run` and MongoDB comes up by itself
- [ ] `POST` valid product → `201` with a `Location` header
- [ ] `POST` with blank name → `400` ProblemDetail that names the bad field
- [ ] `POST` same SKU twice → `409`
- [ ] `GET /api/products/does-not-exist` → `404` ProblemDetail
- [ ] Swagger UI loads; `/actuator/health` is `UP`
- [ ] No connection URL anywhere in your config; no `@Autowired` on fields; controller never returns `Product`
- [ ] Commit + `phase-1` tag pushed

## Self-check questions (answer in your notes)
1. Why does the controller return `ProductResponse` instead of `Product`? Name two problems the separation prevents.
2. What would happen to inventory-service if it read product-service's MongoDB collection directly, and you then renamed a field?
3. Why is the duplicate-SKU check in the service not enough on its own?
4. `@WebMvcTest` vs `@SpringBootTest`: what does each load, and when would you choose each?
5. With virtual threads on, what happens to the thread while a request waits for MongoDB?

## Next: Phase 2 — inventory-service and order-service on PostgreSQL with Flyway (database per service)
