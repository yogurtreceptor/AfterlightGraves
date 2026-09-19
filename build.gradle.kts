plugins {
    java
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.8-alpha")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.jar {
    archiveBaseName = "AfterlightGraves"
    manifest {
        attributes["Implementation-Title"] = "AfterlightGraves"
        attributes["Implementation-Version"] = project.version
    }
}
