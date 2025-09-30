plugins {
    id("java")
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "dev.jchat"
version = "0.1.0"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // DB & migrations
    implementation("org.liquibase:liquibase-core:4.33.0")
    runtimeOnly("org.postgresql:postgresql:42.7.7")

    // Keycloak Admin REST client
    implementation("org.keycloak:keycloak-core:26.3.4")
    implementation("org.keycloak:keycloak-admin-client:26.0.6")
    implementation("io.opentelemetry:opentelemetry-api:1.54.1")

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.testcontainers:spring-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
}

tasks.test { useJUnitPlatform() }