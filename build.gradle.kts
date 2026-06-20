plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.jellypudding"
version = "3.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.+")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Use Mojang mappings since we're targeting Paper only
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION 