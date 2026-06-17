package com.riderpludgemaker.structoptimizer

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Replaces struct field declarations in the document with reordered versions.
 *
 * Strategy: partition the struct body into alternating "gap" and "field block" segments.
 * Gap segments (whitespace, methods, nested types, etc.) stay in their original positions.
 * Field block segments are replaced with reordered field text.
 */
object StructFormatter {

    /**
     * A contiguous block of text in the struct body.
     */
    private data class BodySegment(
        val isField: Boolean,
        val text: String,
        val originalFieldIndex: Int, // -1 for non-field segments
    )

    /**
     * Applies the reordered field layout to the document.
     *
     * @param currentFields fields in original declaration order
     * @param orderedFields fields in the new optimal order (same instances, rearranged)
     */
    fun applyReordering(
        project: Project,
        document: Document,
        structInfo: StructAnalyzer.StructInfo,
        currentFields: List<StructAnalyzer.FieldInfo>,
        orderedFields: List<StructAnalyzer.FieldInfo>,
    ) {
        if (currentFields.isEmpty()) return

        WriteCommandAction.runWriteCommandAction(project) {
            val bodyStart = structInfo.bodyOpenBraceOffset + 1
            val bodyEnd = structInfo.bodyCloseBraceOffset

            // Step 1: compute the "full extent" for each field (including leading comments/attributes/whitespace)
            val fieldExtents = currentFields.map { computeFieldExtent(it) }

            // Step 2: partition the body into segments
            val segments = buildSegments(bodyStart, bodyEnd, currentFields, fieldExtents, document)

            // Step 3: build a lookup from original field index → its text block
            val fieldTextBlocks = currentFields.indices.map { i ->
                document.getText(TextRange(fieldExtents[i].first, fieldExtents[i].second))
            }

            // Step 4: build the new body by walking segments and emitting reordered field text
            val fieldPosToOrigIndex = orderedFields.map { orderedField ->
                currentFields.indexOfFirst { it === orderedField }
            }

            val sb = StringBuilder(bodyEnd - bodyStart)
            var reorderIdx = 0
            for (segment in segments) {
                if (segment.isField) {
                    val origIdx = fieldPosToOrigIndex[reorderIdx]
                    sb.append(fieldTextBlocks[origIdx])
                    reorderIdx++
                } else {
                    sb.append(segment.text)
                }
            }

            // Step 5: replace the entire body text
            document.replaceString(bodyStart, bodyEnd, sb.toString())

            // Commit PSI changes
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    /**
     * Computes the full extent (startOffset, endOffset) for a field block.
     * The full extent includes leading whitespace, comments, and attributes
     * that are directly associated with the field.
     */
    private fun computeFieldExtent(
        field: StructAnalyzer.FieldInfo,
    ): Pair<Int, Int> {
        var start = field.textRange.startOffset
        val end = field.textRange.endOffset

        // Walk backwards through PSI siblings to include comments, attributes, whitespace
        var sibling: PsiElement? = field.psiElement.prevSibling
        while (sibling != null) {
            if (isSkippableSibling(sibling)) {
                start = sibling.textRange.startOffset
                sibling = sibling.prevSibling
            } else {
                break
            }
        }

        return Pair(start, end)
    }

    /**
     * Returns true if this PSI sibling should be included in the preceding field's text block.
     * Includes: whitespace, comments, attributes (things that "belong" to the next field).
     */
    private fun isSkippableSibling(sibling: PsiElement): Boolean {
        val typeName = sibling.node?.elementType?.toString() ?: ""
        val text = sibling.text ?: ""

        if (typeName.contains("WHITE_SPACE")) return true
        if (typeName.contains("COMMENT")) return true

        val trimmed = text.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) return true
        if (trimmed.startsWith('[') && trimmed.contains(']')) return true

        return false
    }

    /**
     * Partitions the struct body text into segments: field blocks and non-field gaps.
     */
    private fun buildSegments(
        bodyStart: Int,
        bodyEnd: Int,
        fields: List<StructAnalyzer.FieldInfo>,
        fieldExtents: List<Pair<Int, Int>>,
        document: Document,
    ): List<BodySegment> {
        val segments = mutableListOf<BodySegment>()
        var cursor = bodyStart

        for (i in fields.indices) {
            val (fieldStart, fieldEnd) = fieldExtents[i]

            if (fieldStart > cursor) {
                // Gap segment: non-field text between previous construct and this field block
                segments.add(
                    BodySegment(
                        isField = false,
                        text = document.getText(TextRange(cursor, fieldStart)),
                        originalFieldIndex = -1,
                    )
                )
            }

            // Field segment
            segments.add(
                BodySegment(
                    isField = true,
                    text = document.getText(TextRange(fieldStart, fieldEnd)),
                    originalFieldIndex = i,
                )
            )

            cursor = fieldEnd
        }

        // Trailing gap after the last field
        if (cursor < bodyEnd) {
            segments.add(
                BodySegment(
                    isField = false,
                    text = document.getText(TextRange(cursor, bodyEnd)),
                    originalFieldIndex = -1,
                )
            )
        }

        return segments
    }
}
