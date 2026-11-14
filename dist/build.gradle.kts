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
    // Support floor: Minecraft 1.21.4 (v1_21_R3). Older adapters are no longer built.
    implementation(project(":nms:v1_21_R3"))
    implementation(project(":nms:v1_21_R4"))
    implementation(project(":nms:v1_21_R5"))
    implementation(project(":nms:v1_21_R6"))
    implementation(project(":nms:v1_21_R7_spigot"))
    // Don't include v1_21_R7_paper via normal dependency - we'll add the reobfJar directly
    implementation(project(":nms:v26"))
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

// Bundled libraries live under com.magmaguy.magmacore, so a consumer's single
// com.magmaguy.magmacore relocation also gives it its own copy of luaj, reflections and javassist.
val packagePath = "com.magmaguy.magmacore.shaded"

tasks.shadowJar {
    // Shadow's up-to-date check does not see relocation targets; without this a changed
    // packagePath reuses the previous jar.
    inputs.property("packagePath", packagePath)
    relocate("org.luaj", "$packagePath.luaj")
    relocate("org.reflections", "$packagePath.reflections")
    // Reflections' own dependencies. Left at their original names, every plugin ships the
    // same classes, and Bukkit's cross-plugin lookup lets another plugin (WorldEdit, for
    // javax.annotation) bind to whichever copy loaded first, which pins that plugin's old
    // class loader after a restart-free update. JSR-305 is compile-time only.
    relocate("javassist", "$packagePath.javassist")
    exclude("javax/annotation/**")
    exclude("META-INF/maven/com.google.code.findbugs/**")
    // luaj-jse's command-line programs (lua, luac, luajc) sit in the default package and
    // nothing in the jar calls them.
    exclude("lua.class", "luac.class", "luajc.class", "luajc$*.class")
    // luaj's JSR-223 registration names the unrelocated org.luaj factory, which is not in the
    // jar, so a ScriptEngineManager could only fail on it or reach another plugin's luaj.
    exclude("META-INF/services/javax.script.ScriptEngineFactory")
    // slf4j-api, which only reflections uses. Every supported server (Spigot and Paper 1.21.4+)
    // ships slf4j-api, and plugin class loaders ask the server first, so this copy never loads.
    exclude("org/slf4j/**", "META-INF/maven/org.slf4j/**")
    // Javadoc fragments from javassist.
    exclude("**/package.html")
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

// ---- Adapter linkage check ---------------------------------------------------------------
// Each adapter module is built for one server. verifyAdapterLinkage resolves every server class,
// field and method the adapters in the shaded jar use against that server's jar, and fails the
// build when one does not exist. A module jar that was remapped twice produces such references.
// The check runs after every shadowJar and before every publication, so a jar like that cannot
// reach Maven Local or the repository. The check itself is src/linkage/java (AdapterLinkageCheck);
// it also fails when the jar holds an adapter module that is missing from this list.
val adapterServers = mapOf(
    "v1_21_R3" to "org.spigotmc:spigot:1.21.4-R0.1-SNAPSHOT",
    "v1_21_R4" to "org.spigotmc:spigot:1.21.5-R0.1-SNAPSHOT",
    "v1_21_R5" to "org.spigotmc:spigot:1.21.6-R0.1-SNAPSHOT",
    "v1_21_R6" to "org.spigotmc:spigot:1.21.9-R0.1-SNAPSHOT",
    "v1_21_R7_spigot" to "org.spigotmc:spigot:1.21.11-R0.1-SNAPSHOT",
    // reobfJar gives the Paper module Spigot's names too.
    "v1_21_R7_paper" to "org.spigotmc:spigot:1.21.11-R0.1-SNAPSHOT",
    "v26" to "org.spigotmc:spigot:26.3-R0.1-SNAPSHOT",
)
val adapterServerJars = adapterServers.mapValues { (_, notation) ->
    configurations.detachedConfiguration(dependencies.create(notation)).apply { isTransitive = false }
}

// Its own source set: compiled for the build, never part of the shaded jar.
val linkage by sourceSets.creating

val verifyAdapterLinkage by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when a shaded NMS adapter uses a server class, field or method its server jar does not have."
    val shadedJar = tasks.shadowJar.flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("reports/adapter-linkage.txt")
    inputs.file(shadedJar)
    inputs.files(adapterServerJars.values)
    outputs.file(report)
    classpath = linkage.runtimeClasspath
    mainClass.set("com.magmaguy.magmacore.linkage.AdapterLinkageCheck")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(shadedJar.get().asFile.path, report.get().asFile.path) +
            adapterServerJars.map { (module, serverJar) -> "$module=${serverJar.singleFile.path}" }
    })
}

tasks.shadowJar {
    finalizedBy(verifyAdapterLinkage)
}
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyAdapterLinkage)
}
tasks.named("check") {
    dependsOn(verifyAdapterLinkage)
}
