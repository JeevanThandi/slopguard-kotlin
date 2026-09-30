plugins {
    kotlin("jvm") version "1.9.24" apply false
    // Kover instruments the project's own tests so slopguard-kotlin can dogfood
    // itself with real coverage. The root project hosts the aggregated report.
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

allprojects {
    group = "dev.slopguard"
    version = "0.2.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jetbrains.kotlinx.kover")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            sourceCompatibility = "17"
            targetCompatibility = "17"
        }
    }
}

// Aggregate every module into the root koverXmlReport (build/reports/kover/report.xml).
dependencies {
    add("kover", project(":core"))
    add("kover", project(":coverage"))
    add("kover", project(":mutation"))
    add("kover", project(":cli"))
    add("kover", project(":app"))
}
