package com.cosmos.unreddit.util.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the 2.5.79–2.5.82 "frosted trap".
 *
 * `error { … }` inside `ImageView.load`'s frosted branch never resolved to Coil:
 * Coil 2.2.2's `ImageRequest.Builder` declares only `error(Int)` and
 * `error(Drawable?)` (verified in the published AAR). The bare `error { … }`
 * call instead bound to the KOTLIN STDLIB built-in `kotlin.error(message: Any?)`,
 * whose definition is `throw IllegalStateException(message.toString())`. The
 * compiler therefore emitted an UNCONDITIONAL `new java/lang/IllegalStateException;
 * athrow` into the frosted branch of `load` — every frosted (spoiler,
 * `blurUrl != null`) bind threw inside `onBindViewHolder`, mid layout pass, which
 * leaked RecyclerView 1.2.1's `mLayoutOrScrollCounter` (no try/finally around
 * `onLayoutChildren`) and turned the next paging page-insert into "Cannot call
 * this method while RecyclerView is computing a layout or scrolling". The
 * compiler emits no warning for this silent re-resolution.
 *
 * This test disassembles the COMPILED `ViewExtKt.class` (the exact bytes that
 * will be R8-processed and shipped) with the JDK's own `javap` and asserts, at
 * the bytecode level:
 *  1. no `load`/`load$default` method ever constructs an
 *     `IllegalStateException` (the frosted path cannot throw — the original bug),
 *     and
 *  2. at least one `load` method references Coil's real error-callback API
 *     (`coil.request.ImageRequest$Builder.listener…`), so the guard cannot be
 *     satisfied by simply deleting the error handling.
 *
 * It goes red against the buggy 2.5.79–2.5.82 source and green once the frosted
 * branch uses `listener(onError = …)`.
 */
class ViewExtLoadGuardTest {

    /** Disassembles [path] with the JDK's own javap and returns the text output. */
    private fun disassemble(path: String): String {
        val javap = File(System.getProperty("java.home"), "bin${File.separator}javap")
        assertTrue(
            "javap not found at $javap — run this test with a full JDK on java.home",
            javap.canExecute()
        )
        val proc = ProcessBuilder(javap.absolutePath, "-p", "-c", path)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        assertTrue("javap failed (exit $exit):\n$out", exit == 0)
        return out
    }

    private fun loadViewExtKtFile(): File {
        // Locate the COMPILED ViewExtKt facade (the bytes that get R8-processed and
        // shipped). Two robust sources, in order:
        //  1) The test's OWN classloader — in a unit test it is the same classloader
        //     that loaded the app under test, so it sees ViewExtKt.class however AGP
        //     lays the app classes out (a directory or a jar, debug or release). This
        //     is variant-agnostic, unlike a hardcoded build-output path.
        //  2) A filesystem search up from the working directory, as a fallback for
        //     the (rare) case the class is not resolvable as a resource.
        val rel = "com/cosmos/unreddit/util/extension/ViewExtKt.class"
        val url = ViewExtLoadGuardTest::class.java.classLoader?.getResource(rel)
        if (url != null) {
            val f = File.createTempFile("viewext", ".class")
            f.deleteOnExit()
            url.openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            return f
        }
        for (variant in listOf("debug", "release")) {
            var dir: File? = File(System.getProperty("user.dir"))
            var hops = 0
            while (dir != null && hops < 4) {
                val file = File(dir, "build/tmp/kotlin-classes/$variant/$rel")
                if (file.isFile) return file
                dir = dir.parentFile
                hops++
            }
        }
        org.junit.Assert.fail("ViewExtKt.class not found via the test classloader or above ${System.getProperty("user.dir")}")
        throw IllegalStateException("unreachable")
    }

    /** Returns the disassembled Code sections for every method named [prefix]. */
    private fun methodSections(disasm: String, namePrefix: String): String {
        // javap -c layout: a method begins with a 2-space-indented signature line
        // ("  public …;"), code lines are indented deeper ("      0: …").
        val lines = disasm.lines()
        val sb = StringBuilder()
        var inSection = false
        for (line in lines) {
            val isMethodHeader = line.startsWith("  ") && line[2].isLetterOrDigit() &&
                line.contains("(") && line.endsWith(");")
            if (isMethodHeader) {
                inSection = line.contains(" $namePrefix(") || line.contains(" $namePrefix\$")
                if (inSection) sb.appendLine(line)
                continue
            }
            if (inSection) sb.appendLine(line)
        }
        return sb.toString()
    }

    /** Splits a javap code line "      211: new  #186 // class …" into (opcode, rest). */
    private fun splitOpcode(line: String): Pair<String, String>? {
        val m = Regex("^\\s*(\\d+):\\s*(\\S+)\\s*(.*)$").find(line) ?: return null
        return m.groupValues[2] to m.groupValues[3]
    }

    @Test
    fun `frosted path cannot throw - load never constructs an IllegalStateException`() {
        val disasm = disassemble(loadViewExtKtFile().absolutePath)
        val loads = methodSections(disasm, "load")
        assertTrue(
            "no 'load' method found in the compiled ViewExtKt — " +
                "the guard cannot verify the frosted branch\n$disasm",
            loads.isNotEmpty()
        )
        val offenders = loads.lineSequence()
            .mapNotNull { splitOpcode(it) }
            .filter { (op, rest) -> op == "new" && rest.contains("IllegalStateException") }
            .map { it.first }
            .toList()
        assertTrue(
            "load constructs java/lang/IllegalStateException — the frosted branch " +
                "still has the 'kotlin.error' trap:\n${offenders.joinToString("\n")}",
            offenders.isEmpty()
        )
    }

    @Test
    fun `frosted path wires Coil real error callback - load references Builder listener`() {
        val disasm = disassemble(loadViewExtKtFile().absolutePath)
        val loads = methodSections(disasm, "load")
        assertTrue(
            "no 'load' method found in the compiled ViewExtKt\n$disasm",
            loads.isNotEmpty()
        )
        // javap renders the receiver as coil/request/ImageRequest$Builder; the real
        // Coil 2.2.2 error callback is listener(...) / listener$default(...) — never
        // a bare `error(Function1)`.
        val wired = loads.lineSequence()
            .mapNotNull { splitOpcode(it) }
            .any { (op, rest) -> op.startsWith("invoke") &&
                rest.contains("coil/request/ImageRequest") && rest.contains(".listener") }
        assertTrue(
            "no load method invokes coil.request.ImageRequest.Builder.listener… — " +
                "the frosted branch must fall back through Coil's real error callback",
            wired
        )
    }
}
