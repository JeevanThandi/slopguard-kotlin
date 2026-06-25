plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
