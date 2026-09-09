plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api("org.ow2.asm:asm:9.9.1")
    api("org.ow2.asm:asm-commons:9.9.1")
    api("org.ow2.asm:asm-util:9.9.1")
    api("net.fabricmc:mapping-io:0.8.0")
    api("net.fabricmc:mapping-io-extras:0.8.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
