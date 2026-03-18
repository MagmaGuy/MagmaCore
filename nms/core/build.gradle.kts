plugins {
    java
}

// Use a distinct group to avoid GAV conflict with top-level :core module
// Both would otherwise be com.magmaguy:core which causes Gradle to substitute one for the other
group = "com.magmaguy.nms"

base {
    archivesName.set("magmacore-nms-core")
}

repositories {
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    compileOnly(project(":core"))
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    // Bedrock detection - optional runtime dependencies
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.4.2-SNAPSHOT")
}
