import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.bdarwin"
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
        id = "io.github.bdarwin.xmlgridview"
        name = "XML Grid View"
        version = project.version.toString()
        vendor {
            name = "Darwin Baisa"
            url = "https://github.com/bdarwin/xml-grid-view"
        }
        description = providers.fileContents(layout.projectDirectory.file("src/main/resources/description.html")).asText
        changeNotes = provider {
            // Notes for the current version, taken from the shared CHANGELOG.md.
            val section = file("../../CHANGELOG.md").readText()
                .substringAfter("## ${project.version}\n", "")
                .substringBefore("\n## ")
            val items = section.trim().split(Regex("\n(?=- )"))
                .filter { it.startsWith("- ") }
                .joinToString("") { "<li>" + it.removePrefix("- ").replace(Regex("\\s+"), " ").trim() + "</li>" }
            if (items.isEmpty()) "" else "<ul>$items</ul>"
        }
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }
    signing {
        // Set in CI from repository secrets; signing is skipped when they are absent.
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
    pluginVerification {
        // One released build per major version since sinceBuild. EAPs are left out: the
        // verifier cannot yet resolve 2026.3's split-out "XML and HTML" plugin
        // (com.intellij.xml), which still provides com.intellij.modules.xml.
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7")
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1.5")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.2.3")
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
        inputs.dir(fixturesDir.resolve("json")).withPropertyName("jsonFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// Dev tool: `./gradlew runIdeForScreenshots` starts a sandbox IDE on screenshots/project,
// and screenshots/screenshots.groovy (an IDE startup script) paints the Grid and Flat tabs
// to build/screenshots/*.png. Not part of the plugin.
val runIdeForScreenshots by intellijPlatformTesting.runIde.registering {
    task {
        val shotsOut = layout.buildDirectory.dir("screenshots").get().asFile
        val project = file("screenshots/project")
        jvmArgs(
            "-Dxgv.shots.out=${shotsOut.absolutePath}",
            "-Dxgv.shots.file=${project.resolve("books.xml").absolutePath}",
            "-Didea.trust.all.projects=true",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Didea.is.internal=false",
        )
        args(project.absolutePath)
        val script = file("screenshots/screenshots.groovy")
        val configDir = sandboxConfigDirectory
        doFirst {
            shotsOut.deleteRecursively()
            val startup = configDir.get().asFile.resolve("extensions/com.intellij/startup")
            startup.mkdirs()
            script.copyTo(startup.resolve("screenshots.groovy"), overwrite = true)
        }
    }
}
