dependencies {
    testImplementation(project(":core"))
    testImplementation(project(":execution"))
    testImplementation(project(":spec"))
    testImplementation(project(":git"))
    testImplementation(project(":evaluation"))
    testImplementation(project(":analysis"))
    testImplementation(project(":reporting"))
    testImplementation(project(":cli"))
}

// Deterministic end-to-end test. MUST NOT call Claude (uses the fake provider).
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the deterministic end-to-end regression scenario (no Claude)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("e2e")
    }
    // Never leak credentials into the deterministic test.
    environment("ANTHROPIC_API_KEY", "")
    environment("ANTHROPIC_AUTH_TOKEN", "")
}

// Live test that exercises the real Claude provider. Auto-skips when credentials are absent.
val claudeIntegrationTest by tasks.registering(Test::class) {
    description = "Runs the live Claude analysis integration test (requires ANTHROPIC_API_KEY)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("claude")
    }
}

// Keep `check`/`build` fast and credential-free: run the e2e, not the live Claude test.
tasks.named("test") {
    enabled = false
}
tasks.named("check") {
    dependsOn(integrationTest)
}
