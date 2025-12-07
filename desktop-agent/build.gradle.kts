plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.terminox"
version = "1.0.0"

repositories {
    mavenCentral()
    // JetBrains repository for pty4j
    maven { url = uri("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
}

val ktorVersion = "2.3.12"
val coroutinesVersion = "1.9.0"
val logbackVersion = "1.4.14"
val slf4jVersion = "2.0.9"

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-network-tls:$ktorVersion")
    implementation("io.ktor:ktor-network-tls-certificates:$ktorVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // PTY support for native terminal
    implementation("org.jetbrains.pty4j:pty4j:0.12.29")

    // QR code generation for mobile pairing
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    // mDNS/Bonjour for service discovery
    implementation("org.jmdns:jmdns:3.5.9")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:1.13.13")
}

application {
    mainClass.set("com.terminox.agent.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.terminox.agent.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// Create distribution archive
tasks.register<Zip>("createDistribution") {
    dependsOn(tasks.jar)
    archiveFileName.set("terminox-agent-${version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(tasks.jar)
    from("src/main/resources") {
        into("config")
    }
    from("README.md")
}
