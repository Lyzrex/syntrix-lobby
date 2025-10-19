plugins {
    java

}

group = "net.syntrix"
version = "1.0.0-beta.2"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")

    repositories {
        maven("https://repo.papermc.io/repository/maven-public/")
        maven {
            url = uri("https://maven.pkg.github.com/Lyzrex/LythCore-API")
            credentials {

            }
        }
    }

    dependencies {
        compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
        compileOnly("net.lythcore:lythcore-api:0.1.2")
    }



tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}


tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}


val serverPlugins = findProperty("serverPluginsDir") as String?
tasks.register<Copy>("copyPlugin") {
    dependsOn(tasks.named("jar"))
    from(layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))
    into(serverPlugins ?: "${project.projectDir}/../server/plugins")
}
}