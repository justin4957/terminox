plugins {
    kotlin("jvm") version "1.9.22"
    application
}

group = "com.terminox"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Apache MINA SSHD - same library as Terminox uses
    implementation("org.apache.sshd:sshd-core:2.13.2")
    implementation("org.apache.sshd:sshd-sftp:2.13.2")

    // ED25519 support
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.2")

    // QR code generation for mobile pairing
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    // JSON serialization for pairing payload
    implementation("com.google.code.gson:gson:2.10.1")

    // Testing
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.terminox.testserver.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.terminox.testserver.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
