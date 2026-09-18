plugins {
    `java-library`
}

group = "moe.vitamin.minecraft.mcp"
version = "3.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.opencollab.dev/maven-releases/") { name = "opencollab-releases" }
    maven("https://repo.opencollab.dev/maven-snapshots/") { name = "opencollab-snapshots" }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

// So a jar can say which build it is without the number being written down a second time in Java,
// where it goes stale silently. ShadowJar is a Jar, so this reaches the shipped artifacts too.
tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
