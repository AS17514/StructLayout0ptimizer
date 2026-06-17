package com.riderpludgemaker.structoptimizer

import com.intellij.ide.util.PropertiesComponent
import java.util.*

/**
 * Centralized i18n message provider.
 *
 * Default: follows IDE system locale (Chinese OS → Chinese, otherwise English).
 * Use [toggle] to switch manually; choice is persisted across restarts.
 */
object Messages {

    private const val KEY_LANG = "structoptimizer.language"
    private var _lang: String = PropertiesComponent.getInstance().getValue(KEY_LANG, "auto")

    /** "auto", "zh", or "en" */
    val currentLang: String get() = _lang

    /** Returns true when the effective language is Chinese. */
    private fun isZh(): Boolean = when (_lang) {
        "zh" -> true
        "en" -> false
        else -> Locale.getDefault().language == "zh"
    }

    /** Toggles between Chinese and English, persists the choice. */
    fun toggle() {
        _lang = if (isZh()) "en" else "zh"
        PropertiesComponent.getInstance().setValue(KEY_LANG, _lang)
    }

    /** Toggles to a specific language. */
    fun setLanguage(lang: String) {
        _lang = lang
        PropertiesComponent.getInstance().setValue(KEY_LANG, _lang)
    }

    /**
     * Returns the localized string for [key], formatting with [args] if provided.
     *
     * Usage: Messages.get("no_struct")  or  Messages.get("optimized", count, bytes)
     */
    fun get(key: String, vararg args: Any): String {
        val template = if (isZh()) zh[key] else en[key]
        return template?.format(*args) ?: en[key]?.format(*args) ?: "??$key??"
    }

    // ── English ──────────────────────────────────────────────
    private val en = mapOf(
        "no_editor" to "No editor available.",
        "not_cs_file" to "This action only works in C# (.cs) files.",
        "no_struct" to "No struct found at cursor position.",
        "already_optimal" to "Struct layout is already optimal.",
        "already_optimal_detail" to "Struct layout is already optimal.\nCurrent size: %d bytes.",
        "optimized_summary" to "Optimized %d struct(s), saved %d byte(s):",
        "optimized_item" to "  %s: %d → %d bytes (%d%%)",
        "saves_bytes" to "Optimization saves %d bytes (%d%%).",
        "size_comparison" to "Current size: %d bytes → Optimal: %d bytes.",
        "padding_reduced" to "Padding reduced: %d → %d bytes.",
        "no_improvement" to "No size improvement possible.",
        "no_improvement_detail" to "No size improvement possible.\nCurrent size: %d bytes.",
        "current_size" to "Current size: %d bytes.",
        "warnings_header" to "Warnings:",
        "unknown_type" to "Unknown type '%s' for field '%s' — alignment assumed as 8 (pointer).",
        "explicit_layout" to "Struct uses LayoutKind.Explicit — field order cannot be changed.",
        "auto_layout" to "Struct uses LayoutKind.Auto — the CLR already optimizes field order.",
        "has_field_offset" to "Struct contains [FieldOffset] attributes — field order cannot be changed.",
        "toggle_lang_title" to "Switch Language (EN/中文)",
        "toggle_lang_msg" to "Language switched to %s.",
        "lang_en" to "English",
        "lang_zh" to "中文",
    )

    // ── Chinese ──────────────────────────────────────────────
    private val zh = mapOf(
        "no_editor" to "没有可用的编辑器。",
        "not_cs_file" to "此操作仅适用于 C# (.cs) 文件。",
        "no_struct" to "光标位置未找到结构体。",
        "already_optimal" to "结构体布局已是最优。",
        "already_optimal_detail" to "结构体布局已是最优。\n当前大小: %d 字节。",
        "optimized_summary" to "优化了 %d 个结构体，节省 %d 字节：",
        "optimized_item" to "  %s: %d → %d 字节 (%d%%)",
        "saves_bytes" to "优化节省了 %d 字节 (%d%%)。",
        "size_comparison" to "当前大小: %d 字节 → 优化后: %d 字节。",
        "padding_reduced" to "对齐填充减少: %d → %d 字节。",
        "no_improvement" to "无法进一步优化。",
        "no_improvement_detail" to "无法进一步优化。\n当前大小: %d 字节。",
        "current_size" to "当前大小: %d 字节。",
        "warnings_header" to "警告：",
        "unknown_type" to "未知类型 '%s'，字段 '%s' — 对齐假设为 8（指针）。",
        "explicit_layout" to "结构体使用了 LayoutKind.Explicit — 无法重排字段。",
        "auto_layout" to "结构体使用了 LayoutKind.Auto — CLR 已自动优化字段顺序。",
        "has_field_offset" to "结构体包含 [FieldOffset] 特性 — 无法重排字段。",
        "toggle_lang_title" to "切换语言 (EN/中文)",
        "toggle_lang_msg" to "语言已切换为 %s。",
        "lang_en" to "English",
        "lang_zh" to "中文",
    )
}
