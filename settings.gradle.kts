plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "bisectai"

include(
    "core",
    "execution",
    "spec",
    "git",
    "evaluation",
    "analysis",
    "reporting",
    "cli",
    "integration-tests",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
