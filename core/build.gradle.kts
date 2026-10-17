plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api("org.ow2.asm:asm:9.11")
    api("org.ow2.asm:asm-commons:9.11")
    api("org.ow2.asm:asm-util:9.11")
    api("net.fabricmc:mapping-io:0.9.1")
    api("net.fabricmc:mapping-io-extras:0.9.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
