plugins {
    kotlin("jvm")
}

dependencies {
    // The mutation tester reuses the coverage pipeline (Gradle runner, JaCoCo/Kover
    // parser, coverage index) and core's parser, walker and report models.
    api(project(":coverage"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    // SampleAppEndToEndTest runs mutate against a temporary copy of the sample app.
    val sampleApp = rootProject.layout.projectDirectory.dir("sample-apps/todolist")
    systemProperty("slopguard.sampleApp", sampleApp.asFile.absolutePath)
    inputs.files(sampleApp.asFileTree.matching { exclude("build/**", ".gradle/**") })
        .withPropertyName("sampleApp")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
