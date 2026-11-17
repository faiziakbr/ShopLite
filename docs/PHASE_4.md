# Phase 4 — Containerize Everything (Step-by-step Guide)

## Context
Until now, your services have run from IntelliJ, with only the databases in Docker. In this phase, each service becomes a **Docker image**, and the whole system starts with one command.

Phase 5 needs this, because Kubernetes runs images, not IntelliJ projects. The health endpoints, JSON logs and environment-variable configuration you add here carry straight over.

**At the end of this phase:**
- Each service has a Docker image, built without writing a Dockerfile
- `docker compose --profile apps up -d` starts all 3 services and all 3 databases
- The same image can run anywhere, because hostnames and passwords come from environment variables
- Services report separate **liveness** and **readiness** status
- Containers write JSON logs
- You've scanned the images for known vulnerabilities

**Before you start:**
- Stop all three services in IntelliJ. The containers need ports 8081–8083.
- `order-service` and `inventory-service` tests still use `postgres:latest`. Change line 15 of each `TestcontainersConfiguration.java` to `postgres:18`, to match `compose.yaml`.

---

## Concepts first

**Image vs container.** An **image** is a read-only package holding your app, a Java runtime and a minimal Linux. A **container** is a running instance of an image. One image can run as many containers. It's like a class and its objects.

**Buildpacks instead of a Dockerfile.** `spring-boot:build-image` uses Cloud Native Buildpacks. They detect a Java app and pick a runtime matching your `java.version`. They also tune JVM memory for the container and run as a non-root user. The result follows good practice without you maintaining a Dockerfile.

**Layers.** An image is a stack of layers, and Docker reuses unchanged ones. Spring Boot puts dependencies (which rarely change) in separate layers from your own classes (which change often). So rebuilding after a code change is fast, and pushing it only sends a small layer.

**`localhost` inside a container means that container.** On your Mac, `localhost:8081` reaches product-service. Inside the order-service container, `localhost` is order-service itself. Containers reach each other by **service name**: Docker Compose runs a small DNS server, so `http://product-service:8081` works. Kubernetes works the same way in Phase 5.

**12-factor configuration.** Build **one image** and change its behaviour with **environment variables**. Spring Boot lets environment variables override `application.properties`, with this naming rule:

| Property | Environment variable |
|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` |
| `spring.mongodb.uri` | `SPRING_MONGODB_URI` |
| `spring.http.serviceclient.product.base-url` | `SPRING_HTTP_SERVICECLIENT_PRODUCT_BASEURL` |

Dots become underscores, letters become uppercase, and **dashes are removed**.

**Liveness vs readiness.**
- **Liveness:** "Is this process broken beyond repair?" If not, Kubernetes **restarts** it.
- **Readiness:** "Can it handle requests right now?" If not, Kubernetes **stops sending it traffic** but leaves it running.

The database belongs in **readiness only**. If the database goes down and liveness depended on it, Kubernetes would restart every service in a loop, and restarting can't fix a database.

Don't add product-service or inventory-service to order-service's readiness either. If one of them has a problem, order-service would be marked not ready too, spreading the outage.

**Structured (JSON) logs.** A line like `INFO Started app` is easy for you to read but hard for a program to search. JSON logs have named fields (`log.level`, `service.name`, `message`), so log tools can filter them. Phase 11 relies on this. You'll switch it on **only in containers**, so IntelliJ's console stays readable.

**SBOM and vulnerability scanning.** A **Software Bill of Materials** lists everything inside an image. **Trivy** compares that kind of list against databases of known vulnerabilities. Many companies require both.

---

## Steps

### Step 1 — Fix a deprecated property
**File:** `product-service/src/main/resources/application.properties`

Spring Boot 4 moved MongoDB connection settings. Replace `spring.data.mongodb.database=product-service` with `spring.mongodb.database=product-service`. (My Phase 1 guide gave you the old name.)

Keep `spring.data.mongodb.auto-index-creation=true` as it is. That one belongs to Spring Data and didn't move.

### Step 2 — Include the database in readiness
Add one line to each service's `application.properties`:

| Service | Line to add |
|---|---|
| product-service | `management.endpoint.health.group.readiness.include=readinessState,mongo` |
| inventory-service | `management.endpoint.health.group.readiness.include=readinessState,db` |
| order-service | `management.endpoint.health.group.readiness.include=readinessState,db` |

- `/actuator/health/liveness` and `/actuator/health/readiness` already exist. This line only adds the database check to readiness.
- `db` is Spring's name for the SQL database check, and `mongo` is the name for MongoDB. (Careful: the setting to switch the MongoDB check on or off uses a different name, `management.health.mongodb.enabled`.)
- Spring validates these names at startup, so a typo stops the app with a clear error.

### Step 3 — Build the images
Make sure Docker Desktop is running. From the project root:
```bash
./mvnw -DskipTests spring-boot:build-image
```
The first build downloads the builder and a Java 25 runtime, so allow several minutes. Later builds are much faster. The parent module is skipped automatically, because it has no jar.

Check the result:
```bash
docker images --filter reference='*-service'
```
You should see `product-service`, `inventory-service` and `order-service`, each tagged `0.0.1-SNAPSHOT`.

**Don't be surprised by `Created 46 years ago`.** Buildpacks set a fixed creation date, so the same input always produces an identical image.

### Step 4 — Add health checks to the three databases
**File:** `compose.yaml` (root)

Add a `healthcheck` to each database service, at the same indentation as `ports:`. It lets the apps wait until the database is really accepting connections.

| Service | `test` value |
|---|---|
| `mongodb` | `["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping').ok"]` |
| `inventory-db` | `["CMD-SHELL", "pg_isready -U inventory_user -d inventory_db"]` |
| `order-db` | `["CMD-SHELL", "pg_isready -U order_user -d order_db"]` |

Each one also needs `interval: 5s`, `timeout: 5s` and `retries: 10`:
```yaml
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U inventory_user -d inventory_db"]
      interval: 5s
      timeout: 5s
      retries: 10
