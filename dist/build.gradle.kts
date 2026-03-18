plugins {
    id("com.gradleup.shadow")
}

// Disable Gradle module metadata (causes issues with shadow jar variants)
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

dependencies {
    implementation(project(":core"))
    implementation(project(":nms:core"))
    implementation(project(":nms:v1_19_R3"))
    implementation(project(":nms:v1_20_R1"))
    implementation(project(":nms:v1_20_R2"))
    implementation(project(":nms:v1_20_R3"))
    implementation(project(":nms:v1_20_R4"))
    implementation(project(":nms:v1_21_R1"))
    implementation(project(":nms:v1_21_R2"))
    implementation(project(":nms:v1_21_R3"))
    implementation(project(":nms:v1_21_R4"))
    implementation(project(":nms:v1_21_R5"))
    implementation(project(":nms:v1_21_R6"))
    implementation(project(":nms:v1_21_R7_spigot"))
    // Don't include v1_21_R7_paper via normal dependency - we'll add the reobfJar directly
}

// Get the reobfJar output from v1_21_R7_paper after projects are evaluated
gradle.projectsEvaluated {
    val reobfJar = project(":nms:v1_21_R7_paper").tasks.named<io.papermc.paperweight.tasks.RemapJar>("reobfJar")

    tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        dependsOn(reobfJar)
        // Include the reobfJar output directly
        from(zipTree(reobfJar.flatMap { it.outputJar }))
    }
}

val packagePath = "com.magmaguy.shaded"

tasks.shadowJar {
    relocate("org.luaj", "$packagePath.luaj")
    relocate("org.reflections", "$packagePath.reflections")
    archiveBaseName.set("MagmaCore")
    archiveClassifier.set(null as String?)
    archiveVersion.set(project.version.toString())

    exclude("**/package-info.class")
    exclude("META-INF/MANIFEST.MF")
}

// Configure publishing to use shadow jar
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                artifactId = "MagmaCore"
                artifacts.clear()
                artifact(tasks.shadowJar)

                pom.withXml {
                    val dependenciesNode = asNode().get("dependencies")
                    if (dependenciesNode is groovy.util.NodeList && dependenciesNode.isNotEmpty()) {
                        asNode().remove(dependenciesNode.first() as groovy.util.Node)
                    }
                }
            }
        }
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }
    jar {
        enabled = false
    }
}
