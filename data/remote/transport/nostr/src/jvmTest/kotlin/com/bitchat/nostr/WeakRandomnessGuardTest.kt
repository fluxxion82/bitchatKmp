package com.bitchat.nostr

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fails when code that creates secrets can reach a random number generator that is not a CSPRNG.
 *
 * Shape assertions cannot do this job. A weak PRNG produces keys of the right length that differ
 * from one another, so the suite stayed green when two independent reviews swapped the JVM
 * `SecureRandom` for `kotlin.random.Random`, and the Nostr device seed - the root secret of every
 * geohash identity - was in fact minted with `kotlin.random.Random` until this test existed. The
 * only thing that catches it is refusing to let the code reach one.
 *
 * It scans every production source set (`src/<name>Main`) of the two modules that create Nostr
 * secrets: `:data:crypto`, which generates every key, and this module, which mints the device
 * seed. All platforms are covered from here, because it reads files rather than classes. It
 * lives in this module because this is the lowest one that can see both.
 *
 * `CsprngProvenanceTest` (crypto) and `DeviceSeedProvenanceTest` (here) are the other half: they
 * show that the bytes really come from the platform CSPRNG, which a scan cannot.
 */
class WeakRandomnessGuardTest {

    @Test
    fun `secret-generating sources reach no non-cryptographic random number generator`() {
        val findings = scannedSources().flatMap(::findingsIn).filterNot(::isExempt)

        assertTrue(
            findings.isEmpty(),
            findings.joinToString(
                separator = "\n",
                prefix = "Non-cryptographic randomness in code that creates secrets:\n",
                postfix = "\n\nDraw secret bytes from Cryptography.secureRandomBytes (or, inside " +
                    "Cryptography, from the platform CSPRNG it already uses). If this use really " +
                    "creates nothing secret, add an Exemption naming the file, the rule and why.",
            ) { "  ${it.path}:${it.line}: [${it.rule.id}] ${it.text}\n    ${it.rule.why}" },
        )
    }

    @Test
    fun `every exemption is still needed`() {
        // A stale exemption is a hole waiting for the next weak generator in that file. This is
        // also the positive control on real input: these files do contain a weak generator, so
        // if the scanner reports nothing here it has stopped working, not got clean.
        val findings = scannedSources().flatMap(::findingsIn)
        for (exemption in EXEMPTIONS) {
            assertTrue(
                findings.any { it.path == exemption.path && it.rule.id == exemption.rule },
                "Exemption for ${exemption.path} [${exemption.rule}] matches nothing; remove it",
            )
        }
    }

    @Test
    fun `the scan covers the files that create secrets`() {
        val scanned = scannedSources().map { it.path }.toSet()
        val missing = MUST_SCAN.filterNot { it in scanned }

        assertTrue(
            missing.isEmpty(),
            "These files create secrets but were not scanned (moved or renamed?), so the guard " +
                "would pass without looking at them. Update MUST_SCAN:\n  " +
                missing.joinToString("\n  "),
        )
    }

    @Test
    fun `every rule catches what it names`() {
        for ((rule, snippet) in RULE_EXAMPLES) {
            val hits = findingsIn(Source("Example.kt", snippet)).map { it.rule.id }
            assertTrue(rule in hits, "rule '$rule' did not match: $snippet (matched $hits)")
        }
        assertEquals(RULES.map { it.id }.toSet(), RULE_EXAMPLES.map { it.first }.toSet(), "every rule needs an example")
    }

    @Test
    fun `comments are ignored but code beside them is not`() {
        for (snippet in IGNORED) {
            assertEquals(emptyList(), findingsIn(Source("Example.kt", snippet)).map { it.text }, snippet)
        }
        for (snippet in NOT_IGNORED) {
            assertTrue(findingsIn(Source("Example.kt", snippet)).isNotEmpty(), "missed: $snippet")
        }
    }

