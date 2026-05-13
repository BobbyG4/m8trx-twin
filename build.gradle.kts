plugins {
    application
    alias(deps.plugins.kotlinJvm)
    alias(deps.plugins.ktlint)
}

application {
    mainClass.set("com.m8trx.twin.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.nats:jnats:${deps.versions.jnats.get()}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${deps.versions.jackson.get()}")
    implementation("com.fasterxml.jackson.core:jackson-databind:${deps.versions.jackson.get()}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${deps.versions.coroutines.get()}")
    implementation("ch.qos.logback:logback-classic:${deps.versions.logback.get()}")
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version.set("1.5.0")
    android.set(false)
    outputToConsole.set(true)
}
