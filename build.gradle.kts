plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.bisectai"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
        jvmToolchain(21)
    }

    dependencies {
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        add("testImplementation", libs.findLibrary("junit-jupiter").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
