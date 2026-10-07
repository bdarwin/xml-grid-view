import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.xmlgridview"
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    // Required by the platform test framework when running JUnit 4 tests.
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.xmlgridview.viewer"
        name = "XML Grid View"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }
    test {
        // Shared golden fixtures live at the repository root (override with -PfixturesDir=...).
        val fixturesDir = providers.gradleProperty("fixturesDir").map { file(it) }.orElse(rootProject.layout.projectDirectory.dir("../../fixtures").asFile).get()
        systemProperty("xmlgridview.fixtures", fixturesDir.absolutePath)
        inputs.dir(fixturesDir.resolve("cases")).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
