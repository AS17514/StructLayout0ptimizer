"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.computeLayout = computeLayout;
exports.computeOptimalOrder = computeOptimalOrder;
exports.formatSummary = formatSummary;
const structAnalyzer_1 = require("./structAnalyzer");
const typeSizeTable_1 = require("./typeSizeTable");
function computeLayout(fields, pack) {
    const placements = [];
    let currentOffset = 0;
    let maxAlignment = 0;
    for (const field of fields) {
        const alignment = (0, structAnalyzer_1.effectiveAlignment)(field, pack);
        const size = field.typeInfo?.size;
        if (!size)
            continue;
        if (alignment > maxAlignment)
            maxAlignment = alignment;
        const padding = (0, typeSizeTable_1.alignUp)(currentOffset, alignment) - currentOffset;
        const alignedOffset = currentOffset + padding;
        placements.push({ field, offset: alignedOffset, paddingBefore: padding });
        currentOffset = alignedOffset + size;
    }
    const structAlignment = pack === 0 ? maxAlignment : Math.min(pack, maxAlignment);
    const trailingPadding = (0, typeSizeTable_1.alignUp)(currentOffset, structAlignment) - currentOffset;
    const totalSize = currentOffset + trailingPadding;
    const totalPadding = placements.reduce((sum, p) => sum + p.paddingBefore, 0) + trailingPadding;
    return { totalSize, placements, paddingBytes: totalPadding };
}
function computeOptimalOrder(fields, pack) {
    const currentLayout = computeLayout(fields, pack);
    const warnings = [];
    // Separate fixed vs movable
    const fixed = new Map();
    const movable = [];
    for (let i = 0; i < fields.length; i++) {
        const field = fields[i];
        if (field.isFixed) {
            fixed.set(i, field);
        }
        else if (!field.typeInfo) {
            movable.push(field);
            warnings.push(`Unknown type '${field.typeText}' for field '${field.name}' — alignment assumed as 8 (pointer).`);
        }
        else {
            movable.push(field);
        }
    }
    // Sort movable by alignment DESC, then size DESC
    movable.sort((a, b) => {
        const alignA = (0, structAnalyzer_1.effectiveAlignment)(a, pack);
        const alignB = (0, structAnalyzer_1.effectiveAlignment)(b, pack);
        if (alignB !== alignA)
            return alignB - alignA;
        return (b.typeInfo?.size ?? 0) - (a.typeInfo?.size ?? 0);
    });
    // Reconstruct ordered list, keeping fixed at original positions
    const orderedFields = [];
    let movableIdx = 0;
    for (let i = 0; i < fields.length; i++) {
        if (fixed.has(i)) {
            orderedFields.push(fixed.get(i));
        }
        else if (movableIdx < movable.length) {
            orderedFields.push(movable[movableIdx++]);
        }
    }
    const optimalLayout = computeLayout(orderedFields, pack);
    const bytesSaved = currentLayout.totalSize - optimalLayout.totalSize;
    const isAlreadyOptimal = orderedFields.length === fields.length &&
        orderedFields.every((f, i) => f.textRange === fields[i].textRange);
    return { currentLayout, optimalLayout, bytesSaved, isAlreadyOptimal, orderedFields, warnings };
}
function formatSummary(result, i18n) {
    if (result.isAlreadyOptimal) {
        return i18n("already_optimal_detail", String(result.currentLayout.totalSize));
    }
    if (result.bytesSaved > 0) {
        const pct = result.currentLayout.totalSize > 0
            ? Math.floor((result.bytesSaved * 100) / result.currentLayout.totalSize)
            : 0;
        const lines = [
            i18n("saves_bytes", String(result.bytesSaved), String(pct)),
            i18n("size_comparison", String(result.currentLayout.totalSize), String(result.optimalLayout.totalSize)),
            i18n("padding_reduced", String(result.currentLayout.paddingBytes), String(result.optimalLayout.paddingBytes)),
        ];
        if (result.warnings.length > 0) {
            lines.push("\n" + i18n("warnings_header"));
            result.warnings.forEach((w) => lines.push("  - " + w));
        }
        return lines.join("\n");
    }
    return i18n("no_improvement_detail", String(result.currentLayout.totalSize));
}
//# sourceMappingURL=layoutCalculator.js.map