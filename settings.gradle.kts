pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Magmacore"

include("core")
include("nms:core")
// Support floor is Minecraft 1.21.4 (= nms:v1_21_R3). The pre-1.21.4 adapter modules have been
// deleted; recover them from git history if a version below 1.21.4 ever needs supporting again.
include("nms:v1_21_R3")
include("nms:v1_21_R4")
include("nms:v1_21_R5")
include("nms:v1_21_R6")
include("nms:v1_21_R7_common")
include("nms:v1_21_R7_spigot")
include("nms:v1_21_R7_paper")
include("nms:v26")
include("dist")
