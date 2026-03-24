plugins {
    java
    `maven-publish`
    id("io.github.patrick.remapper") version "1.4.2" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19" apply false
    id("com.gradleup.shadow") version "9.0.0-beta12" apply false
}

val projectVersion = "2.1.0-SNAPSHOT"

allprojects {
    group = "com.magmaguy"
    version = projectVersion
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://libraries.minecraft.net/")
        maven("https://repo.magmaguy.com/releases")
        maven("https://maven.enginehub.org/repo/")
        maven("https://repo.opencollab.dev/main/")
    }

    dependencies {
        "compileOnly"("org.projectlombok:lombok:1.18.42")
        "annotationProcessor"("org.projectlombok:lombok:1.18.42")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "magmaguy"
                url = uri("https://repo.magmaguy.com/releases")
                credentials {
                    username = findProperty("magmaguyUsername") as String? ?: System.getenv("MAGMAGUY_USERNAME") ?: ""
                    password = findProperty("magmaguyPassword") as String? ?: System.getenv("MAGMAGUY_PASSWORD") ?: ""
                }
            }
        }
    }
}
