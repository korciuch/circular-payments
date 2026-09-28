plugins {
    application
}

dependencies {
    implementation(project(":payments"))

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.circular.tools.BackfillToolKt")
}
