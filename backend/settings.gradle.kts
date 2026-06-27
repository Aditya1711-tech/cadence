plugins {
    // Lets Gradle auto-provision the Java 21 toolchain (this box has JDK 17).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "cadence-backend"
