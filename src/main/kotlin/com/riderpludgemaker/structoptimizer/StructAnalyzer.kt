package com.riderpludgemaker.structoptimizer

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Analyzes C# struct declarations using PSI tree traversal.
 * Extracts struct metadata and field declarations from the editor context.
 */
object StructAnalyzer {

    enum class LayoutKind { SEQUENTIAL, AUTO, EXPLICIT }

    data class StructInfo(
        val structElement: PsiElement,
        val bodyOpenBraceOffset: Int,  // position of '{' in document
        val bodyCloseBraceOffset: Int, // position of '}' in document
        val layoutKind: LayoutKind,
        val pack: Int,                 // 0 = default (natural alignment)
        val explicitSize: Int,         // 0 = not set
        val hasFieldOffset: Boolean,
        val isReadOnly: Boolean,
        val isRefStruct: Boolean,
    )

    data class FieldInfo(
        val psiElement: PsiElement,
        val fieldName: String,
        val typeText: String,         // type as written in source (e.g. "int", "List<string>")
        val typeInfo: TypeSizeResolver.TypeInfo?,
        val textRange: TextRange,     // range in the document
        val leadingCommentText: String, // any // or /* */ comments before this field
        val attributeText: String,    // any [Attribute] on this field
        val isFixed: Boolean,         // fixed buffer: "fixed byte buf[48]"
    )

    /**
     * Finds the struct or record struct declaration at the given offset in the file.
     * Walks up the PSI tree from the element at the offset.
     */
    fun findEnclosingStruct(psiFile: PsiFile, offset: Int): StructInfo? {
        val element = psiFile.findElementAt(offset) ?: return null
        val structElement = findStructAncestor(element) ?: return null
        return buildStructInfo(structElement)
    }

