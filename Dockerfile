FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ARG GRADLE_VERSION=8.11.1
RUN mkdir -p /opt/gradle \
    && curl -4 -fsSL -o /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
    && unzip -q /tmp/gradle.zip -d /opt/gradle \
    && rm -f /tmp/gradle.zip

ENV PATH="/opt/gradle/gradle-${GRADLE_VERSION}/bin:${PATH}"
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Europe/Minsk
COPY --from=build /app/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

