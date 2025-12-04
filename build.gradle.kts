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
    implementation("net.minestom:minestom:2025.10.31-1.21.10")
    implementation("org.jctools:jctools-core:4.0.5")
    implementation("it.unimi.dsi:fastutil:8.5.18")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}