    @Test
    fun `line numbers survive comment stripping`() {
        val source = "/* one\n two */\n// three\nval x = \"/*\" // four\nimport kotlin.random.Random\n"

        assertEquals(listOf(5), findingsIn(Source("Example.kt", source)).map { it.line })
    }
}

private class Rule(val id: String, val pattern: Regex, val why: String)

private const val LIBC_GENERATORS =
    "rand|srand|random|srandom|rand_r|drand48|erand48|lrand48|nrand48|mrand48|jrand48|srand48"

private val RULES = listOf(
    Rule(
        "kotlin.random",
        Regex("""\bkotlin\.random\b"""),
        "kotlin.random.Random is a fast, predictable PRNG, not a CSPRNG",
    ),
    Rule(
        "java.util.Random",
        Regex("""\bjava\.util\.Random\b"""),
        "java.util.Random is a 48-bit LCG; its output reveals its state",
    ),
    Rule(
        "ThreadLocalRandom",
        Regex("""\b(ThreadLocalRandom|SplittableRandom)\b"""),
        "ThreadLocalRandom and SplittableRandom are fast, predictable PRNGs, not CSPRNGs",
    ),
    Rule("Math.random", Regex("""\bMath\.random\s*\("""), "Math.random() is java.util.Random underneath"),
    Rule(
        "Random()",
        // `Random(seed)` or `Random()` whatever the import, including a java.util.* star import.
        Regex("""(?<![\w.])Random\s*\("""),
        "constructs a non-cryptographic generator",
    ),
    Rule(
        ".random()",
        // The stdlib's collection and range helpers draw from kotlin.random.Random.Default.
        // Uuid.random() is exempt: it is documented to use the platform's secure generator.
        Regex("""(?<!\bUuid)\.(random|randomOrNull|shuffled|shuffle)\s*\("""),
        "stdlib .random()/.shuffled() use kotlin.random.Random.Default",
    ),
    Rule(
        "seeded SecureRandom",
        Regex("""\bsetSeed\s*\(|\bSecureRandom\s*\(\s*[^)\s]"""),
        "seeding a SecureRandom yourself can make its output reproducible",
    ),
    Rule(
        "libc rand",
        Regex("""(?<![\w.])($LIBC_GENERATORS)\s*\(|\bplatform\.posix\.($LIBC_GENERATORS)\b"""),
        "libc generators are not cryptographic",
    ),
)

/** One example per rule that it must catch: proves no pattern has been broken into silence. */
private val RULE_EXAMPLES = listOf(
    "kotlin.random" to "import kotlin.random.Random",
    "kotlin.random" to "val b = kotlin.random.Random.nextBytes(32)",
    "java.util.Random" to "val r = java.util.Random()",
    "ThreadLocalRandom" to "ThreadLocalRandom.current().nextBytes(seed)",
    "ThreadLocalRandom" to "val r = SplittableRandom()",
    "Math.random" to "val d = Math.random()",
    "Random()" to "val r = Random(42)",
    ".random()" to "val b = ByteArray(32) { (0..255).random().toByte() }",
    ".random()" to "val k = alphabet.shuffled().take(8)",
    "seeded SecureRandom" to "rng.setSeed(0L)",
    "seeded SecureRandom" to "val rng = SecureRandom(byteArrayOf(1))",
    "libc rand" to "val r = platform.posix.rand()",
    "libc rand" to "srand(time(null).toUInt()); val r = rand()",
)

/** None of these may produce a finding. */
private val IGNORED = listOf(
    "// import kotlin.random.Random",
    "/** Never kotlin.random.Random here. */",
    "/* outer /* kotlin.random.Random */ still a comment: java.util.Random() */",
    "private fun platformRandom(): SecureRandom = SecureRandom()",
    "val id = Uuid.random().toString()",
    "Cryptography.randomizeTimestampUpToPast(); Cryptography.secureRandomBytes(32)",
    "randombytes_buf(p, n); randomInt(10)",
)

