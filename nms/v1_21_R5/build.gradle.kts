plugins {
    id("io.github.patrick.remapper")
}

dependencies {
    compileOnly("org.spigotmc:spigot:1.21.6-R0.1-SNAPSHOT:remapped-mojang")
    compileOnly(project(":nms:core"))
}

// The jar task writes the Mojang-mapped jar as <module>-<version>-mojang.jar and the remapper
// writes the Spigot-mapped copy beside it. They must stay two files. When the remapper wrote
// over the jar task's output, Gradle skipped the jar task as up to date in the next build of
// the same daemon and the remapper ran again over the already remapped jar. That second pass
// renames server classes to ones that do not exist (dist verifyAdapterLinkage catches it).
val remappedJarName = "${base.archivesName.get()}-${project.version}.jar"

tasks {
    remap {
        version.set("1.21.6")
        archiveName.set(remappedJarName)
        // Declared so Gradle tracks what this task reads and writes.
        inputs.file(jar.flatMap { it.archiveFile })
        outputs.file(layout.buildDirectory.file("libs/$remappedJarName"))
        dependsOn(jar)
    }
    jar {
        archiveClassifier.set("mojang")
        finalizedBy("remap")
    }
}

// Make sure the remapped JAR is used as the outgoing artifact
val remappedJar = tasks.named("remap").map {
    layout.buildDirectory.file("libs/$remappedJarName").get().asFile
}

configurations {
    named("runtimeElements") {
        outgoing {
            artifacts.clear()
            artifact(remappedJar) {
                builtBy(tasks.named("remap"))
            }
        }
    }
    named("apiElements") {
        outgoing {
            artifacts.clear()
            artifact(remappedJar) {
                builtBy(tasks.named("remap"))
            }
        }
    }
}
