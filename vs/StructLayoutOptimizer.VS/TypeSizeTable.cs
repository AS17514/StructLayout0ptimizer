using Microsoft.CodeAnalysis;
using System.Collections.Generic;

namespace StructLayoutOptimizer.VS;

public class TypeSizeInfo
{
    public int Size { get; }
    public int Alignment { get; }
    public TypeSizeInfo(int size, int alignment) { Size = size; Alignment = alignment; }
}

internal static class TypeSizeTable
{
    private const int PointerSize = 8;

    private static readonly Dictionary<SpecialType, TypeSizeInfo> _specialTypes = new()
    {
        [SpecialType.System_Byte]   = new(1, 1),
        [SpecialType.System_SByte]  = new(1, 1),
        [SpecialType.System_Boolean]= new(1, 1),
        [SpecialType.System_Int16]  = new(2, 2),
        [SpecialType.System_UInt16] = new(2, 2),
        [SpecialType.System_Char]   = new(2, 2),
        [SpecialType.System_Int32]  = new(4, 4),
        [SpecialType.System_UInt32] = new(4, 4),
        [SpecialType.System_Single] = new(4, 4),
        [SpecialType.System_Int64]  = new(8, 8),
        [SpecialType.System_UInt64] = new(8, 8),
        [SpecialType.System_Double] = new(8, 8),
        [SpecialType.System_IntPtr] = new(PointerSize, PointerSize),
        [SpecialType.System_UIntPtr]= new(PointerSize, PointerSize),
    };

    private static readonly Dictionary<string, TypeSizeInfo> _knownTypes = new()
    {
        ["System.DateTime"]       = new(8, 8),
        ["System.TimeSpan"]       = new(8, 8),
        ["System.DateTimeOffset"] = new(16, 8),
        ["System.Guid"]           = new(16, 4),
        ["System.Decimal"]        = new(16, 8),
        ["System.Half"]           = new(2, 2),
        ["System.Int128"]         = new(16, 16),
        ["System.UInt128"]        = new(16, 16),
    };

    /// <summary>
    /// Resolves type size/alignment using Roslyn's SemanticModel.
    /// Returns null for reference types (not layout-relevant fields in a struct body).
    /// </summary>
    public static TypeSizeInfo? Resolve(ITypeSymbol type, SemanticModel model)
    {
        if (type.IsReferenceType) return null;

        // Special types (primitives)
        if (_specialTypes.TryGetValue(type.SpecialType, out var st))
            return st;

        // Known BCL types
        var fullName = type.ContainingNamespace?.IsGlobalNamespace == false
            ? type.ContainingNamespace.ToDisplayString() + "." + type.Name
            : type.Name;
        if (_knownTypes.TryGetValue(fullName, out var kt))
            return kt;

        // Enum — underlying type determines size
        if (type.TypeKind == TypeKind.Enum && type is INamedTypeSymbol enumType)
        {
            var underlying = enumType.EnumUnderlyingType;
            if (underlying != null && _specialTypes.TryGetValue(underlying.SpecialType, out var et))
                return et;
            return new TypeSizeInfo(4, 4); // default: int
        }

        // Named struct — recursively analyze fields
        if (type.IsValueType && type is INamedTypeSymbol namedStruct
            && namedStruct.TypeKind == TypeKind.Structure)
        {
            return ResolveStructFromFields(namedStruct, model);
        }

        return null;
    }

    /// <summary>
    /// Recursively computes the size and alignment of a named struct type
    /// by analyzing its instance fields.
    /// </summary>
    public static TypeSizeInfo ResolveStructFromFields(
        INamedTypeSymbol structType, SemanticModel model)
    {
        var fields = new List<(int Size, int Alignment)>();

        foreach (var member in structType.GetMembers())
        {
            if (member is IFieldSymbol field && !field.IsStatic)
            {
                var info = Resolve(field.Type, model);
                if (info != null)
                    fields.Add((info.Size, info.Alignment));
            }
        }

        if (fields.Count == 0)
            return new TypeSizeInfo(1, 1); // empty struct

        return ComputeLayout(fields);
    }

    internal static TypeSizeInfo ComputeLayout(List<(int Size, int Alignment)> sortedFields)
    {
        int offset = 0, maxAlign = 0;
        foreach (var (size, align) in sortedFields)
        {
            if (align > maxAlign) maxAlign = align;
            offset = AlignUp(offset, align);
            offset += size;
        }
        offset = AlignUp(offset, maxAlign);
        return new TypeSizeInfo(offset, maxAlign);
    }

    internal static int AlignUp(int offset, int alignment)
    {
        if (alignment <= 0) return offset;
        return (offset + alignment - 1) & ~(alignment - 1);
    }
}
