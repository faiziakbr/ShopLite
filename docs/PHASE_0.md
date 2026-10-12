# Phase 0 — Foundations: Step-by-step Guide (ShopLite)

## Context
The full roadmap (Phases 0–13, running on Kubernetes) is in `docs/PLAN.md`. This guide covers **Phase 0 only**: setting up the empty project skeleton every later service plugs into. No business code yet. At the end you'll have a git repo, a Maven "parent" project with a pinned Maven version (the wrapper), a pinned Java version, and a notes file.

What I checked on your machine:
- Folder is empty (just `.DS_Store`), not a git repo
- JDKs: 27, 26, 26.0.1, 17 (no 25). Homebrew and git are installed. Maven is **not** installed
- Spring Initializr currently defaults to **Spring Boot 4.1.1**. Its matching **Spring Cloud** release train is **2025.1.3**

Decision: target **Java 25 (LTS)**. Most companies run LTS releases, and libraries (Lombok, Testcontainers, Mockito agents) support LTS JDKs first. Java 27 is a short-lived release. If you'd rather not install another JDK, set `java.version` to 27 below. Initializr lists 27 as supported.

---

## Concepts first (read before typing)

**Monolith vs microservices.** A monolith is one deployable app containing every feature. Microservices split the app into small, independently deployable services. Each one owns its own data and talks to the others over the network (HTTP or messages).

**Why do it:** teams deploy independently, each service scales separately, a failure stays contained, and each service can choose its own tech.
**Why not to (important!):** network calls fail and are slow, data is spread across many databases (no simple JOINs or transactions), and you need to run, monitor and debug many processes. For a small team or app, a well-structured monolith is usually better. We're doing microservices here to *learn the patterns*, not because a shop this size needs them.

**Maven multi-module.** One root `pom.xml` with `<packaging>pom</packaging>` (the "parent") lists child modules (`product-service`, `order-service`, …). Children inherit versions, plugins and Java settings from it. Why: one place to upgrade Spring Boot for every service, and one command (`./mvnw verify`) builds and tests everything.
> In real companies each microservice often lives in its *own* repo. A single repo ("monorepo") is far easier while learning. The services stay independent at runtime either way.

**Maven Wrapper (`mvnw`).** A small script committed to the repo. It downloads the exact Maven version the project specifies. Why: everyone (you, CI, a future teammate) builds with the same Maven, without installing it globally.

**BOMs (Bill of Materials).** `spring-boot-starter-parent` and the `spring-cloud-dependencies` BOM pin compatible versions for hundreds of libraries. That's why you'll never write a version number on a Spring dependency. Spring Boot and Spring Cloud versions **must match** (Boot 4.1.x ↔ Cloud 2025.1.x). We still need Spring Cloud on Kubernetes, for Spring Cloud Gateway and the Resilience4j circuit breaker. Kubernetes only replaces its discovery (Eureka) and config (Config Server) parts.

---

## Steps

### Step 1 — Install JDK 25 and select it
```bash
brew install --cask temurin@25
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # add this line to ~/.zshrc to make it permanent
java -version                                       # should print 25
```
Why: Maven compiles with whatever `JAVA_HOME` points to. Your current default is 27.

### Step 2 — Initialize git
```bash
cd ~/Documents/Learning/SpringBoot/MicroServices_example
git init
```
Why: one commit per phase lets you `git diff phase-0 phase-1` later and see exactly what each concept added.

Create **`.gitignore`**:
```
# Build output
target/
# IDE
.idea/
*.iml
.vscode/
# macOS
.DS_Store
# Logs / env
*.log
.env
# Keep the wrapper jar out; mvnw downloads it
.mvn/wrapper/maven-wrapper.jar
```
Why: build output, IDE files and secrets don't belong in version control.

### Step 3 — Create the Maven Wrapper
```bash
brew install maven          # needed once, only to generate the wrapper
mvn -N wrapper:wrapper -Dmaven=3.9.16
./mvnw -v                    # proves the wrapper works
```
This creates `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties`. From here on, **always use `./mvnw`, not `mvn`**. (You could `brew uninstall maven` afterwards.)
`-N` = "non-recursive", run only in the root.

### Step 4 — Create the parent `pom.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Inherit Spring Boot's defaults: dependency versions, plugin config, UTF-8, etc. -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>com.shoplite</groupId>
    <artifactId>shoplite-parent</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>   <!-- a parent produces no jar, it only groups modules -->
    <name>ShopLite</name>

    <properties>
        <java.version>25</java.version>
        <spring-cloud.version>2025.1.3</spring-cloud.version>
    </properties>

    <!-- Each service gets added here as we build it -->
    <modules>
        <!-- <module>product-service</module>   Phase 1 -->
    </modules>

    <!-- Version alignment only: this does NOT add dependencies to any module -->
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```
Key points:
- `<parent>` = *inheritance* (children get Boot's config). `dependencyManagement` + `import` = *version pinning only*. Students often mix these up.
- Each child module will later declare `<parent>` = `shoplite-parent`, so everything flows down from this file.

### Step 5 — Notes file
Create `docs/LEARNING_NOTES.md` with a `## Phase 0` heading. Write 3–5 bullets in **your own words**: what a microservice is, one reason *not* to use them, and what the parent POM and wrapper do. Writing it yourself is what makes it stick.

### Step 6 — Verify and commit
```bash
./mvnw validate             # BUILD SUCCESS = POM is valid and Spring/Cloud versions resolve
./mvnw help:effective-pom | less   # optional: see everything inherited from Spring Boot's parent
git add .
git commit -m "Phase 0: project skeleton (parent POM, Maven wrapper)"
git tag phase-0
```

---

## Final layout after Phase 0
```
MicroServices_example/
├── .gitignore
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw, mvnw.cmd
├── pom.xml
└── docs/
    ├── PLAN.md
    └── LEARNING_NOTES.md
```
`docker-compose.yml` is deferred to Phase 1, when we add its first real service (MongoDB).

## Verification checklist
- [ ] `java -version` → 25 (or 27 if you chose that)
- [ ] `./mvnw -v` prints Maven 3.9.16
- [ ] `./mvnw validate` → BUILD SUCCESS
- [ ] `git log --oneline` shows the Phase 0 commit; `git tag` shows `phase-0`

## Self-check questions (answer in your notes)
1. Why can't two microservices just share one database table?
2. What's the difference between `<parent>` and a BOM in `<dependencyManagement>`?
3. Why commit `mvnw` instead of telling people to install Maven?

## Next: Phase 1 — product-service (a child module generated from start.spring.io: REST CRUD on MongoDB, virtual threads, Swagger UI, tests with Testcontainers)
