package com.riderpludgemaker.structoptimizer

/**
 * Computes struct field layout — both current (declaration order) and optimal (descending alignment).
 * Determines whether reordering is beneficial.
 */
object LayoutCalculator {

    data class LayoutResult(
        val totalSize: Int,
        val fieldOffsets: List<FieldPlacement>,
        val paddingBytes: Int,  // total wasted bytes due to alignment
    )

    data class FieldPlacement(
        val field: StructAnalyzer.FieldInfo,
        val offset: Int,     // aligned offset where the field starts
        val paddingBefore: Int, // bytes of padding inserted before this field
    )

    data class OptimizationResult(
        val currentLayout: LayoutResult,
        val optimalLayout: LayoutResult,
        val bytesSaved: Int,
        val isAlreadyOptimal: Boolean,
        val orderedFields: List<StructAnalyzer.FieldInfo>, // fields in optimal order
        val warnings: List<String>,
    )

    /**
     * Computes the layout for fields in a given order.
     * Returns offsets, padding, and total struct size.
     */
    fun computeLayout(
        fields: List<StructAnalyzer.FieldInfo>,
        pack: Int,
    ): LayoutResult {
        val placements = mutableListOf<FieldPlacement>()
        var currentOffset = 0
        var maxAlignment = 0

        for (field in fields) {
            val alignment = StructAnalyzer.effectiveAlignment(field, pack)
            val size = field.typeInfo?.size ?: continue

            if (alignment > maxAlignment) maxAlignment = alignment

            val padding = TypeSizeResolver.alignUp(currentOffset, alignment) - currentOffset
            val alignedOffset = currentOffset + padding

            placements.add(FieldPlacement(field, alignedOffset, padding))
            currentOffset = alignedOffset + size
        }

        // Total struct size must be a multiple of maxAlignment (for arrays)
        val structAlignment = if (pack == 0) maxAlignment else minOf(pack, maxAlignment)
        val trailingPadding = TypeSizeResolver.alignUp(currentOffset, structAlignment) - currentOffset
        val totalSize = currentOffset + trailingPadding

        // Count total padding
        val totalPadding = placements.sumOf { it.paddingBefore } + trailingPadding

        return LayoutResult(
            totalSize = totalSize,
            fieldOffsets = placements,
            paddingBytes = totalPadding,
        )
    }

    /**
     * Determines the optimal field order to minimize padding.
     *
     * Strategy: sort fields by effective alignment descending, then by size descending.
     * Fields with unresolved types are placed at the end (largest alignment conservative).
     *
     * Fields that should NOT be reordered (fixed buffers, etc.) are kept in their relative
     * positions among the movable fields.
     */
    fun computeOptimalOrder(
        fields: List<StructAnalyzer.FieldInfo>,
        pack: Int,
    ): OptimizationResult {
        val currentLayout = computeLayout(fields, pack)

        val warnings = mutableListOf<String>()

        // Separate fields into movable and fixed
        val movable = mutableListOf<StructAnalyzer.FieldInfo>()
        val fixed = mutableListOf<Pair<Int, StructAnalyzer.FieldInfo>>() // (originalIndex, field)

        for ((i, field) in fields.withIndex()) {
            if (field.isFixed) {
                fixed.add(i to field)
            } else if (field.typeInfo == null) {
                // Unknown type — keep in place with a warning
                movable.add(field)
                warnings.add(Messages.get("unknown_type", field.typeText, field.fieldName))
            } else {
                movable.add(field)
            }
        }

        // Sort movable fields by effective alignment DESC, then size DESC
        val sortedMovable = movable.sortedWith(
            compareByDescending<StructAnalyzer.FieldInfo> { field ->
                StructAnalyzer.effectiveAlignment(field, pack)
            }.thenByDescending { field ->
                field.typeInfo?.size ?: 0
            }
        )

        // Place fixed fields in their original positions, based on original indices
        val orderedFields = mutableListOf<StructAnalyzer.FieldInfo>()
        var movableIdx = 0
        val fixedSet = fixed.map { it.first }.toSet()

        for (i in fields.indices) {
            if (i in fixedSet) {
                // Insert the fixed field at its original position
                val fixedField = fixed.first { it.first == i }.second
                orderedFields.add(fixedField)
            } else {
                // Take the next movable field from the sorted list
                if (movableIdx < sortedMovable.size) {
                    orderedFields.add(sortedMovable[movableIdx])
                    movableIdx++
                }
            }
        }

        // Compute optimal layout
        val optimalLayout = computeLayout(orderedFields, pack)
        val bytesSaved = currentLayout.totalSize - optimalLayout.totalSize
        val isAlreadyOptimal = orderedFields.map { it.textRange }.toList() ==
                               fields.map { it.textRange }.toList()

        return OptimizationResult(
            currentLayout = currentLayout,
            optimalLayout = optimalLayout,
            bytesSaved = bytesSaved,
            isAlreadyOptimal = isAlreadyOptimal,
            orderedFields = orderedFields,
            warnings = warnings,
        )
    }

    /**
     * Generates a human-readable summary of the optimization.
     */
    fun formatSummary(result: OptimizationResult): String {
        val sb = StringBuilder()
        if (result.isAlreadyOptimal) {
            sb.append(Messages.get("already_optimal_detail", result.currentLayout.totalSize))
        } else if (result.bytesSaved > 0) {
            val pct = if (result.currentLayout.totalSize > 0) {
                (result.bytesSaved * 100) / result.currentLayout.totalSize
            } else 0
            sb.appendLine(Messages.get("saves_bytes", result.bytesSaved, pct))
            sb.appendLine(Messages.get("size_comparison", result.currentLayout.totalSize, result.optimalLayout.totalSize))
            sb.appendLine(Messages.get("padding_reduced", result.currentLayout.paddingBytes, result.optimalLayout.paddingBytes))
        } else {
            sb.append(Messages.get("no_improvement_detail", result.currentLayout.totalSize))
        }
        if (result.warnings.isNotEmpty()) {
            sb.appendLine("\n" + Messages.get("warnings_header"))
            result.warnings.forEach { sb.appendLine("  - $it") }
        }
        return sb.toString()
    }
}
