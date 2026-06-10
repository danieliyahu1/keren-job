# ==============================================================================
# Stage 1: Build the Spring Boot fat JAR
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Copy Maven wrapper and POM first (better layer caching — deps change less often)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build
COPY src/ src/
RUN ./mvnw package -DskipTests -B && \
    mv target/*.jar target/app.jar

# ==============================================================================
# Stage 2: Runtime image
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Install runtime dependencies:
#   tini — proper PID 1 / signal handling
RUN apk add --no-cache tini

# Create non-root user for running the app
RUN addgroup -S kerenjob && adduser -S kerenjob -G kerenjob

# Copy the fat JAR from the build stage
COPY --from=build --chown=kerenjob:kerenjob /build/target/app.jar /app/app.jar

# Switch to non-root user
USER kerenjob
WORKDIR /app

EXPOSE 8080

# Use tini as PID 1
ENTRYPOINT ["tini", "--"]

CMD ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]
