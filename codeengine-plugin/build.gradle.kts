plugins { `java-library` }
dependencies {
    implementation(project(":codeengine-api"))
    implementation(project(":codeengine-compiler"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
val webIdeDirectory = rootProject.file("codeengine-webide")
val npmExecutable = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val installWebIde by tasks.registering(Exec::class) {
    workingDir(webIdeDirectory)
    commandLine(npmExecutable, "ci", "--no-audit", "--no-fund")
    inputs.files(webIdeDirectory.resolve("package.json"), webIdeDirectory.resolve("package-lock.json"))
    outputs.dir(webIdeDirectory.resolve("node_modules"))
}
val buildWebIde by tasks.registering(Exec::class) {
    dependsOn(installWebIde)
    workingDir(webIdeDirectory)
    commandLine(npmExecutable, "run", "build")
    inputs.dir(webIdeDirectory.resolve("src"))
    inputs.dir(webIdeDirectory.resolve("scripts"))
    inputs.dir(webIdeDirectory.resolve("tests"))
    inputs.file(webIdeDirectory.resolve("playwright.config.ts"))
    inputs.files(webIdeDirectory.resolve("index.html"), webIdeDirectory.resolve("tsconfig.json"),
        webIdeDirectory.resolve("package.json"), webIdeDirectory.resolve("package-lock.json"))
    outputs.dir(webIdeDirectory.resolve("dist"))
}
val testWebIde by tasks.registering(Exec::class) {
    dependsOn(installWebIde)
    workingDir(webIdeDirectory)
    commandLine(npmExecutable, "test")
    inputs.dir(webIdeDirectory.resolve("src"))
    inputs.dir(webIdeDirectory.resolve("tests"))
}
tasks.named("check") { dependsOn(testWebIde) }
tasks.processResources {
    dependsOn(buildWebIde)
    from(webIdeDirectory.resolve("dist")) { into("webide") }
    from(rootProject.file("examples/hello.ce")) { into("examples") }
}
tasks.jar {
    dependsOn(":codeengine-api:classes", ":codeengine-compiler:classes")
    from(project(":codeengine-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":codeengine-compiler").extensions.getByType<SourceSetContainer>()["main"].output)
}
