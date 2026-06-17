# Struct Layout Optimizer / 结构体布局优化器

[English](#english) | [中文](#中文)

---

## English

Automatically reorders C# struct fields for optimal memory layout, eliminating alignment padding.

### How it works

```csharp
// 24 bytes  →  Ctrl+Alt+R  →  16 bytes (33% saved)
struct Bad  { byte a; long b; int c; byte d; }
struct Good { long b; int c; byte a; byte d; }
```

Place the cursor inside any struct and press `Ctrl+Alt+R`.

### Supported Platforms

| Platform | Status |
|----------|--------|
| JetBrains Rider | Released |
| Visual Studio Code | Released |
| Visual Studio 2022 | In progress |

### Features

- One-click optimization via `Ctrl+Alt+R` or right-click context menu
- Recursively optimizes nested struct declarations
- Resolves custom struct field types for accurate alignment calculation
- Preserves comments, attributes, methods, and properties
- Handles `LayoutKind.Explicit`, `[FieldOffset]`, `fixed` buffers, `readonly` structs
- Chinese / English bilingual UI

### Installation

**Rider**: Settings → Plugins → Marketplace → Search "Struct Layout Optimizer"

**VS Code**: Download `.vsix` from [Releases](https://github.com/AS17514/StructLayout0ptimizer/releases) → `Ctrl+Shift+P` → "Install from VSIX"

Or build from source:
```
# Rider
./gradlew.bat buildPlugin

# VS Code
cd vscode && npm install && npx tsc -p ./ && vsce package
```

### License

Apache 2.0

---

## 中文

自动重排 C# 结构体字段顺序，消除内存对齐 padding，优化内存占用。

### 原理

```csharp
// 24 字节  →  Ctrl+Alt+R  →  16 字节（节省 33%）
struct 差 { byte a; long b; int c; byte d; }
struct 好 { long b; int c; byte a; byte d; }
```

光标放在结构体内，按 `Ctrl+Alt+R` 即可。

### 支持的平台

| 平台 | 状态 |
|------|------|
| JetBrains Rider | 已发布 |
| Visual Studio Code | 已发布 |
| Visual Studio 2022 | 开发中 |

### 功能

- `Ctrl+Alt+R` 一键优化，或右键菜单触发
- 递归处理嵌套结构体类型声明
- 自动解析自定义结构体字段类型，准确计算对齐
- 保留注释、特性、方法、属性等非字段成员
- 正确处理 `LayoutKind.Explicit`、`[FieldOffset]`、`fixed` 等场景
- 中英双语界面

### 安装

**Rider**：Settings → Plugins → Marketplace → 搜索 "Struct Layout Optimizer"

**VS Code**：从 [Releases](https://github.com/AS17514/StructLayout0ptimizer/releases) 下载 `.vsix` → `Ctrl+Shift+P` → "Install from VSIX"

或从源码构建：
```
# Rider
./gradlew.bat buildPlugin

# VS Code
cd vscode && npm install && npx tsc -p ./ && vsce package
```

### 开源协议

Apache 2.0
