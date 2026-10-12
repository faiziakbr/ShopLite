# Learning Plan: Spring Boot Microservices on Kubernetes — "ShopLite" E-Commerce System

## Context
Goal: learn Java Spring Boot microservices by building one realistic project, step by step, where each phase introduces exactly one or two new concepts. Services run on **Kubernetes**. Kubernetes provides service discovery, configuration, load balancing and health checks itself, so we don't build Eureka or Config Server.

Environment: Java (25 LTS target), Docker Desktop 29 (includes `kubectl` and a built-in Kubernetes cluster), Maven via the wrapper (`./mvnw`). Phase 0 setup is in progress, see `docs/PHASE_0.md`.

Why an e-commerce order system? It's the classic microservices domain. The service boundaries are clear (products, orders, inventory, notifications). It has a natural synchronous call (order → inventory check) and a natural async event (order placed → send notification), so every core pattern has an obvious place to live.

### Why Kubernetes instead of Eureka / Config Server
Eureka isn't deprecated; Spring Cloud Netflix Eureka is still maintained. But once you run on Kubernetes, the platform already does those jobs, and most teams today run on Kubernetes:

| Concern | Classic Spring Cloud | Kubernetes-native (what we'll use) |
|---|---|---|
| Service discovery | Eureka server + client | K8s `Service` + cluster DNS (`http://inventory-service`) |
| Load balancing | Spring Cloud LoadBalancer (client-side) | `Service` spreads traffic across Pod replicas |
| Central config | Config Server + git repo | `ConfigMap` + `Secret` mounted as env vars/files |
| Health / self-healing | Eureka heartbeats | Liveness/readiness probes on Actuator endpoints |
| Edge routing | Spring Cloud Gateway | Spring Cloud Gateway (kept, for learning filters), exposed via the K8s **Gateway API** (Envoy Gateway) |

Upside: less code in your services, and this is what you'll see at work. Downside: Kubernetes is a big topic, so we containerize first and add it gradually.

## What You'll Build
```
                         ┌──────── Kubernetes cluster (namespace: shoplite) ────────┐
                         │                                                          │
  Client (curl/Postman) ─┼─► Gateway ─► ┌──────────────┐                            │
                         │   (K8s API)  │              │                            │
                         │              │ api-gateway  │  (Spring Cloud Gateway)    │
                         │              └──────┬───────┘                            │
                         │     ┌───────────────┼──────────────────┐                 │
                         │     ▼               ▼                  ▼                 │
                         │ ┌────────────┐ ┌────────────┐   ┌───────────────┐        │
                         │ │product-svc │ │ order-svc  │──►│ inventory-svc │ x2 Pods│
                         │ │ MongoDB    │ │ PostgreSQL │   │ PostgreSQL    │        │
                         │ └────────────┘ └─────┬──────┘   └───────────────┘        │
                         │   calls by DNS name, │ "OrderPlaced" event               │
                         │   via K8s Services   ▼                                   │
                         │                ┌─────────┐      ┌──────────────────┐     │
                         │                │  Kafka  │ ───► │ notification-svc │     │
                         │                └─────────┘      └──────────────────┘     │
                         │                                                          │
                         │  ConfigMaps/Secrets · Keycloak · OpenTelemetry Collector │
                         │  → Prometheus · Loki · Tempo · Grafana                   │
                         └──────────────────────────────────────────────────────────┘
```

## Tech Stack
- Spring Boot 4.1.x, Spring Cloud 2025.1.x (still used for Gateway + Resilience4j circuit breaker)
- Java 25 (LTS) with **virtual threads** enabled (`spring.threads.virtual.enabled=true`)
- Maven multi-module project + Maven Wrapper
- Spring Data MongoDB / JPA, Flyway
- Testing: JUnit 5, Testcontainers with `@ServiceConnection`; Spring Boot Docker Compose support for local dev
- API docs: OpenAPI via springdoc (`/swagger-ui`)
- Docker images via Spring Boot buildpacks (`./mvnw spring-boot:build-image`), no Dockerfile needed; SBOM included; scanned with **Trivy**
- Kubernetes: Docker Desktop's built-in cluster (Settings → Kubernetes → Enable), `kubectl`, plain YAML → **Kustomize**; **Tilt** for the save-and-redeploy loop
- Edge: K8s **Gateway API** with Envoy Gateway (ingress-nginx was retired in March 2026)
- Observability: **OpenTelemetry** → Collector → Prometheus (metrics), Loki (logs), Tempo (traces), Grafana (dashboards)
- Delivery: GitHub Actions (CI) + **Argo CD** (GitOps)
- Docker Compose is used only to run databases/Kafka on your laptop **while coding** (fast feedback). Kubernetes is where the full system runs.

## Project Layout
```
MicroServices_example/
├── pom.xml                  # parent POM
├── docker-compose.yml       # local dev infra only (mongo, postgres, kafka)
├── product-service/
├── inventory-service/
├── order-service/
├── notification-service/
├── api-gateway/
├── k8s/                     # Kubernetes manifests
│   ├── base/                #   one folder per service + infra (deployment.yaml, service.yaml, configmap.yaml)
│   └── overlays/local/      #   Kustomize overlay for your laptop cluster
├── Tiltfile                 # local K8s dev loop (Phase 5)
├── .github/workflows/       # CI pipeline (Phase 12)
└── docs/
    ├── PLAN.md
    ├── PHASE_0.md
    └── LEARNING_NOTES.md
```

## Phased Roadmap (each phase = a working, runnable checkpoint)

### Phase 0 — Foundations (½ day) — *in progress*
- git, parent POM, Maven wrapper, `.gitignore`. See `docs/PHASE_0.md`
- **Learn:** why microservices (and when *not* to), Maven multi-module, BOMs

### Phase 1 — First service: product-service (1–2 days)
- CRUD REST API: `POST /api/products`, `GET /api/products`, `GET /api/products/{id}`
- Controller → Service → Repository; DTOs (Java records) + `@Valid`; `@RestControllerAdvice` with ProblemDetail
- MongoDB via docker-compose, auto-started by Spring Boot's Docker Compose support
- Tests with `@WebMvcTest` + Testcontainers (`@ServiceConnection`, so no hand-written connection URLs)
- Turn on virtual threads; add springdoc so the API is browsable at `/swagger-ui`
- **Learn:** Spring Boot basics, Spring Data, DTOs vs entities, testing, virtual threads vs platform threads, OpenAPI

### Phase 2 — More services + DB-per-service (2 days)
- `inventory-service` (PostgreSQL + Flyway): `GET /api/inventory?skuCode=...&quantity=...`
- `order-service` (PostgreSQL + Flyway): `POST /api/orders`
- **Learn:** database-per-service, schema migrations, why services must not share tables

### Phase 3 — Service-to-service communication (1–2 days)
- order-service calls inventory-service with Spring `RestClient` (HTTP interface client) before saving an order
- The base URL comes from a property (`inventory.url`), e.g. `localhost:8082` for now. This is what makes the Kubernetes switch later a config change, not a code change
- **Learn:** sync coupling, timeouts, what happens when the downstream is down

### Phase 4 — Containerize (1 day)
- Build an image per service with buildpacks; run the whole stack with `docker compose up`
- Add Actuator; expose `/actuator/health/liveness` and `/readiness` (needed by Kubernetes next)
- Config via environment variables (`SPRING_DATA_MONGODB_URI`, …)
- Structured JSON logging with Spring Boot's built-in support (`logging.structured.format.console`)
- Inspect each image's SBOM; scan images with `trivy image` and fix anything critical
- **Learn:** images vs containers, layered jars, 12-factor config, health endpoints, why JSON logs, supply-chain security basics

### Phase 5 — Kubernetes basics + service discovery (2–3 days) — *replaces Eureka*
- Enable Kubernetes in Docker Desktop; create namespace `shoplite`
- For each service: a `Deployment` (Pods, image, resources, probes) and a `Service` (stable DNS name)
- Run databases in-cluster as a simple `StatefulSet` + `PersistentVolumeClaim`
- order-service now calls `http://inventory-service:8080`. Discovery is just DNS
- Scale inventory to 2 replicas (`kubectl scale`), watch the requests spread across Pods. Delete a Pod and watch Kubernetes recreate it
- Add a `Tiltfile`: `tilt up` rebuilds the image and redeploys whenever you save, with all Pod logs in one UI
- **Learn:** Pods, Deployments, ReplicaSets, Services, DNS, probes, `kubectl get/describe/logs/port-forward`, a fast inner dev loop

### Phase 6 — Configuration & secrets (1 day) — *replaces Config Server*
- Move settings into `ConfigMap`s and passwords into `Secret`s; inject them as env vars or mounted files (`spring.config.import=configtree:`)
- Introduce **Kustomize** (`base/` + `overlays/local`) so one set of manifests serves several environments
- Rolling restart to pick up a config change (`kubectl rollout restart`)
- **Learn:** externalized config, why Secrets are only base64 (not encryption), and what tools like Sealed Secrets or External Secrets add
- *Optional:* Spring Cloud Kubernetes for hot-reloading config without a restart

### Phase 7 — API Gateway + Kubernetes Gateway API (1–2 days)
- `api-gateway` (Spring Cloud Gateway) routes `/api/products/**` → `http://product-service`, etc. Adds filters (request ID, rate limiting later)
- Install **Envoy Gateway**; a `Gateway` + `HTTPRoute` expose only api-gateway outside the cluster
- Aggregate every service's OpenAPI docs into one Swagger UI at the gateway
- **Learn:** north-south vs east-west traffic, why only the gateway is public, Gateway API resources (GatewayClass → Gateway → HTTPRoute) and why they replaced Ingress

### Phase 8 — Resilience (1–2 days)
- Resilience4j on order → inventory: timeout, retry, circuit breaker, fallback
- Scale inventory to 0 replicas and watch the breaker open (`/actuator/circuitbreakers`); scale back up and watch it close
- **Learn:** cascading failures, what Kubernetes does (restarts) vs what your code must do (fail gracefully)

### Phase 9 — Event-driven with Kafka (2 days)
- Kafka (single broker, KRaft mode) in the cluster; order-service publishes `OrderPlacedEvent`; notification-service consumes it
- **Learn:** sync vs async, eventual consistency, idempotent consumers, dead-letter topics
- *Stretch:* Strimzi operator; Outbox pattern; Saga with compensation

### Phase 10 — Security (2 days)
- Keycloak in the cluster as OAuth2/OIDC server; gateway validates JWTs; role-based access (only `ADMIN` creates products)
- **Learn:** OAuth2, JWT, securing at the edge vs per-service, K8s NetworkPolicies

### Phase 11 — Observability with OpenTelemetry (2 days)
- Services export metrics, traces and logs over OTLP (Spring Boot's OpenTelemetry support) to an **OpenTelemetry Collector** in the cluster
- Collector → Prometheus (metrics), Tempo (traces), Loki (logs); Grafana shows all three, linked by trace ID
- Follow one order across gateway → order → inventory → Kafka → notification in a single trace, then jump from a slow span to its logs
- **Learn:** logs, metrics, traces; why OpenTelemetry is vendor-neutral (swap backends without touching app code); debugging a request that crosses five Pods

### Phase 12 — CI/CD and GitOps (2 days)
- **GitHub Actions:** on every push, run `./mvnw verify`, build images, scan with Trivy, push to GitHub Container Registry
- **Argo CD** in the cluster watches the `k8s/` folder in git and syncs it. Deploying = merging a commit; rolling back = reverting it
- **Learn:** CI vs CD, GitOps (git is the source of truth, nobody runs `kubectl apply` by hand), image tagging strategies

### Phase 13 — Stretch goals (optional)
- Helm charts; Horizontal Pod Autoscaler; contract testing (Spring Cloud Contract)
- GraalVM native images: compare startup time and memory against the normal JVM
- Spring AI: natural-language product search in product-service
- Service mesh (Istio/Linkerd): mTLS, retries and traffic splitting done by the platform

**Total estimate:** ~5 weeks at a few hours/day.

## How We'll Work
- One phase at a time. At the end of each phase the code runs, the tests pass, and you write a short note in `docs/LEARNING_NOTES.md`.
- One git commit + tag (`phase-N`) per phase, so you can diff and revisit.
- You write the code; I explain the *why* and review.

## Verification (per phase)
- `./mvnw clean verify`: all unit + Testcontainers tests pass
- Phases 1–3: services run locally against `docker compose` infra, exercised with curl or `requests.http`
- Phase 4+: `trivy image` reports no critical vulnerabilities
- Phase 5+: `kubectl get pods -n shoplite` shows all `Running`/`Ready`; calls through `kubectl port-forward` (later the Gateway) work end-to-end
- Phase-specific checks: replicas share load; config change applied after rollout; circuit breaker opens at 0 replicas; Kafka event shows in notification logs; one trace spans all services in Grafana/Tempo; CI goes green and Argo CD deploys the merged commit