/** Each of these hides real code where a naive comment stripper would see a comment. */
private val NOT_IGNORED = listOf(
    "val url = \"wss://relay\"; val r = kotlin.random.Random.nextInt()",
    "val s = \"/*\"; val r = kotlin.random.Random.nextInt() // */",
    "val s = \"\"\"// raw\"\"\"; import kotlin.random.Random",
    "val s = \"\${f(\"//\")}\"; val r = kotlin.random.Random.nextInt()",
    "val c = '\"'; val r = kotlin.random.Random.nextInt() // \"",
)

/**
 * A file that may use a non-cryptographic generator, and the one rule it is excused from. Name
 * the file and the reason; never exempt a directory.
 */
private class Exemption(val path: String, val rule: String, val why: String)

private val EXEMPTIONS = listOf(
    Exemption(
        path = "data/remote/transport/nostr/src/commonMain/kotlin/com/bitchat/nostr/NostrProofOfWork.kt",
        rule = "kotlin.random",
        why = "NIP-13 proof-of-work only needs a starting nonce that differs between attempts. " +
            "The nonce is published in the event's tags and protects nothing, so predictability " +
            "costs nothing and speed matters.",
    ),
)

private fun isExempt(finding: Finding): Boolean =
    EXEMPTIONS.any { it.path == finding.path && it.rule == finding.rule.id }

/** The modules whose production sources are scanned, relative to the repository root. */
private val SCANNED_MODULES = listOf("data/crypto", "data/remote/transport/nostr")

/**
 * Files that create secrets. If one of these is not among the scanned sources the scan is not
 * looking where it should, and must fail rather than pass on nothing.
 */
private val MUST_SCAN = listOf(
    "data/crypto/src/jvmAndroidMain/kotlin/com/bitchat/crypto/Cryptography.jvmAndroid.kt",
    "data/crypto/src/appleMain/kotlin/com/bitchat/crypto/Cryptography.apple.kt",
    "data/crypto/src/linuxMain/kotlin/com/bitchat/crypto/Cryptography.linux.kt",
    "data/remote/transport/nostr/src/commonMain/kotlin/com/bitchat/nostr/NostrClient.kt",
    "data/remote/transport/nostr/src/commonMain/kotlin/com/bitchat/nostr/model/NostrIdentity.kt",
)

private class Source(val path: String, val text: String)

private class Finding(val path: String, val line: Int, val rule: Rule, val text: String)

private fun findingsIn(source: Source): List<Finding> {
    val code = stripComments(source.text)
    val lines = code.lines()
    return RULES.flatMap { rule ->
        rule.pattern.findAll(code).map { match ->
            val line = code.substring(0, match.range.first).count { it == '\n' } + 1
            Finding(source.path, line, rule, lines[line - 1].trim())
        }
    }.sortedBy { it.line }
}

/**
 * Every `.kt` file in the production source sets of [SCANNED_MODULES]. Fails, rather than
 * returning nothing, when it cannot find them: a scanner that finds no files passes every check.
 */