```

### Step 5 — Add the `apps` profile to the databases
In the same file, add a second entry to each database's `profiles:` list:

| Service | `profiles` becomes |
|---|---|
| `mongodb` | `product`, `apps` |
| `inventory-db` | `inventory`, `apps` |
| `order-db` | `order`, `apps` |

IntelliJ runs keep working, because each still starts only its own database. `--profile apps` will start all three.

### Step 6 — Add the three application services
Add these under `services:` in `compose.yaml`. Every one has `profiles: [apps]`, so Spring's IntelliJ runs never start them.

| Service | `image` | `ports` | Waits for (healthy) |
|---|---|---|---|
| `product-service` | `product-service:0.0.1-SNAPSHOT` | `8081:8081` | `mongodb` |
| `inventory-service` | `inventory-service:0.0.1-SNAPSHOT` | `8082:8082` | `inventory-db` |
| `order-service` | `order-service:0.0.1-SNAPSHOT` | `8083:8083` | `order-db` |

Environment variables for each:

| Service | Variable | Value |
|---|---|---|
| all three | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` |
| product-service | `SPRING_MONGODB_URI` | `mongodb://root:secret@mongodb:27017/product-service?authSource=admin` |
| inventory-service | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://inventory-db:5432/inventory_db` |
| inventory-service | `SPRING_DATASOURCE_USERNAME` | `inventory_user` |
| inventory-service | `SPRING_DATASOURCE_PASSWORD` | `secret` |
| order-service | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://order-db:5432/order_db` |
| order-service | `SPRING_DATASOURCE_USERNAME` | `order_user` |
| order-service | `SPRING_DATASOURCE_PASSWORD` | `secret` |
| order-service | `SPRING_HTTP_SERVICECLIENT_PRODUCT_BASEURL` | `http://product-service:8081` |
| order-service | `SPRING_HTTP_SERVICECLIENT_INVENTORY_BASEURL` | `http://inventory-service:8082` |

Here's how one looks:
```yaml
  product-service:
    image: 'product-service:0.0.1-SNAPSHOT'
    profiles:
      - apps
    ports:
      - '8081:8081'
    environment:
      - 'LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs'
      - 'SPRING_MONGODB_URI=mongodb://root:secret@mongodb:27017/product-service?authSource=admin'
    depends_on:
      mongodb:
        condition: service_healthy
```

Notes:
- **Hostnames are compose service names** (`mongodb`, `inventory-db`), and the port is the one *inside* the container. So `order-db` uses `5432`, not your Mac's `5433`.
- **Passwords in this file are for local use only.** Phase 6 moves them into Kubernetes Secrets.
- **Apps have no `healthcheck`.** These minimal images contain no shell or `curl` to run one. Kubernetes doesn't need them, because it calls the health URLs from outside the container.

Validate the file before running it:
```bash
docker compose --profile apps config --services
```
It should list all six services.

### Step 7 — Start everything
```bash
docker compose --profile apps up -d
```
Then check that all six are running:
```bash
docker compose --profile apps ps
```
Check the health of the three apps:
```bash
curl localhost:8081/actuator/health/readiness
```
Repeat with ports 8082 and 8083. Each should return `{"status":"UP"}`.

