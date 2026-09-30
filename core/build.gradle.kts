dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.magmaguy:FreeMinecraftModels:2.3.17")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.7")
    implementation("org.reflections:reflections:0.10.2")
    implementation("org.luaj:luaj-jse:3.0.1")

    testImplementation("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Match core tests run on MockBukkit, which needs paper-api 26.2 and Java 25.
// They get their own source set so the existing spigot-api tests stay as they are.
val sourceSets = the<SourceSetContainer>()
val toolchains = extensions.getByType<JavaToolchainService>()
val matchTest: SourceSet = sourceSets.create("matchTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations[matchTest.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[matchTest.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])

dependencies {
    "matchTestImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "matchTestImplementation"("org.junit.jupiter:junit-jupiter")
    "matchTestImplementation"("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
    "matchTestImplementation"("io.papermc.paper:paper-api:26.2.build.111-stable")
    "matchTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

configurations.matching { it.name in setOf("matchTestCompileClasspath", "matchTestRuntimeClasspath") }
    .configureEach { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
tasks.named<JavaCompile>("compileMatchTestJava") {
    javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
val matchTestTask = tasks.register<Test>("matchTest") {
    description = "Runs the match core tests on MockBukkit."
    group = "verification"
    testClassesDirs = matchTest.output.classesDirs
    classpath = matchTest.runtimeClasspath
    javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    // MockBukkit reports unimplemented operations as skips; an untested behaviour is not a pass.
    var skipped = 0L
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (descriptor.parent == null) skipped = result.skippedTestCount
    }))
    doLast {
        if (skipped > 0) throw GradleException("$skipped match tests were skipped; see the JUnit XML.")
    }
}
tasks.named("check") { dependsOn(matchTestTask) }