private fun scannedSources(): List<Source> {
    val root = repositoryRoot()
    val sources = SCANNED_MODULES.flatMap { module ->
        val src = File(root, "$module/src")
        val sourceSets = src.listFiles { f -> f.isDirectory && f.name.endsWith("Main") }.orEmpty()
        if (sourceSets.isEmpty()) fail("No production source sets under $src")

        val files = sourceSets.flatMap { set -> set.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        if (files.isEmpty()) fail("No Kotlin files under the production source sets of $src")
        files.map { Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
    }
    return sources.sortedBy { it.path }
}

/**
 * The checkout root, found from where the test is running rather than assumed: Gradle runs each
 * module's tests in that module's directory, an IDE may use the root, and the class files sit
 * under the module's build directory. `-Dbitchat.repoRoot=...` overrides all of that, and is then
 * trusted exactly - a wrong override fails instead of falling back.
 */
private fun repositoryRoot(): File {
    System.getProperty("bitchat.repoRoot")?.let { override ->
        val dir = File(override).absoluteFile
        if (!isRepositoryRoot(dir)) fail("-Dbitchat.repoRoot=$override is not the bitchatKmp checkout")
        return dir
    }

    val anchors = listOfNotNull(
        File(System.getProperty("user.dir")),
        runCatching { File(WeakRandomnessGuardTest::class.java.protectionDomain.codeSource.location.toURI()) }
            .getOrNull(),
    )
    for (anchor in anchors) {
        generateSequence(anchor.absoluteFile) { it.parentFile }
            .firstOrNull(::isRepositoryRoot)
            ?.let { return it }
    }
    fail(
        "Could not find the bitchatKmp checkout above any of ${anchors.map { it.absolutePath }}. " +
            "Run from inside the repository or pass -Dbitchat.repoRoot=<checkout>.",
    )
}

private fun isRepositoryRoot(dir: File): Boolean =
    File(dir, "settings.gradle.kts").isFile && SCANNED_MODULES.all { File(dir, "$it/build.gradle.kts").isFile }

/**
 * [source] with every comment blanked to spaces and everything else, line breaks included, left
 * where it was, so a match keeps its line number. Strings are tracked only so that a comment
 * opener inside one is not taken for a comment; their contents are still scanned. Handles nested
 * block comments (Kotlin nests them), raw strings and `${...}` templates, which may themselves
 * contain strings.
 */
internal fun stripComments(source: String): String = CommentStripper(source).run()

private class CommentStripper(private val s: String) {
    private val out = StringBuilder(s.length)
    private var i = 0

    fun run(): String {
        code(inTemplate = false)
        return out.toString()
    }

    /** Code up to the end of input or, inside a `${...}` template, up to its closing brace. */
    private fun code(inTemplate: Boolean) {
        var braces = 0
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> lineComment()
                s.startsWith("/*", i) -> blockComment()
                s.startsWith("\"\"\"", i) -> rawString()
                s[i] == '"' -> string()
                s[i] == '\'' -> charLiteral()
                s[i] == '{' -> { braces++; keep() }
                s[i] == '}' && inTemplate && braces == 0 -> { keep(); return }
                s[i] == '}' -> { braces--; keep() }
                else -> keep()
            }
        }
    }

    private fun lineComment() {
        while (i < s.length && s[i] != '\n') blank()
    }

    private fun blockComment() {
        var depth = 0
        while (i < s.length) {
            when {
                s.startsWith("/*", i) -> { depth++; blank(); blank() }
                s.startsWith("*/", i) -> { depth--; blank(); blank(); if (depth == 0) return }
                else -> blank()
            }
        }
    }

    private fun string() {
        keep()
        while (i < s.length) {
            when {
                s[i] == '\\' -> { keep(); if (i < s.length) keep() }
                s[i] == '"' -> { keep(); return }
                s.startsWith("\${", i) -> { keep(); keep(); code(inTemplate = true) }
                s[i] == '\n' -> return // unterminated: let the code scanner carry on
                else -> keep()
            }
        }
    }

    private fun rawString() {
        repeat(3) { keep() }
        while (i < s.length) {
            when {
                s.startsWith("\"\"\"", i) -> {
                    // A raw string may end in extra quotes; only the last three close it.
                    while (s.startsWith("\"\"\"\"", i)) keep()
                    repeat(3) { keep() }
                    return
                }
                s.startsWith("\${", i) -> { keep(); keep(); code(inTemplate = true) }
                else -> keep()
            }
        }
    }

    private fun charLiteral() {
        keep()
        while (i < s.length) {
            when (s[i]) {
                '\\' -> { keep(); if (i < s.length) keep() }
                '\'' -> { keep(); return }
                '\n' -> return
                else -> keep()
            }
        }
    }

    private fun keep() {
        out.append(s[i])
        i++
    }

    private fun blank() {
        out.append(if (s[i] == '\n') '\n' else ' ')
        i++
    }
}
