plugins {
    kotlin("jvm") version "1.9.24"
    // Kover is slopguard-kotlin's default coverage tool. koverXmlReport emits
    // build/reports/kover/report.xml with XML enabled out of the box.
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
