# syntax=docker/dockerfile:1.7
FROM node:24.16.0-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run check && npm run build

FROM eclipse-temurin:25-jdk AS backend
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY src/ src/
COPY frontend/ frontend/
COPY --from=frontend /workspace/frontend/dist/ frontend/dist/
RUN chmod +x gradlew && ./gradlew bootJar -PfrontendSkipBuild=true --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home incidentdesk
COPY --from=backend --chown=incidentdesk:incidentdesk /workspace/build/libs/incident-desk-web.jar app.jar
USER incidentdesk
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
