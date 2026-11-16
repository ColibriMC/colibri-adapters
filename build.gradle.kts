plugins {
    java
}

group = "space.colibrimc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

/**
 * Adaptadores de plugins de Bukkit para ColibriMC.
 *
 * Todo es `compileOnly`: en tiempo de ejecucion las clases las ponen ColibriMC (Minestom, la API de Bukkit) y el
 * plugin al que se adapta (WorldEdit). Este proyecto no usa ninguna clase interna de ColibriMC.
 */
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6")
    compileOnly("com.google.guava:guava:33.6.0-jre")
    compileOnly("net.minestom:minestom:2026.10.07-26.2")
    compileOnly("net.kyori:adventure-api:5.2.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.jar {
    archiveBaseName.set("ColibriWorldEditAdapter")
    archiveVersion.set("")
}
