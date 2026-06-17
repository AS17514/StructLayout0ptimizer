import { type TypeInfo, resolveType } from "./typeSizeTable";

export interface FieldInfo {
    name: string;
    typeText: string;
    typeInfo: TypeInfo | null;
    startOffset: number;
    endOffset: number;
    textRange: string; // unique identifier for comparison
    leadingText: string;
    isFixed: boolean;
}

export interface StructInfo {
    bodyStart: number;
    bodyEnd: number;
    fields: FieldInfo[];
}

/**
 * Finds the enclosing struct at the given cursor offset.
 * Does text-based parsing — works without OmniSharp.
 */
export function findEnclosingStruct(text: string, cursorOffset: number): StructInfo | null {
    // Walk backwards from cursor to find the struct keyword
    const before = text.substring(0, cursorOffset);
    const after = text.substring(cursorOffset);

    // Find the last "struct" before cursor
    const structPattern = /\bstruct\s+/g;
    let match: RegExpExecArray | null;
    let bestMatch: RegExpExecArray | null = null;

    while ((match = structPattern.exec(before)) !== null) {
        // Check if this struct body contains the cursor
        const openBrace = text.indexOf("{", match.index);
        if (openBrace < 0) continue;
        const closeBrace = findMatchingBrace(text, openBrace);
        if (closeBrace < 0) continue;
        if (cursorOffset > openBrace && cursorOffset < closeBrace) {
            bestMatch = match;
            break;
        }
    }

    if (!bestMatch) return null;

    const openBrace = text.indexOf("{", bestMatch.index);
    const closeBrace = findMatchingBrace(text, openBrace);
    if (openBrace < 0 || closeBrace < 0) return null;

    const fields = extractFields(text, openBrace + 1, closeBrace);
    return { bodyStart: openBrace + 1, bodyEnd: closeBrace, fields };
}

function findMatchingBrace(text: string, openPos: number): number {
    let depth = 0;
    for (let i = openPos; i < text.length; i++) {
        if (text[i] === "{") depth++;
        else if (text[i] === "}") {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

function extractFields(text: string, bodyStart: number, bodyEnd: number): FieldInfo[] {
    const fields: FieldInfo[] = [];
    const bodyText = text.substring(bodyStart, bodyEnd);

    // Split into top-level declarations (semicolons at depth 0)
    const declarations = splitDeclarations(bodyText);

    for (const decl of declarations) {
        const field = parseField(decl.text, bodyStart, decl.bodyOffset);
        if (field) fields.push(field);
    }

    return fields;
}

function splitDeclarations(bodyText: string): Array<{ text: string; bodyOffset: number }> {
    const declarations: Array<{ text: string; bodyOffset: number }> = [];
    let depth = 0;
    let start = 0;

    for (let i = 0; i < bodyText.length; i++) {
        const ch = bodyText[i];
        if (ch === "{" || ch === "<") depth++;
        else if (ch === "}" || ch === ">") depth--;
        else if (ch === ";" && depth === 0) {
            declarations.push({ text: bodyText.substring(start, i + 1), bodyOffset: start });
            start = i + 1;
        }
    }

    return declarations;
}

function parseField(declaration: string, bodyStart: number, bodyOffset: number): FieldInfo | null {
    let text = declaration.trim();

    // Strip comments
    text = text.replace(/\/\/.*$/, "").trim();
    text = text.replace(/\/\*[\s\S]*?\*\//g, "").trim();

    if (!text) return null;

    // Check for fixed buffer
    const fixedMatch = text.match(/fixed\s+(\w+)\s+(\w+)\s*\[.*\]/);
    if (fixedMatch) {
        const elemType = resolveType(fixedMatch[1]);
        const name = fixedMatch[2];
        // Rough estimate: count * elemSize
        const countMatch = text.match(/\[(\d+)\]/);
        const count = countMatch ? parseInt(countMatch[1]) : 1;
        const typeInfo: TypeInfo | null = elemType
            ? { size: elemType.size * count, alignment: elemType.alignment }
            : null;
        return {
            name,
            typeText: `fixed ${fixedMatch[1]}`,
            typeInfo,
            startOffset: bodyStart + bodyOffset + declaration.indexOf(text),
            endOffset: bodyStart + bodyOffset + declaration.indexOf(text) + text.length,
            textRange: declaration.trim(),
            leadingText: "",
            isFixed: true,
        };
    }

    // Check if this looks like a field
    if (!looksLikeField(text)) return null;

    // Remove trailing semicolon
    text = text.replace(/;\s*$/, "").trim();

    // Remove initializer
    const eqIdx = findLastOutsideGenerics(text, "=");
    if (eqIdx >= 0) text = text.substring(0, eqIdx).trim();

    // Tokenize and find field name
    const tokens = tokenize(text);
    if (tokens.length < 2) return null;

    let nameIdx = tokens.length - 1;
    while (nameIdx >= 0 && !isIdentifier(tokens[nameIdx])) nameIdx--;
    if (nameIdx < 0) return null;

    const fieldName = tokens[nameIdx];
    let typeStr = tokens.slice(0, nameIdx).join(" ");
    // Strip modifiers
    typeStr = typeStr
        .replace(/\b(readonly|static|private|public|protected|internal|volatile)\b\s*/g, "")
        .trim();

    // Collect leading text before the field in the declaration
    const leadingText = collectLeadingText(declaration, text);

    const typeInfo = resolveType(typeStr);

    return {
        name: fieldName,
        typeText: typeStr,
        typeInfo,
        startOffset: bodyStart + bodyOffset + declaration.indexOf(text),
        endOffset: bodyStart + bodyOffset + declaration.indexOf(text) + text.length + 1, // include semicolon
        textRange: declaration.trim(),
        leadingText,
        isFixed: false,
    };
}

function looksLikeField(text: string): boolean {
    if (!text.endsWith(";")) return false;
    if (/\w+\s*\(.*\)/.test(text)) return false; // method
    if (text.includes("{") || text.includes("=>")) return false; // property or nested type
    if (/^\s*event\s/.test(text)) return false;
    if (/^\s*const\s/.test(text)) return false;
    if (/^\s*(using|namespace|class|struct|interface|enum|record)\s/.test(text)) return false;
    return text.trim().length > 0;
}

function tokenize(text: string): string[] {
    const tokens: string[] = [];
    let current = "";
    let depth = 0;

    for (const ch of text) {
        if (ch === "<") { depth++; current += ch; }
        else if (ch === ">") { depth--; current += ch; }
        else if (ch === " ") {
            if (depth === 0 && current) { tokens.push(current); current = ""; }
            else if (depth > 0) current += ch;
        } else {
            current += ch;
        }
    }
    if (current) tokens.push(current);
    return tokens;
}

function isIdentifier(s: string): boolean {
    if (!s) return false;
    return /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(s);
}

function findLastOutsideGenerics(text: string, target: string): number {
    let depth = 0;
    for (let i = text.length - 1; i >= 0; i--) {
        if (text[i] === ">") depth++;
        else if (text[i] === "<") depth--;
        else if (text[i] === target && depth === 0) return i;
    }
    return -1;
}

function collectLeadingText(fullDecl: string, fieldText: string): string {
    const idx = fullDecl.indexOf(fieldText);
    if (idx <= 0) return "";
    return fullDecl.substring(0, idx);
}

export function effectiveAlignment(field: FieldInfo, pack: number): number {
    const natural = field.typeInfo?.alignment ?? 8;
    return pack === 0 ? natural : Math.min(pack, natural);
}
