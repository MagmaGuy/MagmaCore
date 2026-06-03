dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.magmaguy:FreeMinecraftModels:2.3.17")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.7")
    implementation("org.reflections:reflections:0.10.2")
    implementation("org.luaj:luaj-jse:3.0.1")

    testImplementation("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
