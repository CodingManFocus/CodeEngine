plugins { `java-library` }
repositories { maven("https://repo.extendedclip.com/releases/") }
dependencies {
    compileOnly(project(":codeengine-plugin"))
    compileOnly(project(":codeengine-api"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    if (providers.gradleProperty("placeholderApiJar").isPresent) {
        compileOnly(files(providers.gradleProperty("placeholderApiJar").get()))
    } else {
        compileOnly("me.clip:placeholderapi:2.11.6")
    }
}
