package com.savedbylight.appcloner

import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Patches literal String constants embedded in dex bytecode that hold the app's OWN
 * (pre-clone) package name — the class of bug behind crashes like:
 *
 *   java.lang.SecurityException: Attempt to change component state;
 *       component=ComponentInfo{com.github.android/...DeepLinkActivity}
 *
 * Some apps hardcode their own applicationId as a string literal (e.g. to build a
 * ComponentName for PackageManager#setComponentEnabledSetting) instead of deriving it
 * from Context#getPackageName(). After cloning, the running process's real package name
 * no longer matches that literal, so PackageManagerService's "caller must own the target
 * package" check fails and the app crashes in Application#onCreate() before any UI shows.
 *
 * Deliberately narrow in scope: this does NOT touch class/type descriptors
 * (Lcom/github/android/Foo;) — Android happily lets an app's Java package structure
 * differ from its manifest package/applicationId, so class names are left as-is, same
 * as the rest of this engine's approach. Only const-string / const-string/jumbo
 * literals that exactly equal, or start with "<oldPkg>.", are rewritten.
 */
object DexPatcher {

    /**
     * @return patched dex bytes, or null if no matching hardcoded string was found —
     *         callers should keep the original bytes unchanged in that case, both to
     *         avoid needless work and because a from-scratch dexlib2 write is not
     *         guaranteed to be byte-identical to the original for unrelated content.
     */
    fun patchSelfPackageStringConstants(
        dexBytes: ByteArray,
        oldPkg: String,
        newPkg: String,
        tempDir: File
    ): ByteArray? {
        val opcodes = Opcodes.getDefault()
        val dexFile: DexFile = DexBackedDexFile.fromInputStream(opcodes, ByteArrayInputStream(dexBytes))

        var matchCount = 0

        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getInstructionRewriter(rewriters: Rewriters): Rewriter<Instruction> {
                return object : Rewriter<Instruction> {
                    override fun rewrite(instruction: Instruction): Instruction {
                        // Read opcode off `instruction` while its static type is still the
                        // plain `Instruction` parameter type. Narrowing `instruction` itself
                        // via `is` checks below would smart-cast it to an intersection type
                        // that implements both ReferenceInstruction.opcode and
                        // OneRegisterInstruction.opcode, which is an unresolvable overload
                        // ambiguity in Kotlin — so we deliberately don't narrow the parameter,
                        // and instead cast into separate local vals.
                        val opcode = instruction.opcode
                        if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) {
                            return instruction
                        }

                        val refInsn = instruction as? ReferenceInstruction ?: return instruction
                        val regInsn = instruction as? OneRegisterInstruction ?: return instruction

                        val ref = refInsn.reference
                        if (ref !is StringReference) return instruction
                        val original = ref.string

                        val replacement = when {
                            original == oldPkg -> newPkg
                            original.startsWith("$oldPkg.") -> newPkg + original.substring(oldPkg.length)
                            else -> null
                        } ?: return instruction

                        matchCount++
                        Logger.log("DexPatcher: rewriting hardcoded string \"$original\" -> \"$replacement\"")

                        val newRef = ImmutableStringReference(replacement)
                        return if (opcode == Opcode.CONST_STRING_JUMBO) {
                            ImmutableInstruction31c(opcode, regInsn.registerA, newRef)
                        } else {
                            ImmutableInstruction21c(opcode, regInsn.registerA, newRef)
                        }
                    }
                }
            }
        })

        val rewrittenDexFile = rewriter.rewriteDexFile(dexFile)
        if (matchCount == 0) {
            return null
        }

        Logger.log("DexPatcher: patched $matchCount hardcoded self-package string constant(s)")

        val tempOut = File.createTempFile("patched_dex", ".dex", tempDir)
        return try {
            DexPool.writeTo(tempOut.absolutePath, rewrittenDexFile)
            tempOut.readBytes()
        } finally {
            tempOut.delete()
        }
    }

    fun isDexEntry(entryName: String): Boolean =
        Regex("""classes\d*\.dex""").matches(entryName)
}
