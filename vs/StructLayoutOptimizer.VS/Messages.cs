using System.Collections.Generic;

namespace StructLayoutOptimizer.VS;

internal static class Messages
{
    private static string _lang = "auto";

    public static void Toggle()
    {
        _lang = IsZh() ? "en" : "zh";
    }

    public static bool IsZh()
    {
        if (_lang == "zh") return true;
        if (_lang == "en") return false;
        // auto: use system locale
        return System.Globalization.CultureInfo.CurrentUICulture.Name.StartsWith("zh");
    }

    public static string Get(string key, params object[] args)
    {
        var dict = IsZh() ? zh : en;
        if (dict.TryGetValue(key, out var template))
            return string.Format(template, args);
        return en.TryGetValue(key, out var enTemplate)
            ? string.Format(enTemplate, args)
            : $"??{key}??";
    }

    private static readonly Dictionary<string, string> en = new()
    {
        ["no_struct"] = "No struct found at cursor position.",
        ["already_optimal"] = "Struct layout is already optimal.",
        ["optimized_summary"] = "Optimized struct, saved {0} byte(s):",
        ["optimized_item"] = "  {0} → {1} bytes ({2}%)",
        ["not_reorderable"] = "Struct uses LayoutKind.{0} — field order cannot be changed.",
        ["toggle_title"] = "Struct Optimizer: EN/中文",
        ["lang_switched"] = "Language switched to {0}.",
        ["lang_en"] = "English",
        ["lang_zh"] = "中文",
    };

    private static readonly Dictionary<string, string> zh = new()
    {
        ["no_struct"] = "光标位置未找到结构体。",
        ["already_optimal"] = "结构体布局已是最优。",
        ["optimized_summary"] = "结构体已优化，节省 {0} 字节：",
        ["optimized_item"] = "  {0} → {1} 字节 ({2}%)",
        ["not_reorderable"] = "结构体使用了 LayoutKind.{0} — 无法重排字段。",
        ["toggle_title"] = "结构体优化器: EN/中文",
        ["lang_switched"] = "语言已切换为 {0}。",
        ["lang_en"] = "English",
        ["lang_zh"] = "中文",
    };
}
