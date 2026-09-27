plugins { `java-library` }
dependencies {
    compileOnly(project(":codeengine-plugin"))
    compileOnly(project(":codeengine-api"))
    compileOnly(project(":codeengine-compiler"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
