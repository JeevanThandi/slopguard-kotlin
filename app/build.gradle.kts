plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":cli"))
}

application {
    mainClass.set("dev.slopguard.app.MainKt")
    applicationName = "slopguard-kotlin"
}
