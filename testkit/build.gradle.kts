plugins {
    id("vitaminmcp.java-conventions")
    id("vitaminmcp.module-rules")
}

dependencies {
    api(project(":contract"))
    implementation(project(":bot-core"))
    implementation(project(":orchestrator"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

tasks.test {

    listOf(
        "vitaminmcp.liveServer",
        "vitaminmcp.host",
        "vitaminmcp.port",
        "vitaminmcp.mcpPort",
        "vitaminmcp.token",
        "vitaminmcp.agentJar",
        "vitaminmcp.runnerJar",
        "vitaminmcp.version",
        "vitaminmcp.paperBuild",
        "vitaminmcp.protocol",
        "vitaminmcp.paperCache",
        "vitaminmcp.repeat",
        "vitaminmcp.serverJavaHome",
    ).forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}
