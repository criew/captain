# Captain-Bot (Mattermost <-> opencode), Spring Boot. Gebaut ueber das Root-compose.yml
# bzw. compose.deploy.yml.

# Build-Stufe: Gradle + JDK 21. Ein Proxy aus ~/.docker/config.json kommt als
# Build-Argument an; gradle-proxy.sh uebersetzt ihn fuer Gradle
# (docs/betrieb.md: „Proxy und eigene CA“).
FROM gradle:9.8.1-jdk21 AS build
ARG HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy
WORKDIR /src
COPY docker/gradle-proxy.sh /usr/local/bin/gradle-proxy.sh
COPY settings.gradle.kts build.gradle.kts ./
# Abhaengigkeiten vorab laden (eigene Docker-Schicht, solange sich die Build-Dateien nicht aendern)
RUN sh /usr/local/bin/gradle-proxy.sh \
 && gradle dependencies --no-daemon -q --configuration runtimeClasspath > /dev/null \
 && gradle dependencies --no-daemon -q --configuration compileClasspath > /dev/null
COPY src/main ./src/main
RUN gradle bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/captain.jar ./captain.jar

# Laeuft im Betrieb als CAPTAIN_UID:CAPTAIN_GID (compose.deploy.yml), dieselbe
# UID wie opencode: beide teilen /tmp/captain, der Bot legt dort die
# Session-Verzeichnisse an. REST-Eingabekanal (mit CAPTAIN_API_TOKEN): Port 8090.
ENV SESSIONS_DIR=/tmp/captain \
    DATA_DIR=/data
VOLUME ["/data"]
EXPOSE 8090

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/captain.jar"]
