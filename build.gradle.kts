plugins {
    id("java")
}

group = "net.minestom"
version = "dev"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("net.minestom:minestom:2026.04.13-1.21.11")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("it.unimi.dsi:fastutil:8.5.18")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}