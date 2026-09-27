plugins { `java-library` }
dependencies {
    implementation(project(":codeengine-api"))
    implementation(project(":codeengine-compiler"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
tasks.processResources {
    from(rootProject.file("codeengine-webide")) { into("webide"); exclude("tests/**", "package*.json") }
    from(rootProject.file("examples/hello.ce")) { into("examples") }
}
tasks.jar {
    dependsOn(":codeengine-api:classes", ":codeengine-compiler:classes")
    from(project(":codeengine-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":codeengine-compiler").extensions.getByType<SourceSetContainer>()["main"].output)
}
