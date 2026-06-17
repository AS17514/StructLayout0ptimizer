import * as vscode from "vscode";
import { findEnclosingStruct } from "./structAnalyzer";
import { computeOptimalOrder } from "./layoutCalculator";

// ── i18n ──────────────────────────────────────────────────

const en: Record<string, string> = {
    no_struct: "No struct found at cursor position.",
    already_optimal: "Struct layout is already optimal.",
    optimized_summary: "Optimized struct, saved %s byte(s):",
    optimized_item: "  %s → %s bytes (%s%%)",
    lang_switched: "Language switched to %s.",
    lang_en: "English",
    lang_zh: "中文",
};

const zh: Record<string, string> = {
    no_struct: "光标位置未找到结构体。",
    already_optimal: "结构体布局已是最优。",
    optimized_summary: "结构体已优化，节省 %s 字节：",
    optimized_item: "  %s → %s 字节 (%s%%)",
    lang_switched: "语言已切换为 %s。",
    lang_en: "English",
    lang_zh: "中文",
};

function makeI18n(): (key: string, ...args: string[]) => string {
    const config = vscode.workspace.getConfiguration("structLayoutOptimizer");
    const lang = config.get<string>("language", "auto");
    const vsZh = vscode.env.language?.startsWith("zh") ?? true; // default zh if undetected
    const isZh = lang === "zh" || (lang === "auto" && vsZh);
    const dict = isZh ? zh : en;
    return (key: string, ...args: string[]) => {
        const template = dict[key] ?? en[key] ?? `??${key}??`;
        return template.replace(/%s/g, () => args.shift() ?? "%s");
    };
}

// ── Main Command ──────────────────────────────────────────

async function optimizeStruct() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) return;

    const document = editor.document;
    if (document.languageId !== "csharp") return;

    const i18n = makeI18n();
    const text = document.getText();
    const offset = document.offsetAt(editor.selection.active);

    const structInfo = findEnclosingStruct(text, offset);
    if (!structInfo || structInfo.fields.length < 2) {
        vscode.window.showInformationMessage(i18n("no_struct"));
        return;
    }

    const result = computeOptimalOrder(structInfo.fields, 0);
    if (result.isAlreadyOptimal || result.bytesSaved <= 0) {
        vscode.window.showInformationMessage(i18n("already_optimal"));
        return;
    }

    // Apply reordering
    const bodyStart = structInfo.bodyStart;
    const bodyEnd = structInfo.bodyEnd;
    const bodyText = text.substring(bodyStart, bodyEnd);

    // Compute body-relative field ranges sorted by original position
    const bodyRanges = structInfo.fields
        .map((f) => ({
            start: f.startOffset - bodyStart,
            end: f.endOffset - bodyStart,
        }))
        .sort((a, b) => a.start - b.start);

    // Extract gaps (non-field text between fields)
    const gaps: string[] = [];
    let prevEnd = 0;
    for (const r of bodyRanges) {
        gaps.push(bodyText.substring(prevEnd, r.start));
        prevEnd = r.end;
    }
    const trailingGap = bodyText.substring(prevEnd);

    // Rebuild body: gaps in original positions + fields in new order
    const parts: string[] = [];
    for (let i = 0; i < result.orderedFields.length; i++) {
        parts.push(gaps[i] ?? "");
        const origIdx = structInfo.fields.indexOf(result.orderedFields[i]);
        const r = bodyRanges[origIdx];
        parts.push(bodyText.substring(r.start, r.end));
    }
    parts.push(trailingGap);

    const newBody = parts.join("");
    const edit = new vscode.WorkspaceEdit();
    edit.replace(
        document.uri,
        new vscode.Range(document.positionAt(bodyStart), document.positionAt(bodyEnd)),
        newBody,
    );
    await vscode.workspace.applyEdit(edit);

    // Show notification
    const pct = result.currentLayout.totalSize > 0
        ? Math.floor((result.bytesSaved * 100) / result.currentLayout.totalSize)
        : 0;
    const msg =
        i18n("optimized_summary", String(result.bytesSaved)) +
        "\n" +
        i18n(
            "optimized_item",
            String(result.currentLayout.totalSize),
            String(result.optimalLayout.totalSize),
            String(pct),
        );
    vscode.window.showInformationMessage(msg);
}

// ── Toggle Language ───────────────────────────────────────

async function toggleLanguage() {
    const config = vscode.workspace.getConfiguration("structLayoutOptimizer");
    const current = config.get<string>("language", "auto");
    const isZhNow = current === "zh" || (current === "auto" && vscode.env.language.startsWith("zh"));
    const next = isZhNow ? "en" : "zh";
    await config.update("language", next, vscode.ConfigurationTarget.Global);
    const i18n = makeI18n();
    const langName = next === "zh" ? i18n("lang_zh") : i18n("lang_en");
    vscode.window.showInformationMessage(i18n("lang_switched", langName));
}

// ── Activation ────────────────────────────────────────────

export function activate(context: vscode.ExtensionContext) {
    context.subscriptions.push(
        vscode.commands.registerCommand("struct-layout-optimizer.optimize", optimizeStruct),
        vscode.commands.registerCommand("struct-layout-optimizer.toggleLanguage", toggleLanguage),
    );
}

export function deactivate() {}
