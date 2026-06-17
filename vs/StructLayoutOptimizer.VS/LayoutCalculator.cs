using System.Collections.Generic;
using System.Linq;

namespace StructLayoutOptimizer.VS;

public class LayoutResult
{
    public int TotalSize { get; init; }
    public int TotalPadding { get; init; }
    public List<FieldPlacement> Placements { get; init; } = new();
}

public class FieldPlacement
{
    public FieldInfo Field { get; init; } = null!;
    public int Offset { get; init; }
    public int PaddingBefore { get; init; }
}

public class OptimizationResult
{
    public LayoutResult Current { get; init; } = null!;
    public LayoutResult Optimal { get; init; } = null!;
    public int BytesSaved => Current.TotalSize - Optimal.TotalSize;
    public bool IsAlreadyOptimal { get; init; }
    public List<FieldInfo> OrderedFields { get; init; } = new();
    public List<string> Warnings { get; init; } = new();
}

public static class LayoutCalculator
{
    public static LayoutResult ComputeLayout(List<FieldInfo> fields, int pack)
    {
        var placements = new List<FieldPlacement>();
        int offset = 0, maxAlign = 0;

        foreach (var field in fields)
        {
            var info = field.TypeInfo;
            if (info == null) continue;

            int align = pack == 0 ? info.Alignment : System.Math.Min(pack, info.Alignment);
            if (align > maxAlign) maxAlign = align;

            int padding = TypeSizeTable.AlignUp(offset, align) - offset;
            placements.Add(new FieldPlacement
            {
                Field = field,
                Offset = offset + padding,
                PaddingBefore = padding,
            });
            offset = offset + padding + info.Size;
        }

        int tailPad = TypeSizeTable.AlignUp(offset, maxAlign) - offset;
        return new LayoutResult
        {
            TotalSize = offset + tailPad,
            TotalPadding = placements.Sum(p => p.PaddingBefore) + tailPad,
            Placements = placements,
        };
    }

    public static OptimizationResult ComputeOptimalOrder(List<FieldInfo> fields, int pack)
    {
        var current = ComputeLayout(fields, pack);
        var warnings = new List<string>();

        // Separate fixed vs movable
        var fixedFields = new Dictionary<int, FieldInfo>();
        var movable = new List<FieldInfo>();

        for (int i = 0; i < fields.Count; i++)
        {
            if (fields[i].IsFixed)
                fixedFields[i] = fields[i];
            else if (fields[i].TypeInfo == null)
            {
                movable.Add(fields[i]);
                warnings.Add($"Unknown type '{fields[i].TypeText}' — alignment assumed as 8.");
            }
            else
            {
                movable.Add(fields[i]);
            }
        }

        // Sort movable: alignment DESC, then size DESC
        movable.Sort((a, b) =>
        {
            int alignCmp = EffectiveAlign(b, pack).CompareTo(EffectiveAlign(a, pack));
            if (alignCmp != 0) return alignCmp;
            return (b.TypeInfo?.Size ?? 0).CompareTo(a.TypeInfo?.Size ?? 0);
        });

        // Reconstruct ordered list
        var ordered = new List<FieldInfo>();
        int movableIdx = 0;
        for (int i = 0; i < fields.Count; i++)
        {
            if (fixedFields.TryGetValue(i, out var fixedField))
                ordered.Add(fixedField);
            else if (movableIdx < movable.Count)
                ordered.Add(movable[movableIdx++]);
        }

        var optimal = ComputeLayout(ordered, pack);
        bool alreadyOptimal = true;
        for (int i = 0; i < fields.Count; i++)
        {
            if (fields[i].Name != ordered[i].Name) { alreadyOptimal = false; break; }
        }

        return new OptimizationResult
        {
            Current = current,
            Optimal = optimal,
            IsAlreadyOptimal = alreadyOptimal,
            OrderedFields = ordered,
            Warnings = warnings,
        };
    }

    private static int EffectiveAlign(FieldInfo field, int pack)
    {
        int natural = field.TypeInfo?.Alignment ?? 8;
        return pack == 0 ? natural : System.Math.Min(pack, natural);
    }
}
