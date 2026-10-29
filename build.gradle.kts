plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ewerlin"
version = "0.1.0"
description = "Mattermost-Bot, der Nachrichten an einen opencode-Server weiterreicht"

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
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    implementation("org.springframework:spring-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.java-websocket:Java-WebSocket:1.6.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    // Integrationstests brauchen das laufende Test-Setup bzw. Docker: ./gradlew integrationTest
    useJUnitPlatform { excludeTags("integration") }
    systemProperty("file.encoding", "UTF-8")
}

tasks.register<Test>("integrationTest") {
    description = "Integrationstests gegen Mattermost, opencode und das opencode-Image (Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    shouldRunAfter(tasks.test)
    // Umgebung (CAPTAIN_MM_ENV, CAPTAIN_OC_ENV, OPENCODE_URL, …) wird durchgereicht
    systemProperty("file.encoding", "UTF-8")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}

// Node-Unit-Tests des opencode-Startskripts (infra/opencode/*.test.mjs)
tasks.register<Exec>("nodeTest") {
    description = "Node-Unit-Tests von infra/opencode (start.mjs, egress.mjs, shared.mjs) und infra/n8n."
    group = "verification"
    workingDir = projectDir
    commandLine(
        "node", "--test",
        "infra/opencode/start.test.mjs", "infra/opencode/webfetch.test.mjs", "infra/opencode/shared.test.mjs",
        "infra/n8n/captain-umschlag.test.mjs",
    )
}

tasks.bootJar {
    archiveFileName = "captain.jar"
}

tasks.jar {
    enabled = false
}
