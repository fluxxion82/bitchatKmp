import java.security.MessageDigest

// ---------------------------------------------------------------------------
// Sidecar for an embedded executable, applied by :apps:embedded and
// :apps:embedded-tui. Set `extra["embeddedBinaryBaseName"]` to the executable's
// base name before applying. Every link of that executable writes
// <base name>.build-info next to the binary: name=<base name>, the raw metadata
// :apps:embedded-common generates, build=<debug|release> (from the binary's
// debuggable flag, see below), kexe_sha256=<sha256 of the executable> and
// identity=<line>. The identity line must be byte-for-byte what
// BuildIdentity(name).line prints (`<name> <version> (<sha12>, <branch>,
// clean|dirty, <build>, built <built_at>)`): scripts/deploy-pi.sh reads the
// sidecar, checks the executable's SHA-256 against it, and requires `--version`
// on the device to print exactly that line. Keep the two formats in sync.
// ---------------------------------------------------------------------------
val baseName = extra["embeddedBinaryBaseName"] as String
val buildInfoProject = project(":apps:embedded-common")

// A script plugin cannot see the Kotlin Gradle plugin's classes (they live in the applying build
// script's class loader), so its task and binary types are loaded from the plugin's own loader
// and read reflectively: KotlinNativeLink.binary / destinationDirectory, Executable.baseName /
// debuggable.
val kgp = plugins.getPlugin("org.jetbrains.kotlin.multiplatform").javaClass.classLoader
@Suppress("UNCHECKED_CAST")
val linkType = kgp.loadClass("org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink") as Class<Task>
val executableType = kgp.loadClass("org.jetbrains.kotlin.gradle.plugin.mpp.Executable")

tasks.withType(linkType).configureEach {
    // `binary` is @Transient (configuration phase only); KGP also registers a test
    // binary (TestExecutable, baseName "test"), which must not get a sidecar.
    val nativeBinary = linkType.getMethod("getBinary").invoke(this)
    if (!executableType.isInstance(nativeBinary) ||
        nativeBinary.javaClass.getMethod("getBaseName").invoke(nativeBinary) != baseName
    ) {
        return@configureEach
    }
    // build= follows the linker's -g flag (NativeBinary.debuggable), which is exactly what
    // Platform.isDebugBinary reports at runtime in BuildIdentity.kt. It defaults from the
    // build type but can be overridden in the DSL, so buildType.name could disagree with
    // the binary. Read at configuration time into a String (CC safe).
    val debuggable = nativeBinary.javaClass.getMethod("getDebuggable").invoke(nativeBinary) as Boolean
    val buildType = if (debuggable) "debug" else "release"
    val name = baseName
    val destination = linkType.getMethod("getDestinationDirectory").invoke(this) as DirectoryProperty
    val props = buildInfoProject.layout.buildDirectory.file("generated/embeddedBuildInfo/build-info.properties")
    val kexe = destination.file("$name.kexe")
    val sidecar = destination.file("$name.build-info")
    dependsOn(":apps:embedded-common:generateEmbeddedBuildInfo")
    inputs.file(props).withPropertyName("embeddedBuildInfo").withPathSensitivity(PathSensitivity.NONE)
    outputs.file(sidecar).withPropertyName("embeddedBuildInfoSidecar")
    doLast {
        val lines = listOf("name=$name") + props.get().asFile.readLines().filter { it.isNotBlank() }
        val values = lines.associate { line ->
            val eq = line.indexOf('=')
            require(eq > 0) { "malformed build-info.properties line: $line" }
            line.substring(0, eq) to line.substring(eq + 1)
        }
        val kexeFile = kexe.get().asFile
        require(kexeFile.isFile) { "expected linked executable at $kexeFile" }
        val digest = MessageDigest.getInstance("SHA-256")
        kexeFile.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val kexeSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val identity = buildString {
            append(values.getValue("name")).append(' ').append(values.getValue("version"))
            append(" (").append(values.getValue("git_sha").take(12))
            append(", ").append(values.getValue("git_branch"))
            append(", ").append(if (values.getValue("git_dirty").toBoolean()) "dirty" else "clean")
            append(", ").append(buildType)
            append(", built ").append(values.getValue("built_at"))
            append(')')
        }
        sidecar.get().asFile.writeText(
            (lines + listOf("build=$buildType", "kexe_sha256=$kexeSha256", "identity=$identity"))
                .joinToString("\n", postfix = "\n")
        )
    }
}
