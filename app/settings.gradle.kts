rootProject.name = "rpcnode-server"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // kotlin.jvmToolchain(26): download a JDK 26 when the machine has none (WSL, fresh checkout).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
