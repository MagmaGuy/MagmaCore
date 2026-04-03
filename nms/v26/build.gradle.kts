java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Target Java 21 bytecode so this module can be included in the dist shadow jar
// which uses Java 21. The 26.1 NMS classes are only loaded at runtime on Java 25+ servers.
tasks.withType<JavaCompile> {
    options.release.set(21)
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:26.1-R0.1-SNAPSHOT")
    compileOnly("org.spigotmc:spigot:26.1-R0.1-SNAPSHOT")
    compileOnly("org.spigotmc:minecraft-server:26.1-R0.1-SNAPSHOT")
    compileOnly(project(":nms:core"))
}
