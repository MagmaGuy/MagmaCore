java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Target Java 21 bytecode so this module can be included in the dist shadow jar
// which uses Java 21. The 26.x NMS classes are only loaded at runtime on Java 25+ servers.
tasks.withType<JavaCompile> {
    options.release.set(21)
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT")
    compileOnly("org.spigotmc:spigot:26.2-R0.1-SNAPSHOT")
    compileOnly("org.spigotmc:minecraft-server:26.2-R0.1-SNAPSHOT")
    compileOnly(project(":core"))
    compileOnly(project(":nms:core"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT")
    testImplementation("org.spigotmc:minecraft-server:26.2-R0.1-SNAPSHOT")
    testImplementation("org.spigotmc:spigot:26.2-R0.1-SNAPSHOT")
    testImplementation(project(":core"))
    testImplementation(project(":nms:core"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    workingDir = layout.buildDirectory.dir("test-work").get().asFile
    doFirst {
        workingDir.mkdirs()
    }
}
