plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.cadence"
version = "0.1.0"

java {
    // §3 pins Java 21 (virtual threads). Toolchain auto-provisions it via the
    // foojay resolver in settings.gradle.kts when the box lacks a JDK 21.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // P2-F (categorisation worker): Redis-backed pattern cache + daily token cap,
    // and the official Anthropic Java SDK for LLM categorisation.
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Self-issued JWT (HS256). jjwt — pure-Java, no extra service (§3 self-issued).
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Integration tests against real Postgres+Timescale need Docker; they live in
    // the `integrationTest` source set and are excluded from the default `build`.
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:1.20.3")
    }
}

// Separate source set for Docker-dependent integration tests so a plain
// `./gradlew build` (no Docker) stays green. Run them with `./gradlew integrationTest`.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (requires Docker for Testcontainers)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("cadence-backend.jar")
}

// Package the §9-owned migration folder (backend/migrations) onto the classpath
// at db/migration so Flyway's default location finds it in the runnable jar.
tasks.named<ProcessResources>("processResources") {
    from("migrations") { into("db/migration") }
}
