plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Deliberately no dependencies: BridgeMain runs on the game's own JDK with nothing but the
// Minecraft client jar and its libraries on the classpath, so everything it needs is reflection
// and hand-rolled JSON writing. (Its bytecode targets 21, which every Java the game accepts also
// accepts; the game's floor is 21 and only climbs.)

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
