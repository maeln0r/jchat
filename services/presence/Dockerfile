# ===== build stage =====
FROM gradle:8.10.1-jdk21 AS build
WORKDIR /src
COPY . .
RUN ./gradlew :services:presence:bootJar -x test

# ===== run stage =====
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/services/presence/build/libs/*.jar /app/app.jar
EXPOSE 8091
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"
ENTRYPOINT ["java","-jar","/app/app.jar"]
