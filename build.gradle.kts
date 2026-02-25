plugins {
    java
}

group = "net.lyzrex"
version = "0.0.3-beta.5"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://maven.byteminer.net")
}

dependencies {

    implementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    implementation("de.murmelmeister.murmelapi:MurmelAPI:0.0.6-SNAPSHOT")
    implementation("de.murmelmeister.library:MurmelLib:0.0.1-SNAPSHOT")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
}

tasks.processResources {

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}