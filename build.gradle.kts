plugins {
    java
}

group = "com.nxenon"
version = "2.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Provided by Burp at runtime, so it must not be bundled into the jar.
    compileOnly("net.portswigger.burp.extensions:montoya-api:2025.5")

    // Bundled: the BApp Store requires an extension to ship its own dependencies so that
    // installation is one click and cannot collide with another extension's versions.
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Tests compile against the API to exercise the editor classes with fake request/responses.
    testImplementation("net.portswigger.burp.extensions:montoya-api:2025.5")
    // Used only to stand in for Burp when checking that the extension registers cleanly.
    testImplementation("org.mockito:mockito-core:5.14.2")
}

java {
    // Burp Suite runs on Java 17 or later; targeting 17 keeps the extension loadable on the
    // oldest supported Burp release rather than only on whatever JDK built it.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    // The UI smoke test builds Swing components; there is no display on a build machine.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Builds the single jar loaded into Burp, with protobuf-java and gson inside it.
 *
 * Burp gives every extension its own classloader, so the bundled libraries cannot clash with
 * another extension's copies and do not need relocating.
 */
val fatJar = tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles the Burp extension jar with all bundled dependencies."
    archiveBaseName.set("grpc-web-coder")
    archiveClassifier.set("all")
    // No version in the filename: BappManifest.bmf points at this path, and a versioned name
    // would mean editing the manifest on every release.
    archiveVersion.set("")

    manifest {
        attributes(
            "Implementation-Title" to "gRPC-Web Coder",
            "Implementation-Version" to project.version,
        )
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        // Signature files and module descriptors from the bundled jars are meaningless once
        // merged, and a stale signature will make the JVM reject the jar outright.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
    }
}

tasks.named("build") {
    dependsOn(fatJar)
}

// Only the fat jar is a loadable extension. Leaving the thin jar in build/libs invites someone to
// load it into Burp, where it fails at runtime with NoClassDefFoundError for the bundled libraries.
tasks.jar {
    enabled = false
}
