/**
 * Maps C# type names to (size, alignment) in bytes.
 * Sizes assume 64-bit .NET runtime.
 */
export type TypeInfo = { size: number; alignment: number };
export type TypeInfoMap = Record<string, TypeInfo>;

const POINTER_SIZE = 8;

const typeTable: TypeInfoMap = {
    // 1-byte aligned
    byte:    { size: 1, alignment: 1 },
    sbyte:   { size: 1, alignment: 1 },
    bool:    { size: 1, alignment: 1 },
    Boolean: { size: 1, alignment: 1 },
    Byte:    { size: 1, alignment: 1 },
    SByte:   { size: 1, alignment: 1 },

    // 2-byte aligned
    short:   { size: 2, alignment: 2 },
    ushort:  { size: 2, alignment: 2 },
    char:    { size: 2, alignment: 2 },
    Int16:   { size: 2, alignment: 2 },
    UInt16:  { size: 2, alignment: 2 },
    Char:    { size: 2, alignment: 2 },

    // 4-byte aligned
    int:     { size: 4, alignment: 4 },
    uint:    { size: 4, alignment: 4 },
    float:   { size: 4, alignment: 4 },
    single:  { size: 4, alignment: 4 },
    Int32:   { size: 4, alignment: 4 },
    UInt32:  { size: 4, alignment: 4 },
    Single:  { size: 4, alignment: 4 },

    // 8-byte aligned
    long:    { size: 8, alignment: 8 },
    ulong:   { size: 8, alignment: 8 },
    double:  { size: 8, alignment: 8 },
    Int64:   { size: 8, alignment: 8 },
    UInt64:  { size: 8, alignment: 8 },
    Double:  { size: 8, alignment: 8 },

    // BCL value types
    DateTime:       { size: 8, alignment: 8 },
    TimeSpan:       { size: 8, alignment: 8 },
    DateTimeOffset: { size: 16, alignment: 8 },
    Guid:           { size: 16, alignment: 4 },
    Decimal:        { size: 16, alignment: 8 },
    decimal:        { size: 16, alignment: 8 },
    Half:           { size: 2, alignment: 2 },
    Int128:         { size: 16, alignment: 16 },
    UInt128:        { size: 16, alignment: 16 },

    // Pointer-sized types
    nint:    { size: POINTER_SIZE, alignment: POINTER_SIZE },
    nuint:   { size: POINTER_SIZE, alignment: POINTER_SIZE },
    IntPtr:  { size: POINTER_SIZE, alignment: POINTER_SIZE },
    UIntPtr: { size: POINTER_SIZE, alignment: POINTER_SIZE },
};

export function resolveType(typeName: string): TypeInfo | null {
    const cleaned = typeName.trim().replace(/\?$/, "").trim();
    // Exact match
    const info = typeTable[cleaned];
    if (info) return info;
    // Reference type: starts with uppercase, not a keyword
    if (isReferenceType(cleaned)) {
        return { size: POINTER_SIZE, alignment: POINTER_SIZE };
    }
    return null;
}

function isReferenceType(name: string): boolean {
    if (!name) return false;
    const first = name[0];
    if (first >= "a" && first <= "z") return false;
    if (name === "string" || name === "String" || name === "object" || name === "Object")
        return true;
    if (first === "I" && name.length > 1 && name[1] >= "A" && name[1] <= "Z")
        return true;
    return !(name in typeTable);
}

export function alignUp(offset: number, alignment: number): number {
    if (alignment <= 0) return offset;
    const mask = alignment - 1;
    return (offset + mask) & ~mask;
}
