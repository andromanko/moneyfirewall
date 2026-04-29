plugins {
    java
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.moneyfirewall"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.telegram:telegrambots-springboot-longpolling-starter:8.0.0")
    implementation("org.telegram:telegrambots-client:8.0.0")

    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    implementation("com.google.api-client:google-api-client:2.8.1")
    implementation("com.google.oauth-client:google-oauth-client:1.39.0")
    implementation("com.google.http-client:google-http-client-jackson2:1.47.1")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20251110-2.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

