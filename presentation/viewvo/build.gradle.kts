plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

version = "0.0.1"
val embeddedEnabled = providers.gradleProperty("embedded.enabled")
    .map(String::toBoolean)
    .orElse(false)
    .get()

/**
 * Turns the Natural Earth geojson under `worlddata/` into Kotlin source.
 *
 * The data has to reach Kotlin/Native `linuxArm64`, where there is no resource mechanism at all:
 * Compose Resources (what `presentation:design` used to read it with) would drag Compose into
 * `presentation:tui`, and a file beside the executable would have to be staged by every deploy.
 * Embedding it as source costs binary size and nothing else.
 *
 * The text is emitted as an array of chunks joined at runtime, never as `const val`: a JVM string
 * constant may not exceed 65535 UTF-8 bytes, and the compiler folds a sum of `const val`s back
 * into one constant.
 */
abstract class GenerateWorldData : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val geojson: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val packageDirectory = outputDirectory.get().asFile.resolve("com/bitchat/viewvo/world")
        packageDirectory.deleteRecursively()
        packageDirectory.mkdirs()
        for (source in geojson.files.sortedBy { it.name }) {
            val stem = source.name.removeSuffix(".geojson") // world_land
            val camel = stem.split("_").joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
            val chunks = source.readText().chunked(CHUNK_CHARACTERS)
            val text = buildString {
                append("// Generated from presentation/viewvo/worlddata/").append(source.name)
                append(" by the generateWorldData task. Do not edit.\n")
                append("package com.bitchat.viewvo.world\n\n")
                append("private val ").append(stem.uppercase()).append("_CHUNKS: Array<String> = arrayOf(\n")
                for (chunk in chunks) append("    \"").append(escape(chunk)).append("\",\n")
                append(")\n\n")
                append("internal fun ").append(camel.replaceFirstChar(Char::lowercase)).append("Json(): String =\n")
                append("    ").append(stem.uppercase()).append("_CHUNKS.joinToString(separator = \"\")\n")
            }
            packageDirectory.resolve(camel + "Json.kt").writeText(text)
        }
    }

    /** [text] as the body of a Kotlin string literal. */
    private fun escape(text: String): String = buildString(text.length) {
        for (character in text) when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }

    private companion object {
        /** Chunked before escaping, so an escape is never split; 20000 ASCII characters escape to well under 65535 bytes. */
        const val CHUNK_CHARACTERS = 20000
    }
}

val generateWorldData = tasks.register<GenerateWorldData>("generateWorldData") {
    geojson.from(
        layout.projectDirectory.file("worlddata/world_land.geojson"),
        layout.projectDirectory.file("worlddata/world_borders.geojson"),
        layout.projectDirectory.file("worlddata/world_cities.geojson"),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/worlddata/commonMain/kotlin"))
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()

    if (embeddedEnabled) {
        linuxArm64()
    }
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            binaryOption("bundleId", "viewvo")
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generateWorldData)
            dependencies {
                implementation(project(":domain"))
                implementation(libs.kotlinx.serialization)
            }
        }
        val commonTest by getting
        val jvmMain by getting {
            dependencies {
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }

        val iosMain by getting
        val iosTest by getting
    }
}
