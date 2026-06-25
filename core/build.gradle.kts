plugins {
    kotlin("jvm")
}

dependencies {
    // The only third-party runtime dependency: the Kotlin compiler's PSI parser,
    // used purely to build a syntax tree. Analogous to slopguard-swift's SwiftSyntax
    // and slopguard-typescript's `typescript` compiler API.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.24")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
