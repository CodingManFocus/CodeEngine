import java.net.URLClassLoader
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.6"
}
dependencies {
    implementation(project(":codeengine-api"))
    implementation(project(":codeengine-compiler"))
    implementation("org.apache.maven:maven-resolver-provider:3.9.9")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.22")
    implementation("org.apache.maven.resolver:maven-resolver-transport-file:1.9.22")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
// Keep every upstream notice independently; flat META-INF merges alone lose provenance.
val dependencyNoticesDirectory = layout.buildDirectory.dir("generated/dependency-notices")
val collectDependencyNotices by tasks.registering {
    val runtimeJars = configurations.runtimeClasspath
    inputs.files(runtimeJars)
    outputs.dir(dependencyNoticesDirectory)
    doLast {
        val output = dependencyNoticesDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        runtimeJars.get().files.filter { it.extension == "jar" }.forEach { artifact ->
            ZipFile(artifact).use { jar ->
                jar.entries().asSequence().filter { entry ->
                    val name = entry.name.substringAfterLast('/').uppercase()
                    !entry.isDirectory && (name.startsWith("LICENSE") || name.startsWith("NOTICE") || name == "DEPENDENCIES")
                }.forEach { entry ->
                    val name = entry.name.replace('/', '_').replace('\\', '_')
                    val target = output.resolve("META-INF/codeengine-dependencies/${artifact.nameWithoutExtension}/$name")
                    target.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
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
    dependsOn(buildWebIde, ":codeengine-api:jar", collectDependencyNotices)
    from(dependencyNoticesDirectory)
    from(project(":codeengine-api").tasks.named<Jar>("jar")) {
        into("intelligence")
        rename { "codeengine-api.bin" }
    }
    from(webIdeDirectory.resolve("dist")) { into("webide") }
    from(rootProject.file("examples/hello.ce")) { into("examples") }
}
tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    append("META-INF/LICENSE")
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE")
    append("META-INF/NOTICE.txt")
    relocate("org.apache.maven", "kr.codenamemc.codeengine.internal.maven")
    relocate("org.eclipse.aether", "kr.codenamemc.codeengine.internal.aether")
    relocate("org.codehaus.plexus", "kr.codenamemc.codeengine.internal.plexus")
    relocate("org.apache.http", "kr.codenamemc.codeengine.internal.http")
    relocate("org.apache.commons", "kr.codenamemc.codeengine.internal.commons")
    relocate("org.slf4j", "kr.codenamemc.codeengine.internal.slf4j")
    relocate("javax.inject", "kr.codenamemc.codeengine.internal.inject")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.named("assemble") { dependsOn(tasks.shadowJar) }
tasks.jar {
    enabled = false
    dependsOn(":codeengine-api:classes", ":codeengine-compiler:classes")
    from(project(":codeengine-api").extensions.getByType<SourceSetContainer>()["main"].output)
    from(project(":codeengine-compiler").extensions.getByType<SourceSetContainer>()["main"].output)
}

// Unit tests see loose resources and cannot catch Shadow dropping a nested archive.
val verifyShadowJar by tasks.registering {
    dependsOn(tasks.shadowJar)
    val archive = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(archive)
    doLast {
        val artifact = archive.get().asFile
        ZipFile(artifact).use { jar ->
            val embedded = requireNotNull(jar.getEntry("intelligence/codeengine-api.bin")) {
                "The packaged plugin is missing the embedded Code Engine API JAR."
            }
            JarInputStream(jar.getInputStream(embedded)).use { api ->
                check(generateSequence { api.nextJarEntry }.any {
                    it.name == "kr/codenamemc/codeengine/api/CodeModule.class"
                }) { "The packaged Code Engine API resource is not a valid API JAR." }
            }
            listOf("webide/index.html", "webide/intelligence-worker.js").forEach { resource ->
                check(jar.getEntry(resource) != null) { "The packaged plugin is missing $resource." }
            }
            check(jar.entries().asSequence().any {
                !it.isDirectory && it.name.startsWith("META-INF/codeengine-dependencies/")
            }) { "The packaged plugin is missing dependency notices." }
        }
        URLClassLoader(arrayOf(artifact.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previousLoader = thread.contextClassLoader
            try {
                thread.contextClassLoader = loader
                val resolver = loader.loadClass("kr.codenamemc.codeengine.intelligence.MavenArtifactResolver")
                val factory = resolver.getDeclaredMethod("newRepositorySystem")
                factory.isAccessible = true
                val system = factory.invoke(null)
                val repositorySystem = loader.loadClass("kr.codenamemc.codeengine.internal.aether.RepositorySystem")
                repositorySystem.getMethod("shutdown").invoke(system)
            } finally {
                thread.contextClassLoader = previousLoader
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyShadowJar) }
