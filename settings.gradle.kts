pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "Magmacore"

include("core")
include("nms:core")
include("nms:v1_19_R3")
include("nms:v1_20_R1")
include("nms:v1_20_R2")
include("nms:v1_20_R3")
include("nms:v1_20_R4")
include("nms:v1_21_R1")
include("nms:v1_21_R2")
include("nms:v1_21_R3")
include("nms:v1_21_R4")
include("nms:v1_21_R5")
include("nms:v1_21_R6")
include("nms:v1_21_R7_common")
include("nms:v1_21_R7_spigot")
include("nms:v1_21_R7_paper")
include("nms:v26")
include("dist")
