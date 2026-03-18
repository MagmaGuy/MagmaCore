plugins {
    id("com.gradleup.shadow")
}

// Disable Gradle module metadata (causes issues with shadow jar variants)
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

dependencies {
    implementation(project(":core"))
    // NMS modules will be added in Task 2
}

val packagePath = "com.magmaguy.shaded"

tasks.shadowJar {
    relocate("org.luaj", "$packagePath.luaj")
    relocate("org.reflections", "$packagePath.reflections")
    archiveBaseName.set("MagmaCore")
    archiveClassifier.set(null as String?)
    archiveVersion.set(project.version.toString())
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
