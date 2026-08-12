plugins {
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":execution"))
    implementation(project(":spec"))
    implementation(project(":git"))
    implementation(project(":evaluation"))
    implementation(project(":analysis"))
    implementation(project(":reporting"))
    implementation(libs.picocli)
}

application {
    mainClass.set("com.bisectai.cli.MainKt")
}

// Generate a version resource surfaced to the CLI's --version output.
val generateVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version")
    val versionValue = project.version.toString()
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("bisectai-version.txt").writeText(versionValue)
    }
}

sourceSets {
    main {
        resources {
            srcDir(generateVersion.map { it.outputs.files.singleFile })
        }
    }
}

tasks.named("shadowJar") {
    this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
    archiveBaseName.set("bisectai")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}
