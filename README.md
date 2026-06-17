# Struct Layout Optimizer

Rider plugin that automatically reorders C# struct fields for optimal memory layout, minimizing alignment padding.

## How it works

C# structs use `LayoutKind.Sequential` by default. Field order affects memory size due to alignment padding:

```csharp
// 24 bytes  →  Ctrl+Alt+R  →  16 bytes (33% saved)
struct Bad  { byte a; long b; int c; byte d; }
struct Good { long b; int c; byte a; byte d; }
```

Place the cursor inside any struct and press `Ctrl+Alt+R` — the plugin reorders fields by descending alignment to eliminate unnecessary padding.

## Features

- One-click optimization via `Ctrl+Alt+R` or right-click context menu
- Recursively optimizes nested struct declarations
- Resolves custom struct field types for accurate alignment calculation
- Preserves comments, attributes, methods, and properties in their original positions
- Handles `LayoutKind.Explicit`, `[FieldOffset]`, `fixed` buffers, and `readonly` structs
- Chinese / English bilingual UI (auto-detects system language, manually toggleable)

## Installation

- **JetBrains Marketplace**: Search "Struct Layout Optimizer" in Rider → Settings → Plugins
- **Manual**: Download the `.zip` from [Releases](https://github.com/AS17514/StructLayout0ptimizer/releases) → Settings → Plugins → ⚙️ → Install Plugin from Disk

## Build from source

```
git clone https://github.com/AS17514/StructLayout0ptimizer.git
cd StructLayout0ptimizer
./gradlew.bat buildPlugin
```

Requires JDK 17+ and Gradle 8.10 (the wrapper downloads it automatically).

## License

Apache 2.0
