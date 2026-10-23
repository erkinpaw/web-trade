import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.purpurmc.org/snapshots")
}

/** Сборка Purpur, под которую пишем и на которой гоняем тестовый сервер. */
val minecraftVersion = "26.3"
val purpurBuild = "2654"

dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:$minecraftVersion.build.$purpurBuild-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(26)
}

/**
 * run-paper умеет скачивать только Paper и Folia, поэтому ядро Purpur берём сами
 * с их официального API и подсовываем runServer готовым файлом.
 */
abstract class DownloadServerJar : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    @TaskAction
    fun download() {
        URI(url.get()).toURL().openStream().use { input ->
            Files.copy(input, jar.get().asFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val downloadPurpur = tasks.register<DownloadServerJar>("downloadPurpur") {
    url = "https://api.purpurmc.org/v2/purpur/$minecraftVersion/$purpurBuild/download"
    jar = layout.buildDirectory.file("purpur/purpur-$minecraftVersion-$purpurBuild.jar")
}

tasks {
    runServer {
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        // Версию run-paper требует даже при своём ядре.
        minecraftVersion(minecraftVersion)
        serverJar(downloadPurpur.flatMap { it.jar })
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
