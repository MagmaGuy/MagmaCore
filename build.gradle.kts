plugins {
    java
    `maven-publish`
    id("io.github.patrick.remapper") version "1.4.2" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19" apply false
    id("com.gradleup.shadow") version "9.0.0-beta12" apply false
}

val projectVersion = "2.2.0-SNAPSHOT"

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

    // One Mind implementation, compiled against each adapter's native mappings.
    // This follows the existing R7 Paper/Spigot shared-source arrangement.
    if (name in setOf("v1_21_R3", "v1_21_R4", "v1_21_R5", "v1_21_R6",
            "v1_21_R7_paper", "v1_21_R7_spigot", "v26")) {
        val adapterName = name
        val mindSources = tasks.register<Sync>("generateMindSources") {
            from(rootProject.file("nms/mind-shared/src/main/java"))
            into(layout.buildDirectory.dir("generated-mind/java"))
            filter { line -> line.replace("mindshared", adapterName) }
            eachFile { path = path.replace("mindshared", adapterName) }
            includeEmptyDirs = false
        }
        sourceSets.main { java.srcDir(mindSources) }
        dependencies { "compileOnly"(project(":core")) }
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