### Step 8 — Place an order through the containers
The containers use the same named volumes as your IntelliJ runs, so your existing products and stock are still there.
```bash
curl -i -X POST localhost:8083/api/orders -H 'Content-Type: application/json' -d '{"skuCode":"P2 SkuCode","quantity":1}'
```
Expect `201`, with the real price. Then all three services and databases have worked together inside Docker.

### Step 9 — Read the JSON logs
```bash
docker compose --profile apps logs --no-log-prefix order-service
```
Most lines are JSON now. To print only the fields you care about, use `jq` (`brew install jq` if you don't have it):
```bash
docker compose --profile apps logs --no-log-prefix order-service | jq -R 'fromjson? | {level: .log.level, message}'
```
`fromjson?` skips the few lines at startup that aren't JSON.

### Step 10 — Scan the images
```bash
brew install trivy
```
```bash
trivy image --severity HIGH,CRITICAL order-service:0.0.1-SNAPSHOT
```
Repeat for the other two images. The first run downloads the vulnerability database.

To generate an SBOM (the `target/` folder is already ignored by git):
```bash
trivy image --format cyclonedx --output order-service/target/sbom.json order-service:0.0.1-SNAPSHOT
```

**If Trivy reports a CRITICAL issue:**
1. Check whether it has a **fixed version**.
2. Most fixes arrive through a newer Spring Boot patch version. Update it in the root `pom.xml`, then rebuild.
3. If there's no fix yet, write down the package and CVE in your notes. Real teams record and track these too.

### Step 11 — Experiments
**Experiment 1 — Liveness vs readiness.** Stop one database:
```bash
docker compose --profile apps stop inventory-db
```
```bash
curl -i localhost:8082/actuator/health/liveness
```
```bash
curl -i localhost:8082/actuator/health/readiness
```
Expect liveness `200` (restarting wouldn't help) and readiness `503` (don't send traffic). Now start it again:
```bash
docker compose --profile apps start inventory-db
```
Readiness returns to `200` within a few seconds, without restarting the app.

**Experiment 2 — The `localhost` mistake.** In `compose.yaml`, temporarily change the product URL to `http://localhost:8081`, then apply it:
```bash
docker compose --profile apps up -d order-service
```
Place an order. You'll get `503`, even though port 8081 works from your Mac. Find the `Could not reach product-service` warning in the logs. Change the URL back and run the `up` command again.

**Experiment 3 — Layer reuse.** Change one log message in any service, then rebuild only that service:
```bash
./mvnw -DskipTests -pl order-service spring-boot:build-image
```
Note how many layers say `Reusing layer`, and how much faster it is. Undo the change afterwards.

### Step 12 — Stop everything and commit
```bash
docker compose --profile apps down
```
This removes the containers and the network, but keeps your data in the volumes. To go back to IntelliJ, just run the services as before.

Finish with `./mvnw clean verify`, then commit, `git tag phase-4` and `git push Shoplite main --tags`.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `port is already allocated` | A service is still running in IntelliJ |
| `pull access denied for product-service` | The image wasn't built (Step 3), or the name or tag in `compose.yaml` differs |
| App exits at startup with `Connection refused` to `localhost` | An environment variable is missing or misspelled, so the app fell back to its default |
| `network … not found` | A stale container (Phase 1 lesson). Run `docker compose --profile apps down`, then `up -d` |
| Container exits with code 137 | Out of memory. Give Docker Desktop more memory (6 GB or more for this stack) |
| Code change has no effect | You didn't rebuild the image. Run Step 3, then `docker compose --profile apps up -d` |

---

## Verification checklist (you run it, then I'll check)
- [ ] `spring.mongodb.database` replaces the deprecated property
- [ ] Three images exist, and no Dockerfile was written
- [ ] `docker compose --profile apps up -d` starts all 6 containers
- [ ] All three services report readiness `UP`
- [ ] An order placed through the containers returns `201` with the real price, and stock goes down
- [ ] Logs from containers are JSON; IntelliJ runs still show normal text
- [ ] Trivy has run on all three images, and any CRITICAL issue is fixed or noted
- [ ] Stopping a database gives liveness `200` and readiness `503`
- [ ] `order-service` config has no hardcoded container hostnames in `application.properties`
- [ ] Commit + `phase-4` tag pushed

## Self-check questions (answer in your notes)
1. Why does `http://localhost:8081` work from your Mac but not from inside the order-service container?
2. Why build one image and configure it with environment variables, instead of building a separate image per environment?
3. Why must the database check be in readiness but not in liveness?
4. Why shouldn't order-service's readiness depend on product-service being up?
5. What makes rebuilding an image after a small code change so fast?
6. Inside the Compose network, why does order-db use port 5432 when your Mac uses 5433?

## Next: Phase 5 — Kubernetes basics and service discovery (Deployments, Services, DNS, probes, Tilt)
