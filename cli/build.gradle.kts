plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":core"))
    api(project(":coverage"))
    api(project(":mutation"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
