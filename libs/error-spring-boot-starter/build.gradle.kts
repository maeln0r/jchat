plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    val springBootVersion = "3.5.6"
    api(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))

    api("org.springframework:spring-webmvc")   // ResponseEntityExceptionHandler, @RestControllerAdvice
    api("org.springframework:spring-web")      // ProblemDetail, ErrorResponseException
    api("org.springframework:spring-context")  // MessageSource, LocaleContextHolder
    api("org.springframework:spring-tx")       // DataIntegrityViolationException
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.slf4j:slf4j-api")

    compileOnly("jakarta.servlet:jakarta.servlet-api")            // HttpServletRequest
    compileOnly("jakarta.validation:jakarta.validation-api")      // ConstraintViolationException

    compileOnly("org.springframework.boot:spring-boot-autoconfigure-processor")
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")
}
