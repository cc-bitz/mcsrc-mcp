plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":cache"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("io.github.oshai:kotlin-logging:8.0.4")
    implementation("org.slf4j:slf4j-simple:2.0.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.vineflower:vineflower:1.12.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.ccbitz.mcsrcmcp.server.MainKt")

    // Without an explicit ceiling the JVM takes the default max heap of 1/4 of physical RAM - 15GB
    // on a 64GB machine - so G1 has no reason to collect early or hand memory back, and a server
    // that needed 1.5GB once keeps that resident for the life of the process (measured: 363MB live,
    // 1.44GB committed, 1.4GB RSS). 1500m is comfortably above a cold build of the largest version
    // with the default three warm workspaces.
    //
    // The periodic GC is what actually returns memory: an MCP server spends nearly all its time
    // idle between tool calls, and without G1PeriodicGCInterval an idle heap is never uncommitted.
    // SystemLoadThreshold=0 disables the load check, so "idle" means idle, not "machine is idle".
    //
    // These are defaults, not a straitjacket - the generated start scripts append JAVA_OPTS after
    // them, so a later -Xmx there wins.
    applicationDefaultJvmArgs = listOf(
        "-Xmx1500m",
        "-XX:+UseG1GC",
        "-XX:G1PeriodicGCInterval=60000",
        "-XX:G1PeriodicGCSystemLoadThreshold=0",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=25",
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The sidecar bridge ships as a jar-in-jar: BridgeMain must run on the game's JDK against only
// Minecraft classes, so it stays a separate artifact (no dependencies, bytecode 21) that this
// server extracts beside its cache at runtime and appends to the game classpath.
tasks.processResources {
    from(project(":bridge").tasks.named("jar")) {
        rename { "bridge.jar" }
    }

    // The core sources ported from FabricMC/mcsrc are MIT, and MIT wants its notice to travel with
    // every substantial portion it ships in - which includes the fat jar. Routing the files through
    // processResources rather than adding them to fatJar directly covers both artifacts at once,
    // since fatJar already pulls in sourceSets.main.output.
    //
    // Scoped to META-INF/mcsrc-mcp/ rather than the conventional META-INF/LICENSE: fatJar merges
    // every dependency under DuplicatesStrategy.EXCLUDE, so a shared path goes to whichever copy
    // lands first. Ours would win and silently drop the dependencies' own license files, which
    // Apache-2.0 §4(d) requires us to keep. A project-scoped path can't collide with anything.
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF/mcsrc-mcp")
    }
    from(rootProject.layout.projectDirectory.file("NOTICE.md")) {
        into("META-INF/mcsrc-mcp")
    }
}

// One-file build for handing the server to someone else: the runtime classpath folded into a
// single jar, so a distribution is a jar plus a launcher rather than 43 loose files. No two
// dependencies declare the same META-INF/services interface (checked), so first-wins duplicate
// handling loses no service registrations. The signatures of the signed mapping-io jars have to
// go: they describe those jars, not this merged one, and the JVM rejects a jar whose signatures
// no longer match its contents.
//
// The JVM tuning in application{} above rides on the generated start scripts, not on the jar, so
// bin/mcsrc-mcp.bat passes the same flags for a `java -jar` launch.
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")

    // The zipTree below reads the project jars (core/cache) straight out of the runtime
    // classpath, so the tasks producing them have to run first or this validation error
    // fires and, worse, an up-to-date path could zip a stale jar.
    dependsOn(configurations.runtimeClasspath)

    manifest {
        attributes["Main-Class"] = "io.github.ccbitz.mcsrcmcp.server.MainKt"
    }

    from(sourceSets.main.get().output)

    val runtimeClasspath = configurations.runtimeClasspath
    from({ runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}

// The handout build: the fat jar plus its launcher, named and placed so a distribution is two
// files someone can copy anywhere. Sync rather than Copy, so renaming or dropping an input here
// clears the old file out instead of leaving a stale one behind for someone to ship by accident.
//
// The launcher lives in src/launcher rather than src/dist: the application plugin folds src/dist
// into distZip/distTar automatically, and this launcher looks for mcsrc-mcp.jar beside itself,
// which only exists in this layout - shipping it inside the lib/-based zip would hand someone a
// script that cannot find its jar.
tasks.register<Sync>("dist") {
    group = "distribution"
    description = "Builds the two-file distribution (fat jar + Windows launcher) into dist/."

    into(rootProject.layout.projectDirectory.dir("dist"))
    from(tasks.named<Jar>("fatJar")) {
        rename { "mcsrc-mcp.jar" }
    }
    from(layout.projectDirectory.file("src/launcher/mcsrc-mcp.bat"))
}