    /**
     * Walks up the PSI tree looking for a struct/record-struct declaration element.
     */
    private fun findStructAncestor(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            if (isStructDeclaration(current)) {
                return current
            }
            // Stop if we hit the file level — we don't want to cross out of the struct body
            if (current is PsiFile) break
            current = current.parent
        }
        return null
    }

    /**
     * Checks if a PSI element represents a struct or record struct declaration.
     * Uses both element type string matching and text heuristics.
     */
    private fun isStructDeclaration(element: PsiElement): Boolean {
        val elementType = element.node?.elementType?.toString() ?: return false

        // ReSharper C# PSI types for struct declarations
        if (elementType.contains("STRUCT") ||
            elementType == "CSharpStructDeclaration" ||
            elementType == "STRUCT_DECLARATION" ||
            elementType == "CSharpRecordStructDeclaration"
        ) return true

        // Fallback: text-based detection
        val trimmed = element.text?.trimStart() ?: ""
        val lines = trimmed.lines().firstOrNull()?.trim() ?: trimmed
        return lines.contains("struct ") &&
               !lines.contains("class ") &&
               !lines.contains("interface ")
    }

    /**
     * Builds StructInfo from a struct PSI element by examining attributes and structure.
     */
    private fun buildStructInfo(structElement: PsiElement): StructInfo? {
        val text = structElement.text ?: return null
        val startOffset = structElement.textRange.startOffset

        // Find the opening and closing braces
        val openBrace = text.indexOf('{')
        val closeBrace = text.lastIndexOf('}')
        if (openBrace < 0 || closeBrace < 0 || closeBrace <= openBrace) return null

        val bodyOpenBraceOffset = startOffset + openBrace
        val bodyCloseBraceOffset = startOffset + closeBrace

        // Analyze attributes on the struct header
        val attrs = extractStructAttributes(text.substring(0, openBrace))

        // FieldOffset attributes are on fields inside the body — scan full text
        val hasAnyFieldOffset = text.contains("FieldOffset", ignoreCase = true)

        val isReadOnly = text.trimStart().startsWith("readonly ") ||
                         text.contains("readonly struct ") ||
                         text.contains("readonly record struct ")

        val isRefStruct = text.trimStart().startsWith("ref ") ||
                          text.contains("ref struct ")

        return StructInfo(
            structElement = structElement,
            bodyOpenBraceOffset = bodyOpenBraceOffset,
            bodyCloseBraceOffset = bodyCloseBraceOffset,
            layoutKind = attrs.layoutKind,
            pack = attrs.pack,
            explicitSize = attrs.size,
            hasFieldOffset = hasAnyFieldOffset,
            isReadOnly = isReadOnly,
            isRefStruct = isRefStruct,
        )
    }

    /** Extracts StructLayout attribute info from the struct declaration text. */
    private data class AttrInfo(
        val layoutKind: LayoutKind = LayoutKind.SEQUENTIAL,
        val pack: Int = 0,
        val size: Int = 0,
    )

    private fun extractStructAttributes(headerText: String): AttrInfo {
        var layoutKind = LayoutKind.SEQUENTIAL // default for C# structs
        var pack = 0
        var size = 0
        val text = headerText

        // Check for [StructLayout(...)] or StructLayoutAttribute
        val structLayoutRegex = Regex(
            """\[?\s*(?:System\.Runtime\.InteropServices\.)?StructLayout(?:Attribute)?\s*\(([^)]*)\)""",
            RegexOption.IGNORE_CASE
        )
        val match = structLayoutRegex.find(text)
        if (match != null) {
            val args = match.groupValues[1]
            // LayoutKind
            if (args.contains("LayoutKind.Explicit", ignoreCase = true) ||
                args.contains("Explicit", ignoreCase = true))
                layoutKind = LayoutKind.EXPLICIT
            else if (args.contains("LayoutKind.Auto", ignoreCase = true) ||
                     args.contains("Auto", ignoreCase = true))
                layoutKind = LayoutKind.AUTO
            else if (args.contains("LayoutKind.Sequential", ignoreCase = true) ||
                     args.contains("Sequential", ignoreCase = true))
                layoutKind = LayoutKind.SEQUENTIAL

            // Pack
            val packRegex = Regex("""Pack\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            packRegex.find(args)?.let { pack = it.groupValues[1].toIntOrNull() ?: 0 }

            // Size
            val sizeRegex = Regex("""Size\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
            sizeRegex.find(args)?.let { size = it.groupValues[1].toIntOrNull() ?: 0 }
        }

        return AttrInfo(layoutKind, pack, size)
    }

    /**
     * Extracts field declarations from the struct body.
     * Returns fields in their current declaration order.
     */
    fun extractFields(structInfo: StructInfo, document: Document): List<FieldInfo> {
        val structElement = structInfo.structElement

        // Find the body element: the child that contains '{' and '}' at the struct level
        val bodyElement = findStructBodyElement(structElement) ?: return emptyList()
        val fields = mutableListOf<FieldInfo>()

        // Collect field-like children
        for (child in bodyElement.children) {
            val field = tryParseField(child) ?: continue
            // Exclude fields with [FieldOffset] from reordering
            fields.add(field)
        }

        // Check for FieldOffset anywhere in the struct body
        val bodyText = document.getText(
            TextRange(structInfo.bodyOpenBraceOffset, structInfo.bodyCloseBraceOffset + 1)
        )
        val hasFieldOffsetInBody = bodyText.contains("FieldOffset")

        // Mark all fields if any FieldOffset exists
        if (hasFieldOffsetInBody && fields.isNotEmpty()) {
            // We'll handle this in the action layer by checking the flag
        }

        return fields
    }

    /**
     * Finds the PSI element representing the struct body (the { ... } block).
     */
    private fun findStructBodyElement(structElement: PsiElement): PsiElement? {
        // The struct body is typically a direct child with a specific type
        for (child in structElement.children) {
            val type = child.node?.elementType?.toString() ?: ""
            val text = child.text ?: ""
            if (text.startsWith('{') || type.contains("BODY") || type.contains("BLOCK")) {
                return child
            }
        }
        // Fallback: find the child with braces
        return structElement.children.firstOrNull { it.text?.startsWith('{') == true }
    }

    /**
     * Attempts to parse a PSI child as a field declaration.
     * Returns null if the element is not a field (method, property, type, etc.).
     */
    private fun tryParseField(element: PsiElement): FieldInfo? {
        val elementType = element.node?.elementType?.toString() ?: return null
        val text = element.text?.trim() ?: return null
        if (text.isEmpty()) return null

        // ReSharper PSI type names for field-like declarations
        val isField = elementType.contains("FIELD", ignoreCase = true) ||
                      elementType == "CSharpFieldDeclaration" ||
                      elementType == "CSharpMultipleFieldDeclaration" ||
                      elementType == "FIELD_DECLARATION"

        // Only fields can be reordered. Skip methods, properties, events, constructors,
        // nested types, etc.
        if (!isField) {
            // Heuristic fallback: check if the text looks like a field
            if (!looksLikeField(text)) return null
        }

        return buildFieldInfo(element, text)
    }

    /**
     * Heuristic check: does this text look like a field declaration?
     * A field typically looks like: [attrs] [modifiers] Type name [= value];
     */
    private fun looksLikeField(text: String): Boolean {
        val t = text.trim()
        // Must end with semicolon (fields and fixed buffers do)
        if (!t.endsWith(';')) return false
        // Skip methods: contain ( ) before ;
        if (Regex("""\w+\s*\(.*\)""").containsMatchIn(t)) return false
        // Skip properties: contain { } or =>
        if (t.contains('{') || t.contains("=>")) return false
        // Skip events
        if (t.startsWith("event ")) return false
        // Skip const (constants are compile-time, don't affect layout)
        if (t.startsWith("const ")) return false
        // Skip using, namespace, class, struct, interface, enum, record declarations
        if (Regex("""^\s*(using|namespace|class|struct|interface|enum|record)\s""").containsMatchIn(t)) return false
        // Must have a type and name pattern
        return t.isNotBlank()
    }

    private fun buildFieldInfo(element: PsiElement, text: String): FieldInfo? {
        val psiFile = element.containingFile ?: return null
        val range = element.textRange
        val (typeText, fieldName, isFixed) = parseFieldTypeAndName(text)

        // Collect leading text (comments and attributes before the field within the struct body)
        val leadingParts = collectLeadingText(element)

        val typeInfo = resolveFieldType(typeText, isFixed, psiFile)

        return FieldInfo(
            psiElement = element,
            fieldName = fieldName,
            typeText = typeText,
            typeInfo = typeInfo,
            textRange = range,
            leadingCommentText = leadingParts.comment,
            attributeText = leadingParts.attribute,
            isFixed = isFixed,
        )
    }

    /** Parses the type name and field name from a field declaration text. */
    private fun parseFieldTypeAndName(text: String): Triple<String, String, Boolean> {
        var t = text.trim()

        // Strip inline comments (// ...) and block comments (/* ... */) before parsing
        t = t.replace(Regex("""//.*$"""), "").trim()
        t = t.replace(Regex("""/\*.*?\*/"""), "").trim()

        // Check for fixed buffer: "fixed byte buf[48];"
        val fixedMatch = Regex("""fixed\s+(\w+)\s+(\w+)\s*\[.*\]\s*;?\s*$""").find(t)
        if (fixedMatch != null) {
            val elementType = fixedMatch.groupValues[1]
            val name = fixedMatch.groupValues[2]
            return Triple("fixed $elementType", name, true)
        }

        // Remove trailing semicolon (may be separated from the name by whitespace)
        t = t.removeSuffix(";").trim()

        // Remove initializer: "= value" at the end
        // Handle nested generics in the type, so find the last '=' that's not inside <>
        val eqIdx = findLastCharOutsideGenerics(t, '=')
        if (eqIdx >= 0) {
            t = t.substring(0, eqIdx).trim()
        }

        // Split into tokens: modifiers + type + name
        // The field name is the last identifier before ; or =
        // We need to handle types like "Dictionary<int, string>" where the type has spaces
        val tokens = tokenizeFieldDeclaration(t)
        if (tokens.size < 2) return Triple(t, "", false)

        // Find the last token that looks like a field name
        var nameIdx = tokens.lastIndex
        while (nameIdx >= 0 && !isValidIdentifier(tokens[nameIdx])) {
            nameIdx--
        }
        if (nameIdx < 0) return Triple(t, tokens.last(), false)

        val fieldName = tokens[nameIdx]
        // Type is everything before the field name
        val typeStr = tokens.subList(0, nameIdx).joinToString(" ")
            .replace("readonly ", "").replace("static ", "")
            .replace("private ", "").replace("public ", "")
            .replace("protected ", "").replace("internal ", "")
            .replace("volatile ", "").trim()

        return Triple(typeStr, fieldName, false)
    }

    /** Tokenizes a field declaration, keeping generic brackets together. */
    private fun tokenizeFieldDeclaration(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0

        for (ch in text) {
            when (ch) {
                '<' -> { depth++; current.append(ch) }
                '>' -> { depth--; current.append(ch) }
                ' ' -> {
                    if (depth == 0 && current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    } else if (depth > 0) {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }

    private fun findLastCharOutsideGenerics(text: String, target: Char): Int {
        var depth = 0
        for (i in text.lastIndex downTo 0) {
            when (text[i]) {
                '>' -> depth++
                '<' -> depth--
                target -> if (depth == 0) return i
            }
        }
        return -1
    }

    private fun isValidIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        // Identifiers must start with letter or _
        if (!s[0].isLetter() && s[0] != '_') return false
        // Rest must be alphanumeric or _
        return s.all { it.isLetterOrDigit() || it == '_' }
    }

    /** Resolves the type size/alignment for a field, searching the PSI file for custom structs. */
    private fun resolveFieldType(typeText: String, isFixed: Boolean, psiFile: PsiFile): TypeSizeResolver.TypeInfo? {
        if (isFixed) {
            // "fixed byte buf[48]" → extract element type and size
            val match = Regex("""fixed\s+(\w+)\s+\w+\s*\[(\d+)\]""").find(typeText)
            if (match != null) {
                val elementType = match.groupValues[1]
                val count = match.groupValues[2].toIntOrNull() ?: return null
                val elemInfo = resolveStructType(elementType, psiFile)
                if (elemInfo != null) {
                    return TypeSizeResolver.TypeInfo(
                        size = elemInfo.size * count,
                        alignment = elemInfo.alignment
                    )
                }
            }
            return null
        }

        return resolveStructType(typeText, psiFile)
    }

    /**
     * Collects comments and attributes that appear before a field element.
     * This traverses the PSI siblings before this element.
     */
    private data class LeadingText(val comment: String, val attribute: String)

    private fun collectLeadingText(element: PsiElement): LeadingText {
        val commentParts = mutableListOf<String>()
        val attrParts = mutableListOf<String>()

        var sibling = element.prevSibling
        while (sibling != null) {
            val siblingText = sibling.text?.trim() ?: ""
            val siblingType = sibling.node?.elementType?.toString() ?: ""

            when {
                siblingType.contains("COMMENT") || siblingText.startsWith("//") || siblingText.startsWith("/*") -> {
                    commentParts.add(0, siblingText)
                }
                siblingType.contains("ATTRIBUTE") || siblingText.startsWith('[') -> {
                    attrParts.add(0, siblingText)
                }
                else -> break // Stop at non-comment, non-attribute siblings
            }
            sibling = sibling.prevSibling
        }

        return LeadingText(
            comment = commentParts.joinToString("\n"),
            attribute = attrParts.joinToString("\n")
        )
    }

    /**
     * Extracts the effective alignment for a field, considering Pack.
     */
    fun effectiveAlignment(field: FieldInfo, pack: Int): Int {
        val natural = field.typeInfo?.alignment ?: return 8 // conservative default
        return if (pack == 0) natural else minOf(pack, natural)
    }

    /**
     * Checks whether the struct can be safely reordered.
     */
    fun canReorder(info: StructInfo): String? {
        return when {
            info.layoutKind == LayoutKind.EXPLICIT ->
                Messages.get("explicit_layout")
            info.layoutKind == LayoutKind.AUTO ->
                Messages.get("auto_layout")
            info.hasFieldOffset ->
                Messages.get("has_field_offset")
            else -> null // OK to reorder
        }
    }

    /**
     * Checks if reordering needs user confirmation (e.g., interop scenarios).
     */
    fun needsConfirmation(info: StructInfo): Boolean {
        return info.pack != 0 || info.explicitSize != 0
    }

    // ========== Custom struct type resolution ==========

    private val structTypeCache = mutableMapOf<String, TypeSizeResolver.TypeInfo?>()

    /** Clears the struct type resolution cache. Call at the start of each action invocation. */
    fun clearStructCache() { structTypeCache.clear() }

    /**
     * Resolves a type name to its (size, alignment), searching the PSI file for struct definitions.
     * Falls back to the built-in type table in TypeSizeResolver.
     */
    fun resolveStructType(typeName: String, psiFile: PsiFile): TypeSizeResolver.TypeInfo? {
        // Try built-in types first
        val builtin = TypeSizeResolver.resolveWithGenerics(typeName)
        if (builtin != null) return builtin

        val baseName = typeName.substringBefore('<').trim()
        if (baseName.isEmpty()) return null

        // Check cache
        structTypeCache[baseName]?.let { return it }

        // Guard against circular references
        structTypeCache[baseName] = null

        // Search for struct definition in the PSI file
        val structElement = findStructDefinition(psiFile, baseName)
        if (structElement != null) {
            val result = computeStructTypeInfo(structElement, psiFile)
            structTypeCache[baseName] = result
            return result
        }

        return null
    }

    /**
     * Finds all struct declarations nested inside the given struct's body.
     * Recursively searches all nesting levels.
     */
    fun findNestedStructs(structInfo: StructInfo): List<StructInfo> {
        val bodyElement = findStructBodyElement(structInfo.structElement) ?: return emptyList()
        val result = mutableListOf<StructInfo>()
        for (child in bodyElement.children) {
            if (isStructDeclaration(child)) {
                buildStructInfo(child)?.let { nestedInfo ->
                    result.add(nestedInfo)
                    result.addAll(findNestedStructs(nestedInfo))
                }
            }
        }
        return result
    }

    /** Searches the entire PSI file tree for a struct declaration with the given name. */
    private fun findStructDefinition(psiFile: PsiFile, structName: String): PsiElement? {
        return findStructInElement(psiFile, structName)
    }

    private fun findStructInElement(element: PsiElement, structName: String): PsiElement? {
        for (child in element.children) {
            if (isStructNamed(child, structName)) return child
            // Recurse into child elements (e.g. namespace blocks, class bodies)
            val found = findStructInElement(child, structName)
            if (found != null) return found
        }
        return null
    }

    private fun isStructNamed(element: PsiElement, name: String): Boolean {
        if (!isStructDeclaration(element)) return false
        val text = element.text?.trim() ?: return false
        val regex = Regex("""struct\s+(\w+)""")
        val match = regex.find(text) ?: return false
        return match.groupValues[1] == name
    }

    /**
     * Computes the (size, alignment) of a custom struct by analyzing its field declarations.
     * Uses resolveStructType recursively for field types.
     */
    private fun computeStructTypeInfo(
        structElement: PsiElement,
        psiFile: PsiFile,
    ): TypeSizeResolver.TypeInfo {
        val text = structElement.text ?: return TypeSizeResolver.TypeInfo(8, 8)

        val openBrace = text.indexOf('{')
        val closeBrace = text.lastIndexOf('}')
        if (openBrace < 0 || closeBrace <= openBrace) return TypeSizeResolver.TypeInfo(8, 8)

        val bodyText = text.substring(openBrace + 1, closeBrace)
        val declarations = splitTopLevelDeclarations(bodyText)

        var currentOffset = 0
        var maxAlignment = 1

        for (decl in declarations) {
            val (typeName, _, isFixed) = parseFieldTypeAndName(decl)
            if (typeName.isEmpty()) continue

            val typeInfo = if (isFixed) {
                val fixMatch = Regex("""fixed\s+(\w+)\s+\w+\s*\[(\d+)\]""").find(decl)
                if (fixMatch != null) {
                    val elemInfo = resolveStructType(fixMatch.groupValues[1], psiFile)
                    val count = fixMatch.groupValues[2].toIntOrNull()
                    if (elemInfo != null && count != null)
                        TypeSizeResolver.TypeInfo(elemInfo.size * count, elemInfo.alignment)
                    else continue
                } else continue
            } else {
                resolveStructType(typeName, psiFile)
            } ?: continue

            if (typeInfo.alignment > maxAlignment) maxAlignment = typeInfo.alignment
            val padding = TypeSizeResolver.alignUp(currentOffset, typeInfo.alignment) - currentOffset
            currentOffset = currentOffset + padding + typeInfo.size
        }

        val tailPadding = TypeSizeResolver.alignUp(currentOffset, maxAlignment) - currentOffset
        return TypeSizeResolver.TypeInfo(currentOffset + tailPadding, maxAlignment)
    }

    /**
     * Splits struct body text into top-level field declarations.
     * Semicolons inside nested { } or < > are ignored — only top-level ones count.
     */
    private fun splitTopLevelDeclarations(bodyText: String): List<String> {
        val declarations = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in bodyText.indices) {
            when (bodyText[i]) {
                '{', '<' -> depth++
                '}', '>' -> depth--
                ';' -> {
                    if (depth == 0) {
                        declarations.add(bodyText.substring(start, i + 1))
                        start = i + 1
                    }
                }
            }
        }
        return declarations
    }
}
