# Stage 1: Build the application using Maven
FROM maven:3.9.9-amazoncorretto-23 AS build
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM amazoncorretto:23-alpine-jdk
# Run as an unprivileged user, so a bug in the app can't touch the rest of the container
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
# Copy the jar file from the build stage
COPY --from=build --chown=app:app /target/*.jar app.jar
# Where the anonymous stats counters are saved (a Docker volume is mounted here on the server)
RUN mkdir -p /app/stats && chown app:app /app/stats
USER app

# Expose the port Spring Boot runs on
EXPOSE 8080

# Run the application
# Tuned for Render's small, shared-CPU instances:
#  - SerialGC: one lightweight collector instead of several GC threads fighting over a tiny CPU
#  - MaxRAMPercentage=60: let the heap use ~60% of the container's RAM instead of Java's default 25%,
#    leaving room for the per-flag pixel-grid cache (up to ~40MB once every flag has been used)
#  - TieredStopAtLevel=1: only the fast JIT compiler, so the CPU isn't burned on heavy optimization
#  - thymeleaf.cache=true: parse each page template once, not on every request (local dev keeps it off
#    via application.properties, which this flag overrides only here)
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-XX:MaxRAMPercentage=60", "-Dspring.thymeleaf.cache=true", "-jar", "app.jar"]