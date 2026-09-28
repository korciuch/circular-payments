dependencies {
    // Money, AuditLogger, RedactingLogger and TransferService are the shared
    // primitives every money movement in this repository is built on.
    implementation(project(":payments"))

    implementation("org.springframework.boot:spring-boot-starter-web:3.4.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.1")
    testImplementation(kotlin("test"))
}
