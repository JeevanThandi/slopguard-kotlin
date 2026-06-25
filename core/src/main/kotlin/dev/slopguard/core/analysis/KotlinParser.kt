package dev.slopguard.core.analysis

import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtFile

/**
 * Wraps the Kotlin compiler's PSI front-end to turn source text into a [KtFile]
 * syntax tree — the Kotlin analogue of slopguard-swift's SwiftSyntax parser and
 * slopguard-typescript's `ts.createSourceFile`. No semantic analysis, no
 * bytecode: just parsing.
 *
 * The (relatively heavy) compiler environment is created once and reused across
 * every file in a scan. Call [close] when finished.
 */
class KotlinParser : AutoCloseable {
    private val disposable: Disposable = Disposer.newDisposable("slopguard-kotlin-parser")

    private val factory: PsiFileFactory by lazy {
        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            put(CommonConfigurationKeys.MODULE_NAME, "slopguard")
        }
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        PsiFileFactory.getInstance(environment.project)
    }

    /** Parse [text] (reported under [name]) into a [KtFile]. */
    fun parse(name: String, text: String): KtFile {
        val psi = factory.createFileFromText(name, KotlinLanguage.INSTANCE, text)
        return psi as? KtFile
            ?: throw IllegalStateException("PSI factory did not return a KtFile for $name")
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}
