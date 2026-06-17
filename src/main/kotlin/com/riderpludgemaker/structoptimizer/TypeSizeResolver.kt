package com.riderpludgemaker.structoptimizer

/**
 * Maps C# type names to their (size, alignment) in bytes.
 * Sizes assume 64-bit .NET runtime. Alignment follows natural alignment rules.
 */
object TypeSizeResolver {

    /** Result: pair of (sizeInBytes, alignmentInBytes) */
    data class TypeInfo(val size: Int, val alignment: Int)

    private val pointerSize: Int = 8

    /** Primitive types and common BCL value types. */
    private val typeTable: Map<String, TypeInfo> = mapOf(
        // 1-byte aligned
        "byte"    to TypeInfo(1, 1),
        "sbyte"   to TypeInfo(1, 1),
        "bool"    to TypeInfo(1, 1),
        "Boolean" to TypeInfo(1, 1),
        "Byte"    to TypeInfo(1, 1),
        "SByte"   to TypeInfo(1, 1),

        // 2-byte aligned
        "short"   to TypeInfo(2, 2),
        "ushort"  to TypeInfo(2, 2),
        "char"    to TypeInfo(2, 2),
        "Int16"   to TypeInfo(2, 2),
        "UInt16"  to TypeInfo(2, 2),
        "Char"    to TypeInfo(2, 2),

        // 4-byte aligned
        "int"     to TypeInfo(4, 4),
        "uint"    to TypeInfo(4, 4),
        "float"   to TypeInfo(4, 4),
        "Int32"   to TypeInfo(4, 4),
        "UInt32"  to TypeInfo(4, 4),
        "Single"  to TypeInfo(4, 4),

        // 8-byte aligned
        "long"    to TypeInfo(8, 8),
        "ulong"   to TypeInfo(8, 8),
        "double"  to TypeInfo(8, 8),
        "Int64"   to TypeInfo(8, 8),
        "UInt64"  to TypeInfo(8, 8),
        "Double"  to TypeInfo(8, 8),

        // BCL value types
        "DateTime"       to TypeInfo(8, 8),
        "TimeSpan"       to TypeInfo(8, 8),
        "DateTimeOffset" to TypeInfo(16, 8), // DateTime + short offset + padding
        "Guid"           to TypeInfo(16, 4),
        "Decimal"        to TypeInfo(16, 8), // .NET 5+: 3x int + ulong, align = 8
        "decimal"        to TypeInfo(16, 8),
        "Half"           to TypeInfo(2, 2),  // .NET 5+
        "Int128"         to TypeInfo(16, 16), // .NET 7+
        "UInt128"        to TypeInfo(16, 16),

        // Pointer-sized types
        "nint"    to TypeInfo(pointerSize, pointerSize),
        "nuint"   to TypeInfo(pointerSize, pointerSize),
        "IntPtr"  to TypeInfo(pointerSize, pointerSize),
        "UIntPtr" to TypeInfo(pointerSize, pointerSize),
    )

    /** Well-known enum types that default to int (4/4). */
    private val knownEnumUnderlyingTypes: Map<String, TypeInfo> = mapOf(
        "Int32"   to TypeInfo(4, 4),
        "Byte"    to TypeInfo(1, 1),
        "Int16"   to TypeInfo(2, 2),
        "Int64"   to TypeInfo(8, 8),
        "UInt32"  to TypeInfo(4, 4),
        "UInt16"  to TypeInfo(2, 2),
        "UInt64"  to TypeInfo(8, 8),
        "SByte"   to TypeInfo(1, 1),
    )

    /** Returns (size, alignment) for a given type name, or null if unknown. */
    fun resolve(typeName: String): TypeInfo? {
        val cleaned = typeName.trim().removeSuffix("?").removeSuffix(" ")
        // Check exact match
        typeTable[cleaned]?.let { return it }
        // Check if it's a reference type (capital letter + not a known value type)
        if (isReferenceType(cleaned)) {
            return TypeInfo(pointerSize, pointerSize)
        }
        return null
    }

    /** Resolves Nullable<T> — size = sizeof(T) + 1 + padding to alignof(T) */
    fun resolveNullable(innerTypeName: String): TypeInfo? {
        val inner = resolve(innerTypeName) ?: return null
        // Nullable<T> = T field + bool hasValue field
        // aligned to alignof(T)
        val rawSize = inner.size + 1 // T + bool
        val paddedSize = alignUp(rawSize, inner.alignment)
        return TypeInfo(paddedSize, inner.alignment)
    }

    /** Resolves a type name that might include generics like Nullable<int> or MyStruct<T>. */
    fun resolveWithGenerics(typeName: String): TypeInfo? {
        val cleaned = typeName.trim()
        // Nullable<T> or T?
        if (cleaned.startsWith("Nullable<") || cleaned.endsWith("?")) {
            val inner = if (cleaned.endsWith("?")) {
                cleaned.removeSuffix("?")
            } else {
                extractGenericArg(cleaned, "Nullable")
            } ?: return null
            return resolveNullable(inner)
        }
        // For other generics, try the base type without generics
        // This is a heuristic — proper resolution needs Roslyn
        val baseName = cleaned.substringBefore('<').trim()
        return resolve(baseName)
    }

    /** Extracts the first generic type argument, e.g. "Nullable<int>" → "int" */
    private fun extractGenericArg(typeName: String, expectedOuter: String): String? {
        val prefix = "$expectedOuter<"
        if (!typeName.startsWith(prefix)) return null
        val inner = typeName.removePrefix(prefix)
        // Handle nested generics by tracking bracket depth
        var depth = 0
        for ((i, c) in inner.withIndex()) {
            when (c) {
                '<' -> depth++
                '>' -> {
                    depth--
                    if (depth < 0) return inner.substring(0, i).trim()
                }
                ',' -> if (depth == 0) return inner.substring(0, i).trim()
            }
        }
        // Should not reach here for valid input; fall back to trimming trailing '>'
        return inner.trimEnd('>').trim()
    }

    /** Aligns offset up to the given alignment boundary. */
    fun alignUp(offset: Int, alignment: Int): Int {
        if (alignment <= 0) return offset
        val mask = alignment - 1
        return (offset + mask) and mask.inv()
    }

    /**
     * Determines if a type name refers to a reference type (class/interface/delegate).
     * Heuristic: starts with uppercase, not in the known value type table,
     * and not a C# keyword (which are all lowercase).
     */
    private fun isReferenceType(typeName: String): Boolean {
        if (typeName.isEmpty()) return false
        val first = typeName[0]
        // C# keywords for value types are all lowercase
        // Proper CLR type names for value types start with uppercase but are in our table
        if (first.isLowerCase()) return false
        // Common reference type signals
        if (typeName == "string" || typeName == "String" || typeName == "object" || typeName == "Object") {
            return true
        }
        // If it starts with 'I' and second char is uppercase, likely an interface
        if (first == 'I' && typeName.length > 1 && typeName[1].isUpperCase()) return true
        // Default: unknown uppercase types are treated as reference types (conservative)
        return typeName !in typeTable
    }

    /** Returns the size of a pointer on the current architecture. */
    fun pointerSizeBytes(): Int = pointerSize
}
