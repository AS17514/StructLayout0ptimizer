package com.riderpludgemaker.structoptimizer

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Rider action: reorders C# struct fields for optimal memory layout.
 *
 * Triggered by Ctrl+Alt+R (configurable in plugin.xml).
 * When invoked with the cursor inside or selecting a struct,
 * the plugin analyzes the struct's fields and all nested struct declarations,
 * reordering fields by descending alignment to minimize padding.
 */
class ReorderStructAction : AnAction() {

    /** Accumulates results across all processed structs for a combined notification. */
    private data class ProcessResult(
        val structName: String,
        val bytesSaved: Int,
        val oldSize: Int,
        val newSize: Int,
        val warning: String? = null,
    )

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (editor == null) {
            showNotification(project, Messages.get("no_editor"), NotificationType.WARNING)
            return
        }
        if (psiFile == null || !psiFile.name.endsWith(".cs", ignoreCase = true)) {
            showNotification(project, Messages.get("not_cs_file"), NotificationType.WARNING)
            return
        }

        val document = editor.document

        PsiDocumentManager.getInstance(project).commitDocument(document)

        // Find the struct at the cursor
        val offset = editor.caretModel.primaryCaret.selectionStart
        val rootStructInfo = StructAnalyzer.findEnclosingStruct(psiFile, offset)
        if (rootStructInfo == null) {
            showNotification(project, Messages.get("no_struct"), NotificationType.WARNING)
            return
        }

        // Check if the root struct can be reordered
        val blockReason = StructAnalyzer.canReorder(rootStructInfo)
        if (blockReason != null) {
            showNotification(project, blockReason, NotificationType.WARNING)
            return
        }

        // Collect all structs (outer + nested), sort deepest first
        StructAnalyzer.clearStructCache()
        val allStructs = collectAllStructs(rootStructInfo)
        val results = mutableListOf<ProcessResult>()

        for (structInfo in allStructs.reversed()) {
            // Re-fetch struct info from current document state (offsets may have shifted)
            val freshInfo = rebuildStructInfo(structInfo) ?: continue
            val canReorder = StructAnalyzer.canReorder(freshInfo)
            if (canReorder != null) continue

            val fields = StructAnalyzer.extractFields(freshInfo, document)
            if (fields.size < 2) continue

            val result = LayoutCalculator.computeOptimalOrder(fields, freshInfo.pack)
            if (result.isAlreadyOptimal || result.bytesSaved <= 0) continue

            // Apply reordering
            StructFormatter.applyReordering(project, document, freshInfo, fields, result.orderedFields)

            // Re-commit PSI so the next struct sees updated offsets
            PsiDocumentManager.getInstance(project).commitDocument(document)

            val name = extractStructName(freshInfo.structElement) ?: "struct"
            results.add(
                ProcessResult(
                    structName = name,
                    bytesSaved = result.bytesSaved,
                    oldSize = result.currentLayout.totalSize,
                    newSize = result.optimalLayout.totalSize,
                    warning = result.warnings.firstOrNull(),
                )
            )
        }

        // Show combined notification
        if (results.isEmpty()) {
            showNotification(project, Messages.get("already_optimal"), NotificationType.INFORMATION)
        } else {
            val totalSaved = results.sumOf { it.bytesSaved }
            val sb = StringBuilder()
            sb.appendLine(Messages.get("optimized_summary", results.size, totalSaved))
            for (r in results) {
                val pct = if (r.oldSize > 0) (r.bytesSaved * 100) / r.oldSize else 0
                sb.appendLine(Messages.get("optimized_item", r.structName, r.oldSize, r.newSize, pct))
            }
            showNotification(project, sb.toString().trimEnd(), NotificationType.INFORMATION)
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    /**
     * Recursively collects all struct declarations starting from the given struct info.
     * Returns a list sorted from outermost to innermost (by PSI depth ascending).
     */
    private fun collectAllStructs(root: StructAnalyzer.StructInfo): List<StructAnalyzer.StructInfo> {
        val result = mutableListOf(root)
        for (nested in StructAnalyzer.findNestedStructs(root)) {
            result.addAll(collectAllStructs(nested))
        }
        return result
    }

    /**
     * Rebuilds StructInfo from the current document state.
     * Needed after inner structs have been modified and offsets have shifted.
     */
    private fun rebuildStructInfo(
        structInfo: StructAnalyzer.StructInfo,
    ): StructAnalyzer.StructInfo? {
        val element = structInfo.structElement
        if (!element.isValid) return null
        // Re-parse from the element's current text
        val text = element.text ?: return null
        val startOffset = element.textRange.startOffset
        val openBrace = text.indexOf('{')
        val closeBrace = text.lastIndexOf('}')
        if (openBrace < 0 || closeBrace <= openBrace) return null

        val attrs = extractAttrs(text.substring(0, openBrace))

        return StructAnalyzer.StructInfo(
            structElement = element,
            bodyOpenBraceOffset = startOffset + openBrace,
            bodyCloseBraceOffset = startOffset + closeBrace,
            layoutKind = attrs.first,
            pack = attrs.second,
            explicitSize = attrs.third,
            hasFieldOffset = text.contains("FieldOffset", ignoreCase = true),
            isReadOnly = text.trimStart().startsWith("readonly ") ||
                         text.contains("readonly struct ") ||
                         text.contains("readonly record struct "),
            isRefStruct = text.trimStart().startsWith("ref ") ||
                          text.contains("ref struct "),
        )
    }

    /** Quick attribute extraction without full PSI traversal. */
    private fun extractAttrs(header: String): Triple<StructAnalyzer.LayoutKind, Int, Int> {
        var kind = StructAnalyzer.LayoutKind.SEQUENTIAL
        var pack = 0
        var size = 0
        val regex = Regex(
            """\[?\s*(?:System\.Runtime\.InteropServices\.)?StructLayout(?:Attribute)?\s*\(([^)]*)\)""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(header)
        if (match != null) {
            val args = match.groupValues[1]
            if (args.contains("LayoutKind.Explicit", ignoreCase = true) ||
                args.contains("Explicit", ignoreCase = true))
                kind = StructAnalyzer.LayoutKind.EXPLICIT
            else if (args.contains("LayoutKind.Auto", ignoreCase = true) ||
                     args.contains("Auto", ignoreCase = true))
                kind = StructAnalyzer.LayoutKind.AUTO
            Regex("""Pack\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(args)
                ?.let { pack = it.groupValues[1].toIntOrNull() ?: 0 }
            Regex("""Size\s*=\s*(\d+)""", RegexOption.IGNORE_CASE).find(args)
                ?.let { size = it.groupValues[1].toIntOrNull() ?: 0 }
        }
        return Triple(kind, pack, size)
    }

    /** Extracts the simple name of a struct from its declaration text. */
    private fun extractStructName(element: PsiElement): String? {
        val text = element.text?.trim() ?: return null
        val regex = Regex("""struct\s+(\w+)""")
        return regex.find(text)?.groupValues?.get(1)
    }

    private fun showNotification(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StructLayoutOptimizer")
            .createNotification(message, type)
            .notify(project)
    }
}
