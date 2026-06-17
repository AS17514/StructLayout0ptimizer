using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using System.Collections.Generic;
using System.Linq;

namespace StructLayoutOptimizer.VS;

public class FieldInfo
{
    public string Name { get; set; } = "";
    public string TypeText { get; set; } = "";
    public TypeSizeInfo? TypeInfo { get; set; }
    public Microsoft.CodeAnalysis.Text.TextSpan Span { get; set; }
    public SyntaxTriviaList LeadingTrivia { get; set; }
    public string? LeadingComment { get; set; }
    public bool IsFixed { get; set; }
}

public class StructInfo
{
    public StructDeclarationSyntax Syntax { get; set; } = null!;
    public int Pack { get; set; }
    public bool IsReadOnly { get; set; }
    public List<FieldInfo> Fields { get; set; } = new();
}

public enum LayoutKindEnum { Sequential, Auto, Explicit }

public static class StructAnalyzer
{
    public static StructInfo? FindEnclosingStruct(SyntaxNode root, int position)
    {
        var node = root.FindNode(new Microsoft.CodeAnalysis.Text.TextSpan(position, 0));
        var structDecl = node?.AncestorsAndSelf()
            .OfType<StructDeclarationSyntax>()
            .FirstOrDefault();
        if (structDecl == null) return null;

        return BuildStructInfo(structDecl);
    }

    private static StructInfo BuildStructInfo(StructDeclarationSyntax syntax)
    {
        var (kind, pack) = ExtractLayoutAttribute(syntax);

        var fields = new List<FieldInfo>();
        foreach (var member in syntax.Members)
        {
            if (member is FieldDeclarationSyntax fieldDecl)
            {
                foreach (var variable in fieldDecl.Declaration.Variables)
                {
                    fields.Add(new FieldInfo
                    {
                        Name = variable.Identifier.Text,
                        TypeText = fieldDecl.Declaration.Type.ToString(),
                        Span = variable.Span,
                        LeadingTrivia = fieldDecl.GetLeadingTrivia(),
                        LeadingComment = ExtractComments(fieldDecl.GetLeadingTrivia()),
                        IsFixed = false,
                    });
                }
            }
        }

        return new StructInfo
        {
            Syntax = syntax,
            Pack = pack,
            IsReadOnly = syntax.Modifiers.Any(SyntaxKind.ReadOnlyKeyword),
            Fields = fields,
        };
    }

    public static (LayoutKindEnum Kind, int Pack) ExtractLayoutAttribute(
        StructDeclarationSyntax syntax)
    {
        var kind = LayoutKindEnum.Sequential;
        var pack = 0;

        foreach (var attrList in syntax.AttributeLists)
        {
            foreach (var attr in attrList.Attributes)
            {
                var name = attr.Name.ToString();
                if (!name.Contains("StructLayout")) continue;

                var args = attr.ArgumentList?.Arguments;
                if (args == null) continue;

                foreach (var arg in args.Value)
                {
                    var text = arg.ToString();
                    if (text.Contains("Explicit"))
                        kind = LayoutKindEnum.Explicit;
                    else if (text.Contains("Auto"))
                        kind = LayoutKindEnum.Auto;
                }

                foreach (var arg in args.Value)
                {
                    if (arg.NameEquals?.Name.Identifier.Text == "Pack")
                        int.TryParse(arg.Expression.ToString(), out pack);
                }
            }
        }

        return (kind, pack);
    }

    public static void ResolveFieldTypes(
        List<FieldInfo> fields,
        StructDeclarationSyntax syntax,
        SemanticModel model)
    {
        foreach (var field in fields)
        {
            var variable = syntax.DescendantNodes()
                .OfType<VariableDeclaratorSyntax>()
                .FirstOrDefault(v => v.Span == field.Span);

            if (variable == null) continue;

            var declaration = variable.Parent as VariableDeclarationSyntax;
            var typeSymbol = declaration != null
                ? model.GetTypeInfo(declaration.Type).Type
                : null;

            if (typeSymbol != null)
                field.TypeInfo = TypeSizeTable.Resolve(typeSymbol, model);
        }
    }

    public static bool CanReorder(StructInfo info)
    {
        var (kind, _) = ExtractLayoutAttribute(info.Syntax);
        if (kind == LayoutKindEnum.Explicit) return false;
        if (kind == LayoutKindEnum.Auto) return false;
        return true;
    }

    private static string? ExtractComments(SyntaxTriviaList trivia)
    {
        var comments = trivia
            .Where(t => t.IsKind(SyntaxKind.SingleLineCommentTrivia)
                     || t.IsKind(SyntaxKind.MultiLineCommentTrivia))
            .Select(t => t.ToString().Trim());
        return string.Join("\n", comments);
    }
}
