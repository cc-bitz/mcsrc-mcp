plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Runs in a child JVM whose classpath is exactly [mache's Vineflower jar, this jar]: mache decompiles
// with --include-classpath=true, so every class on that classpath becomes decompile context, and a
// dependency here would change the output Paper's patches are written against. Vineflower itself is
// compileOnly for the same reason - the child gets whichever version mache.json names.

dependencies {
    compileOnly("org.vineflower:vineflower:1.12.0")

    testImplementation("org.vineflower:vineflower:1.12.0")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
