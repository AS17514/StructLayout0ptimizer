import { type FieldInfo, effectiveAlignment } from "./structAnalyzer";
import { alignUp } from "./typeSizeTable";

export interface FieldPlacement {
    field: FieldInfo;
    offset: number;
    paddingBefore: number;
}

export interface LayoutResult {
    totalSize: number;
    placements: FieldPlacement[];
    paddingBytes: number;
}

export interface OptimizationResult {
    currentLayout: LayoutResult;
    optimalLayout: LayoutResult;
    bytesSaved: number;
    isAlreadyOptimal: boolean;
    orderedFields: FieldInfo[];
    warnings: string[];
}

export function computeLayout(fields: FieldInfo[], pack: number): LayoutResult {
    const placements: FieldPlacement[] = [];
    let currentOffset = 0;
    let maxAlignment = 0;

    for (const field of fields) {
        const alignment = effectiveAlignment(field, pack);
        const size = field.typeInfo?.size;
        if (!size) continue;
        if (alignment > maxAlignment) maxAlignment = alignment;

        const padding = alignUp(currentOffset, alignment) - currentOffset;
        const alignedOffset = currentOffset + padding;
        placements.push({ field, offset: alignedOffset, paddingBefore: padding });
        currentOffset = alignedOffset + size;
    }

    const structAlignment = pack === 0 ? maxAlignment : Math.min(pack, maxAlignment);
    const trailingPadding = alignUp(currentOffset, structAlignment) - currentOffset;
    const totalSize = currentOffset + trailingPadding;
    const totalPadding = placements.reduce((sum, p) => sum + p.paddingBefore, 0) + trailingPadding;

    return { totalSize, placements, paddingBytes: totalPadding };
}

export function computeOptimalOrder(fields: FieldInfo[], pack: number): OptimizationResult {
    const currentLayout = computeLayout(fields, pack);
    const warnings: string[] = [];

    // Separate fixed vs movable
    const fixed = new Map<number, FieldInfo>();
    const movable: FieldInfo[] = [];

    for (let i = 0; i < fields.length; i++) {
        const field = fields[i];
        if (field.isFixed) {
            fixed.set(i, field);
        } else if (!field.typeInfo) {
            movable.push(field);
            warnings.push(`Unknown type '${field.typeText}' for field '${field.name}' — alignment assumed as 8 (pointer).`);
        } else {
            movable.push(field);
        }
    }

    // Sort movable by alignment DESC, then size DESC
    movable.sort((a, b) => {
        const alignA = effectiveAlignment(a, pack);
        const alignB = effectiveAlignment(b, pack);
        if (alignB !== alignA) return alignB - alignA;
        return (b.typeInfo?.size ?? 0) - (a.typeInfo?.size ?? 0);
    });

    // Reconstruct ordered list, keeping fixed at original positions
    const orderedFields: FieldInfo[] = [];
    let movableIdx = 0;
    for (let i = 0; i < fields.length; i++) {
        if (fixed.has(i)) {
            orderedFields.push(fixed.get(i)!);
        } else if (movableIdx < movable.length) {
            orderedFields.push(movable[movableIdx++]);
        }
    }

    const optimalLayout = computeLayout(orderedFields, pack);
    const bytesSaved = currentLayout.totalSize - optimalLayout.totalSize;
    const isAlreadyOptimal =
        orderedFields.length === fields.length &&
        orderedFields.every((f, i) => f.textRange === fields[i].textRange);

    return { currentLayout, optimalLayout, bytesSaved, isAlreadyOptimal, orderedFields, warnings };
}

export function formatSummary(result: OptimizationResult, i18n: I18n): string {
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

/** Simple i18n function type */
export type I18n = (key: string, ...args: string[]) => string;
