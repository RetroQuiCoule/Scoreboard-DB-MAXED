plugins {
    java
    // La version corrigée et renommée du plugin Shadow
    id("com.gradleup.shadow") version "8.3.5"
}

group = "me.mklv"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven(url ="https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    // API Paper & PlaceholderAPI (Non inclus dans le jar final)
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Bibliothèques à inclure dans ton plugin (Shaded)
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.mysql:mysql-connector-j:8.4.0")

    // Drivers fournis par le serveur
    compileOnly("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.4.0")
}

tasks {
    processResources {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        filesMatching("plugin.yml") {
            expand(mapOf("version" to project.version))
        }
    }

    shadowJar {
        // Supprime le suffixe "-all" pour avoir un nom de fichier propre
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // Relocalisation pour éviter les conflits avec d'autres plugins
        relocate("org.yaml.snakeyaml", "me.mklv.shaded.snakeyaml")
        relocate("com.zaxxer.hikari", "me.mklv.shaded.hikari")
        relocate("org.postgresql", "me.mklv.shaded.postgresql")
    }

    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(17)
    }
}