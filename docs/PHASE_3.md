# Phase 3 — Service-to-Service Communication (Step-by-step Guide)

## Context
After Phase 2 you have three independent services, and they don't talk to each other. That caused two real problems you saw:
- order-service saved an order for `VERIFY-DIGITS`, a SKU that exists nowhere
- order-service **trusts the price the client sends**. Anyone could order an iPhone for 0.01

In this phase, order-service **calls the other two services** over HTTP before saving an order:
1. **product-service**: "what is this product's price?" The server decides the price, not the client.
2. **inventory-service**: "reserve N units of this SKU". Stock actually goes down, and two customers can't both buy the last unit.

**At the end of this phase:**
- `POST /api/orders` takes only `skuCode` and `quantity`; the price comes from product-service
- Stock in inventory decreases with every order, safely under concurrent requests
- Clear errors for every outcome: unknown product (`422`), out of stock (`409`), another service down or too slow (`503`)
- Timeouts on every remote call, so a slow service can't freeze order-service
- Service URLs come from configuration. In Phase 5 (Kubernetes), only these properties change, not the code

---

## Concepts first

**Synchronous communication.** order-service sends an HTTP request and **waits** for the answer before continuing. It's simple and gives an immediate answer to the customer. The cost is **temporal coupling**: order-service only works while product-service and inventory-service are both up and fast. If either is down, nobody can order. Phase 8 (resilience) softens this; Phase 9 (Kafka) removes some of it with asynchronous messages.

**HTTP Service Clients (`@HttpExchange`).** Instead of building HTTP requests by hand, you write a Java **interface** describing the remote endpoint:
- Annotations like `@GetExchange("/api/products/sku/{skuCode}")` describe the request, much like `@GetMapping` describes a controller
- Spring generates the implementation at startup, using **`RestClient`** (Spring's modern synchronous HTTP client)
- **Spring Boot 4:** you register the interfaces with `@ImportHttpServices`, and each group gets its base URL and timeouts from properties: `spring.http.serviceclient.<group>.base-url`, `.connect-timeout`, `.read-timeout`
- Older tutorials use `RestTemplate` (legacy) or OpenFeign (a separate Spring Cloud library). HTTP interfaces are now built into Spring itself.

**Timeouts. Every remote call needs one.**
- **Connect timeout:** how long to wait to *open* the connection. Short (≈1–2s): if a service is down, you find out quickly.
- **Read timeout:** how long to wait for the *answer* once connected. Without it, a stuck service makes order-service wait forever. Every waiting request holds a thread and a connection, and soon order-service itself stops responding. This is a **cascading failure**: one slow service takes others down with it.

**Failures are normal, so translate them.** A remote call can fail in many ways, and the client must not see raw internals:

| What happened downstream | Spring throws (in order-service) | order-service should answer |
|---|---|---|
| product-service says `404` | `HttpClientErrorException.NotFound` | `422 Unprocessable Content`: "unknown product" (the request was well-formed, but refers to something that doesn't exist) |
| inventory-service says `409` | `HttpClientErrorException.Conflict` | `409 Conflict`: "out of stock" |
| other service unreachable or too slow | `ResourceAccessException` | `503 Service Unavailable` |
| other service crashes (`5xx`) | `HttpServerErrorException` | `503 Service Unavailable` |

**Atomic stock reservation (the Phase 1 race, again).** "Check stock, then decrease it" in two steps has the same check-then-act race you met with duplicate SKUs: two orders for the last unit both see "1 in stock", and both succeed. The fix is a **single conditional SQL update**:
```sql
UPDATE inventory SET quantity = quantity - :qty WHERE sku_code = :sku AND quantity >= :qty
```
The database checks and decreases **in one atomic step**. It returns the number of rows changed: `1` = reserved, `0` = not enough stock (or unknown SKU). No race is possible.

**Don't trust the client; don't share DTO classes.**
- The client sends *what* it wants (SKU, quantity). The server looks up *how much it costs*.
- order-service defines **its own** small records for product-service's and inventory-service's responses, with only the fields it needs. It doesn't import classes from the other services. Why: each service can evolve independently. Spring's JSON reader ignores fields it doesn't know about, so product-service can add fields without breaking order-service. This is called the **tolerant reader** pattern.

**Transactions stop at the service boundary.** `@Transactional` only covers *your own* database. It can't roll back an HTTP call to another service. So in `placeOrder`, if stock is reserved and then saving the order fails, the stock stays reserved for an order that doesn't exist. You'll handle this properly in Phase 9 (Saga pattern). For now: do the remote calls *first*, save *last*, and write down the gap.

Also: **don't wrap remote calls in `@Transactional`.** A transaction holds a database connection for its whole duration, so wrapping slow network calls in one blocks connections other requests need.

---

## The new flow
```
POST /api/orders {skuCode, quantity}
   │
   ├─1─► product-service    GET  /api/products/sku/{skuCode}       → price      (404 → 422)
   ├─2─► inventory-service  POST /api/inventory/reservations       → reserved   (409 → 409)
   └─3─► save Order(skuCode, price from step 1, quantity) in order_db  → 201
```
Why this order: step 1 changes nothing, so if it fails, nothing needs undoing. Step 2 changes stock, so it runs only once we know the product is valid. Step 3 is last because it's the only step covered by a local transaction.

---

## Steps

### Step 1 — product-service: look up a product by SKU
order-service knows a SKU, not a MongoDB id, so product-service needs a new endpoint:
- `GET /api/products/sku/{skuCode}` → `200` + `ProductResponse`, or `404` ProblemDetail
- Repository: `Optional<Product> findBySkuCode(String skuCode)`. You deleted it as unused in Phase 1; now it has a purpose.
- Service: a `findBySkuCode` method that maps to `ProductResponse`, or throws when absent.
- Your `ProductNotFoundException` message says "with id". Give it a way to say "with SKU" (a second constructor taking a message, or a static factory method like `ProductNotFoundException.forSku(sku)`), so the error message stays accurate.

Test it, including a SKU with a space: `/api/products/sku/P2%20SkuCode`.

### Step 2 — inventory-service: reserve stock atomically
1. **DTO** `ReservationRequest` record: `skuCode` (`@NotBlank @Size(max = 100)`), `quantity` (`@NotNull @Positive`).
2. **Repository:** a method annotated with `@Modifying` and `@Query` running the conditional `UPDATE` from the concepts section, written in **JPQL** (the entity and field names, `Inventory` and `skuCode`, not the table and column names). It returns `int`, the number of updated rows.
   - `@Modifying` tells Spring Data this query changes data, so it runs it as an update and returns the count instead of expecting result rows.
3. **Service:** `reserve(skuCode, quantity)` with **`@Transactional`** (an update needs a writable transaction; your `isInStock` is `readOnly`). If the update returns `0`, throw a new `InsufficientStockException`.
4. **Controller:** `POST /api/inventory/reservations` with `@Valid @RequestBody ReservationRequest`. On success, return `204 No Content`: the reservation worked and there's nothing to send back.
5. **Exception handler:** `InsufficientStockException` → `409 Conflict`, title "Insufficient stock". Also add the `MethodArgumentNotValidException` override (the field-errors one), because this is the first `@Valid` request body in inventory-service.

Test it directly before involving order-service:
- reserve 2 of `P2 SkuCode` → `204`, and `quantity` in the database drops by 2
- reserve 1000 → `409`, and the quantity is unchanged
- reserve an unknown SKU → `409`. Think about whether "unknown SKU" and "not enough stock" should be different errors (a second query could tell them apart). Decide, and note why.

**Note:** this endpoint now *changes* stock. Reset your test data afterwards with an `UPDATE inventory SET quantity = ...` in psql when you need to. Never edit V2.

### Step 3 — order-service: dependencies and configuration
1. Add **`spring-boot-starter-restclient`** to `order-service/pom.xml` (no version; Boot manages it). Reload Maven.
   - Spring Boot 4 split HTTP client support into its own starter. The web starter alone doesn't configure `RestClient`.
2. In `application.properties`, one group per remote service:
   - `spring.http.serviceclient.product.base-url=http://localhost:8081`
   - `spring.http.serviceclient.inventory.base-url=http://localhost:8082`
   - `connect-timeout` and `read-timeout` for both groups, e.g. `2s` and `3s`
   - Why per group: different services can need different limits. A reporting service might legitimately take 10s; a stock check should never take more than a couple of seconds.

### Step 4 — order-service: the client interfaces
**Goal:** give order-service two Java interfaces it can call like normal methods. Spring turns each method call into an HTTP request to the other service. This step only creates and registers them; nothing calls them until Step 6.

You'll create **4 new files** and edit **1 existing file**. All paths are under `order-service/src/main/java/com/shoplite/order_service/`.

| File | Kind | Purpose |
|---|---|---|
| `client/ProductDetails.java` | new record | The part of product-service's JSON response that order-service needs |
| `client/ReservationRequest.java` | new record | The JSON body order-service sends to inventory-service |
| `client/ProductClient.java` | new interface | Calls product-service |
| `client/InventoryClient.java` | new interface | Calls inventory-service |
| `OrderServiceApplication.java` | edit | Registers both interfaces with Spring |

Keep these records in `client/`, not in `dto/`. `dto/` describes **order-service's own API**. `client/` describes **other services' APIs**.

**4.1 — `ProductDetails` record.** Two components: `String skuCode` and `BigDecimal price`.
- Jackson matches JSON fields to record components **by name**, so these names must be exactly `skuCode` and `price`, as product-service sends them.
- product-service also sends `id`, `name` and `description`. Leave them out. They're ignored when the JSON is read.
- No validation annotations. This is data coming *in* from another service, and `@Valid` isn't applied to it. Step 6 checks for a missing price in code.

**4.2 — `ReservationRequest` record.** Two components: `String skuCode` and `Integer quantity`.
- These names must match inventory-service's `ReservationRequest`, because they become the JSON field names.
- Inventory has a record with the same name. That's intentional: each service owns its copy, and neither imports the other's classes.
- No validation annotations here either. `PlaceOrderRequest` has already validated these values, and inventory-service validates them again on its side.

**4.3 — `ProductClient` interface.** One method and no method body:

| Part | Value |
|---|---|
| Annotation on the method | `@GetExchange("/api/products/sku/{skuCode}")` |
| Method signature | `ProductDetails getBySkuCode(@PathVariable String skuCode)` |
| Imports | `org.springframework.web.service.annotation.GetExchange`, `org.springframework.web.bind.annotation.PathVariable` |

- `{skuCode}` in the path is filled in from the `@PathVariable` parameter of the same name. Spring URL-encodes it, so `P2 SkuCode` is sent as `P2%20SkuCode`.
- The return type tells Spring to convert the JSON response into a `ProductDetails`.
- Don't add `@Component` or `@Service`. Step 4.5 registers the interface.

**4.4 — `InventoryClient` interface.** One method and no method body:

| Part | Value |
|---|---|
| Annotation on the method | `@PostExchange("/api/inventory/reservations")` |
| Method signature | `void reserve(@RequestBody ReservationRequest request)` |
| Imports | `org.springframework.web.service.annotation.PostExchange`, `org.springframework.web.bind.annotation.RequestBody` |

- The `ReservationRequest` here is the one from **`client/`**, in the same package, so it needs no import.
- The return type is `void` because inventory-service answers `204 No Content`. A method that returns normally means the reservation succeeded. A `409` makes the call throw an exception, which Step 6 handles.

**4.5 — Register both interfaces.** Add these two annotations to `OrderServiceApplication`, directly above or below `@SpringBootApplication`:
```java
@ImportHttpServices(group = "product", types = ProductClient.class)
@ImportHttpServices(group = "inventory", types = InventoryClient.class)
```
- Import `org.springframework.web.service.registry.ImportHttpServices`, plus your two interfaces.
- **`group` is the link to your configuration.** `group = "product"` uses every `spring.http.serviceclient.product.*` property from Step 3. The spelling must match exactly. A mismatch still compiles, but the client doesn't get that URL or those timeouts.
- Spring generates an implementation of each interface and makes it a bean, so Step 6 can inject `ProductClient` and `InventoryClient` through `OrderService`'s constructor.

**4.6 — Check it.** Reload Maven in IntelliJ if you haven't since Step 3, then start order-service. It should start normally, and it doesn't need product-service or inventory-service to be running, because no HTTP request is sent at startup.

| Error | Likely cause |
|---|---|
| `cannot find symbol: GetExchange` or `ImportHttpServices` | Wrong import, or Maven wasn't reloaded |
| `No qualifying bean of type 'ProductClient'` (once Step 6 injects it) | The `@ImportHttpServices` line is missing or names the wrong type |
| A URI or "not absolute" error on the first call in Step 6 | The `group` name doesn't match the property name, so the client has no base URL |

**Why no host in the interface?** The interfaces contain only paths. The host comes from configuration, so in Phase 5 `http://localhost:8082` becomes `http://inventory-service:8080` without touching the Java code.

### Step 5 — order-service: the server decides the price
- **`PlaceOrderRequest`:** remove `price`. Keep `skuCode` and `quantity` with their validation.
  - This changes your API contract. Any client still sending `price` won't get an error, because the unknown field is ignored, but the value is no longer used. In a real system you'd announce this change to API consumers.
- **The `orders` table doesn't change.** It still stores `price`, now the price *at the time of ordering*, copied from product-service. That's deliberate: if the product's price changes next week, existing orders must keep the price the customer actually paid.

### Step 6 — order-service: `placeOrder` with remote calls
**Goal:** `placeOrder` gets the price from product-service, reserves stock in inventory-service, and only then saves the order. Every remote failure becomes one of your own exceptions, so Step 7 can turn it into a clean HTTP response.

You'll create **3 new files** and edit **1 existing file**. All paths are under `order-service/src/main/java/com/shoplite/order_service/`.

| File | Kind | Purpose |
|---|---|---|
| `exception/UnknownProductException.java` | new | The product doesn't exist or has no price |
| `exception/OutOfStockException.java` | new | inventory-service refused the reservation |
| `exception/ServiceUnavailableException.java` | new | Another service failed, or didn't answer in time |
| `service/OrderService.java` | edit | The new order flow |

**6.1 — Create the three exceptions.** Each one `extends RuntimeException`, like your `OrderNotFoundException`.

| Class | Constructor | Message passed to `super(...)` |
|---|---|---|
| `UnknownProductException` | `(String skuCode)` | `"Product with SKU '<skuCode>' cannot be ordered"` |
| `OutOfStockException` | `(String skuCode, int quantity)` | `"Not enough stock for SKU '<skuCode>' (requested <quantity>)"` |
| `ServiceUnavailableException` | `(Throwable cause)` | `super("A required service is unavailable", cause)` |

`ServiceUnavailableException` passes the original exception as the `cause`. The real reason stays available for debugging, while the message stays safe to show a client.

**6.2 — Add the dependencies to `OrderService`.**
- Add two more `private final` fields, `ProductClient productClient` and `InventoryClient inventoryClient`, and add both as constructor parameters. Spring injects the implementations generated from Step 4.
- Add a logger as a field: `private static final Logger log = LoggerFactory.getLogger(OrderService.class);` (imports `org.slf4j.Logger` and `org.slf4j.LoggerFactory`).

**6.3 — Add a private method `BigDecimal fetchPrice(String skuCode)`.** It calls `productClient.getBySkuCode(skuCode)` inside a `try`, and handles each outcome:

| What happens | What the method does |
|---|---|
| Returns a product with a price | Returns `product.price()` |
| Returns a product with a `null` price (your `P1 SkuCode`) | Throws `UnknownProductException` |
| `catch (HttpClientErrorException.NotFound ex)` | Throws `UnknownProductException` |
| `catch (RestClientException ex)` | Logs a warning, then throws `ServiceUnavailableException(ex)` |

**6.4 — Add a private method `void reserveStock(String skuCode, int quantity)`.** It calls `inventoryClient.reserve(new ReservationRequest(skuCode, quantity))` inside a `try`:

| What happens | What the method does |
|---|---|
| Returns normally | Nothing more to do |
| `catch (HttpClientErrorException.Conflict ex)` | Throws `OutOfStockException` |
| `catch (RestClientException ex)` | Logs a warning, then throws `ServiceUnavailableException(ex)` |

Why these `catch` blocks work:
- **`RestClientException` is the parent** of every failure from these calls: `HttpClientErrorException`, `HttpServerErrorException` and `ResourceAccessException` (connection refused or a timeout). One `catch` covers them all.
- **The specific `catch` must come first.** Java uses the first one that matches. With the parent first, the specific one could never run, and the compiler rejects that code.
- **Import** `org.springframework.web.client.HttpClientErrorException` and `org.springframework.web.client.RestClientException`. `NotFound` and `Conflict` are nested inside `HttpClientErrorException`, so they need no import of their own.
- **Log the exception as the last argument:** `log.warn("Could not reach product-service for SKU {}", skuCode, ex);`. SLF4J fills `{}` with the SKU and prints the stack trace. Use "inventory-service" in the other method, so the log says which service failed.
- **`404` and `409` aren't logged as failures.** They're normal business answers, not problems with the other service.

**6.5 — Rewrite `placeOrder`.** Four statements, in this order:
1. `BigDecimal price = fetchPrice(request.skuCode());`
2. `reserveStock(request.skuCode(), request.quantity());`
3. Create the order with `new Order(request.skuCode(), price, request.quantity())`, and save it with `orderRepository.save(...)`.
4. Return `OrderResponse.from(saved)`.

Then **delete `@Transactional` from `placeOrder` only**:
- Network calls must not run inside a database transaction, because it would hold a connection the whole time. `orderRepository.save(...)` already runs in its own transaction.
- Keep `@Transactional(readOnly = true)` on `findByOrderNumber`.

Don't add a `try`/`catch` to `placeOrder`. Your exceptions pass straight through it to `GlobalExceptionHandler`.

**6.6 — Check it.** Run `./mvnw -q -pl order-service compile`. It should finish with no errors, because `request.price()` is gone. Don't test orders yet: without Step 7, your new exceptions come back as `500`s.

### Step 7 — order-service: map the new exceptions
In `GlobalExceptionHandler`, add one `@ExceptionHandler` method per exception. Each returns a `ProblemDetail`, like `handleOrderNotFound`:
- `UnknownProductException` → **`422`** (`HttpStatus.UNPROCESSABLE_CONTENT`), title "Unknown product", detail from `ex.getMessage()`
- `OutOfStockException` → **`409`**, title "Out of stock", detail from `ex.getMessage()`
- `ServiceUnavailableException` → **`503`**, title "Service unavailable", detail "Could not reach a required service. Please try again later." Never include the internal host name or port in the response.

### Step 8 — Try every path
Start all three services. Check the current stock in psql first, so you can see it change.

| Scenario | How | Expected |
|---|---|---|
| Happy path | order 2 × `P2 SkuCode` | `201`, `price` = product-service's price, inventory quantity −2 |
| Client-sent price ignored | add `"price": 0.01` to the body | `201` with the **real** price |
| Not enough stock | order more than available | `409`, inventory **unchanged**, no order saved |
| Out of stock | order `P5 SkuCode` (quantity 0) | `409` |
| Unknown product | `NOPE` | `422`, inventory unchanged |
| Product without price | `P1 SkuCode` | `422` |
| inventory-service down | stop it, order something | `503` quickly (connect fails immediately) |
| product-service down | stop it, order something | `503` |
| Validation | `quantity: 0`, blank SKU | `400` with field errors (unchanged from Phase 2) |

**Experiment 1 — the read timeout.** Make a "service" that accepts connections but never answers:
```bash
nc -lk 8099
```
Temporarily point `spring.http.serviceclient.inventory.base-url` at `http://localhost:8099`, restart order-service, and place an order. The request hangs for exactly your read timeout, then returns `503`. Then remove the timeout property and try again: it hangs much longer, possibly forever. That's the reason every remote call needs a timeout. Restore the real URL afterwards.

**Experiment 2 — the race is gone.** Set a SKU's quantity to `1` in psql, then send 5 orders for it **at the same time**:
```bash
seq 5 | xargs -P 5 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8083/api/orders -H 'Content-Type: application/json' -d '{"skuCode":"P2 SkuCode","quantity":1}'
```
Expected: exactly **one** `201` and four `409`s, and the quantity ends at `0`, never negative. Then think about what would happen with "check stock, then update" in two steps.

**Experiment 3 — the gap.** Find a way to make the order save fail *after* a successful reservation (e.g. temporarily throw an exception in `placeOrder` right before `save`). Check the inventory: the stock is gone, but there's no order. Write down how you'd fix it. Phase 9 answers it.

### Step 9 — Commit
Commit, `git tag phase-3`, `git push Shoplite main --tags`.

---

## Optional extras
- **Compensation:** add `POST /api/inventory/reservations/release` and call it from order-service when saving the order fails after a successful reservation. A first, simple version of a Saga.
- **Request IDs:** add an `X-Request-Id` header to outgoing calls (`RestClientHttpServiceGroupConfigurer`), and log it in all services. It's a preview of Phase 11's tracing.
- **Tests:** a `@RestClientTest` for the clients, or WireMock to fake product-service and inventory-service in order-service's tests. Then you can test the 404/409/503 paths without starting the other services.

---

## Verification checklist (you run it, then I'll check)
- [ ] `./mvnw clean verify` from the root → BUILD SUCCESS
- [ ] `GET /api/products/sku/{skuCode}` works, including SKUs with spaces; unknown → `404`
- [ ] `POST /api/inventory/reservations` → `204` and stock decreases; not enough → `409` and stock unchanged
- [ ] `PlaceOrderRequest` has no `price`; saved orders always carry product-service's price
- [ ] Every row in the Step 8 table behaves as expected
- [ ] Read-timeout experiment returns `503` after the configured timeout
- [ ] Concurrency experiment: exactly one success, stock never negative
- [ ] No hardcoded URLs in Java code; no other service's internal error details in any response
- [ ] Commit + `phase-3` tag pushed

## Self-check questions (answer in your notes)
1. Why does order-service ask product-service for the price instead of using the client's value?
2. Why can't "check stock, then decrease it" be two separate steps? How does the conditional `UPDATE` solve it?
3. What happens to order-service, step by step, if inventory-service hangs and there's no read timeout?
4. Why does order-service define its own `ProductDetails` record instead of reusing product-service's `ProductResponse` class?
5. Stock was reserved, then saving the order failed. Why can't `@Transactional` fix that? What could?
6. Why `422` for an unknown product, but `503` when product-service is down?

## Next: Phase 4 — Containerize everything (images with buildpacks, JSON logs, health probes)
