# Struct Layout Optimizer / 结构体布局优化器

[English](#english) | [中文](#中文)

---

## English

Rider plugin that automatically reorders C# struct fields for optimal memory layout, minimizing alignment padding.

### How it works

C# structs use `LayoutKind.Sequential` by default. Field order affects memory size due to alignment padding:

```csharp
// 24 bytes  →  Ctrl+Alt+R  →  16 bytes (33% saved)
struct Bad  { byte a; long b; int c; byte d; }
struct Good { long b; int c; byte a; byte d; }
```

Place the cursor inside any struct and press `Ctrl+Alt+R` — the plugin reorders fields by descending alignment to eliminate unnecessary padding.

### Features

- One-click optimization via `Ctrl+Alt+R` or right-click context menu
- Recursively optimizes nested struct declarations
- Resolves custom struct field types for accurate alignment calculation
- Preserves comments, attributes, methods, and properties in their original positions
- Handles `LayoutKind.Explicit`, `[FieldOffset]`, `fixed` buffers, and `readonly` structs
- Chinese / English bilingual UI (auto-detects system language, manually toggleable)

### Installation

- **JetBrains Marketplace**: Search "Struct Layout Optimizer" in Rider → Settings → Plugins
- **Manual**: Download the `.zip` from [Releases](https://github.com/AS17514/StructLayout0ptimizer/releases) → Settings → Plugins → ⚙️ → Install Plugin from Disk

### Build from source

```
git clone https://github.com/AS17514/StructLayout0ptimizer.git
cd StructLayout0ptimizer
./gradlew.bat buildPlugin
```

Requires JDK 17+ and Gradle 8.10 (the wrapper downloads it automatically).

### License

Apache 2.0

---

## 中文

自动重排 C# 结构体字段顺序的 Rider 插件，消除内存对齐产生的 padding，优化内存占用。

### 原理

C# 结构体默认使用 `LayoutKind.Sequential`。字段声明顺序会影响内存大小——不同对齐需求的字段交错排列会产生 padding 空洞：

```csharp
// 24 字节  →  Ctrl+Alt+R  →  16 字节（节省 33%）
struct 差 { byte a; long b; int c; byte d; }
struct 好 { long b; int c; byte a; byte d; }
```

把光标放在任意结构体内，按 `Ctrl+Alt+R` 即可按对齐量从大到小重排字段。

### 功能

- `Ctrl+Alt+R` 一键优化，或右键菜单触发
- 递归处理嵌套结构体类型声明
- 自动解析自定义结构体字段类型，准确计算对齐
- 保留注释、特性（Attribute）、方法、属性等非字段成员的原始位置
- 正确处理 `LayoutKind.Explicit`、`[FieldOffset]`、`fixed` 缓冲区、`readonly` 等场景
- 中英双语界面（跟随系统语言，可手动切换）

### 安装

- **JetBrains 插件商店**：Rider → Settings → Plugins → 搜索 "Struct Layout Optimizer"
- **手动安装**：从 [Releases](https://github.com/AS17514/StructLayout0ptimizer/releases) 下载 `.zip` → Settings → Plugins → ⚙️ → Install Plugin from Disk

### 从源码构建

```
git clone https://github.com/AS17514/StructLayout0ptimizer.git
cd StructLayout0ptimizer
./gradlew.bat buildPlugin
```

需要 JDK 17+，Gradle 8.10（wrapper 自动下载）。

### 开源协议

Apache 2.0
