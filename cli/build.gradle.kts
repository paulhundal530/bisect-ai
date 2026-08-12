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

val shadowJarTask =
    tasks.named("shadowJar", com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        archiveBaseName.set("bisectai")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())
    }

// Produces a single self-executing file `bisect-ai`: a POSIX launcher stub prepended to the fat
// JAR. The JVM reads the zip from the end of the file, so the stub is invisible to `java -jar`,
// while `chmod +x` makes the file directly runnable as `./bisect-ai ...` (no `java -jar`).
val executable by tasks.registering {
    group = "distribution"
    description = "Build a single self-executing ./bisect-ai launcher (stub + fat JAR)."
    val jarProvider = shadowJarTask.flatMap { it.archiveFile }
    val outFile = layout.buildDirectory.file("bisect-ai")
    inputs.file(jarProvider)
    outputs.file(outFile)
    doLast {
        val stub = """
            |#!/bin/sh
            |# Self-executing BisectAI launcher: this file is a shell script with a fat JAR appended.
            |if [ -n "${'$'}JAVA_HOME" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
            |  JAVACMD="${'$'}JAVA_HOME/bin/java"
            |else
            |  JAVACMD="java"
            |fi
            |exec "${'$'}JAVACMD" ${'$'}JAVA_OPTS -jar "${'$'}0" "${'$'}@"
            |
        """.trimMargin() + "\n"
        val out = outFile.get().asFile
        out.outputStream().use { os ->
            os.write(stub.toByteArray(Charsets.UTF_8))
            jarProvider.get().asFile.inputStream().use { it.copyTo(os) }
        }
        out.setExecutable(true, false)
        logger.lifecycle("Built self-executing launcher: ${out.absolutePath}")
    }
}
