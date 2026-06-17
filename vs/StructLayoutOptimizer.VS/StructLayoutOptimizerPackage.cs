using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.ComponentModelHost;
using Microsoft.VisualStudio.Editor;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;
using Microsoft.VisualStudio.TextManager.Interop;
using System;
using System.Collections.Generic;
using System.ComponentModel.Design;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using RoslynTextSpan = Microsoft.CodeAnalysis.Text.TextSpan;
using RoslynTextChange = Microsoft.CodeAnalysis.Text.TextChange;
using Task = System.Threading.Tasks.Task;

namespace StructLayoutOptimizer.VS;

[PackageRegistration(UseManagedResourcesOnly = true, AllowsBackgroundLoading = true)]
[InstalledProductRegistration("#110", "#112", "1.0.0", IconResourceID = 400)]
[ProvideMenuResource("Menus.ctmenu", 1)]
[Guid(Guids.PackageString)]
public sealed class StructLayoutOptimizerPackage : AsyncPackage
{
    protected override async Task InitializeAsync(
        CancellationToken cancellationToken,
        IProgress<ServiceProgressData> progress)
    {
        await JoinableTaskFactory.SwitchToMainThreadAsync(cancellationToken);

        var commandService = (IMenuCommandService?)await GetServiceAsync(typeof(IMenuCommandService));
        if (commandService == null) return;

        commandService.AddCommand(new MenuCommand(
            (s, e) => OptimizeStruct(),
            new CommandID(Guids.CmdSet, Guids.CmdOptimize)));

        commandService.AddCommand(new MenuCommand(
            (s, e) => ToggleLanguage(),
            new CommandID(Guids.CmdSet, Guids.CmdToggleLang)));
    }

    private void OptimizeStruct()
    {
        ThreadHelper.ThrowIfNotOnUIThread();

        try
        {
            var textManager = (IVsTextManager?)GetService(typeof(SVsTextManager));
            if (textManager == null) return;
            textManager.GetActiveView(1, null, out var vsTextView);
            if (vsTextView == null) return;

            var componentModel = (IComponentModel?)GetService(typeof(SComponentModel));
            if (componentModel == null) return;
            var editorAdapter = componentModel.GetService<IVsEditorAdaptersFactoryService>();
            var wpfView = editorAdapter.GetWpfTextView(vsTextView);
            if (wpfView == null) return;

            var snapshot = wpfView.TextSnapshot;
            if (!snapshot.ContentType.IsOfType("CSharp")) return;

            // Get Roslyn Document via workspace
            var workspace = componentModel.GetService<Microsoft.VisualStudio.LanguageServices.VisualStudioWorkspace>();
            if (workspace == null) return;

            var caretPos = wpfView.Caret.Position.BufferPosition;
            var filePath = wpfView.TextDataModel.DocumentBuffer.Properties
                .GetProperty<Microsoft.VisualStudio.Text.ITextDocument>(typeof(Microsoft.VisualStudio.Text.ITextDocument))
                ?.FilePath;

            if (filePath == null) return;

            var document = workspace.CurrentSolution.Projects
                .SelectMany(p => p.Documents)
                .FirstOrDefault(d => d.FilePath == filePath);
            if (document == null) return;

            var model = document.GetSemanticModelAsync().GetAwaiter().GetResult();
            var root = document.GetSyntaxRootAsync().GetAwaiter().GetResult();
            if (root == null || model == null) return;

            var structInfo = StructAnalyzer.FindEnclosingStruct(root, caretPos.Position);
            if (structInfo == null || structInfo.Fields.Count < 2)
            {
                Status(Messages.Get("no_struct"));
                return;
            }

            if (!StructAnalyzer.CanReorder(structInfo))
            {
                var (kind, _) = StructAnalyzer.ExtractLayoutAttribute(structInfo.Syntax);
                Status(Messages.Get("not_reorderable", kind.ToString()));
                return;
            }

            structInfo = StructAnalyzer.FindEnclosingStruct(root, caretPos.Position)!;
            StructAnalyzer.ResolveFieldTypes(structInfo.Fields, structInfo.Syntax, model);

            var result = LayoutCalculator.ComputeOptimalOrder(structInfo.Fields, structInfo.Pack);
            if (result.IsAlreadyOptimal || result.BytesSaved <= 0)
            {
                Status(Messages.Get("already_optimal"));
                return;
            }

            // Apply reordering
            var memberSpan = new RoslynTextSpan(
                structInfo.Syntax.OpenBraceToken.Span.End,
                structInfo.Syntax.CloseBraceToken.Span.Start - structInfo.Syntax.OpenBraceToken.Span.End);


            var fields = structInfo.Fields;
            var orderedIndexes = result.OrderedFields.Select(o => fields.IndexOf(o)).ToList();

            // Full extent of each field (including leading trivia)
            var fieldExtents = fields.Select(f =>
            {
                var trivia = f.LeadingTrivia.ToFullString();
                return new RoslynTextSpan(
                    f.Span.Start - trivia.Length,
                    f.Span.End);
            }).ToList();

            // Sort by position
            var fieldPositions = fieldExtents
                .Select((span, i) => (span, i))
                .OrderBy(x => x.span.Start)
                .ToList();

            var sourceText = root.GetText();
            var newBody = new StringBuilder();
            int prevEnd = memberSpan.Start;

            foreach (var (span, origIdx) in fieldPositions)
            {
                int orderPos = orderedIndexes.IndexOf(origIdx);
                int orderedOrigIdx = orderedIndexes[orderPos];
                var orderedSpan = fieldExtents[orderedOrigIdx];

                // Gap before this slot
                newBody.Append(sourceText.GetSubText(
                    RoslynTextSpan.FromBounds(prevEnd, span.Start)).ToString());
                // Reordered field text
                newBody.Append(sourceText.GetSubText(
                    RoslynTextSpan.FromBounds(orderedSpan.Start, span.End + 1)).ToString());
                prevEnd = span.End + 1;
            }
            // Trailing gap
            newBody.Append(sourceText.GetSubText(
                RoslynTextSpan.FromBounds(prevEnd, memberSpan.End)).ToString());

            var change = new RoslynTextChange(memberSpan, newBody.ToString());
            var newSourceText = sourceText.WithChanges(change);
            var newDocument = document.WithText(newSourceText);
            var newSolution = newDocument.Project.Solution;
            workspace.TryApplyChanges(newSolution);

            int pct = result.Current.TotalSize > 0
                ? (result.BytesSaved * 100) / result.Current.TotalSize
                : 0;
            Status(Messages.Get("optimized_summary", result.BytesSaved) + "\n" +
                   Messages.Get("optimized_item",
                       result.Current.TotalSize, result.Optimal.TotalSize, pct));
        }
        catch (Exception ex)
        {
            Status($"Error: {ex.Message}");
        }
    }

    private void ToggleLanguage()
    {
        ThreadHelper.ThrowIfNotOnUIThread();
        Messages.Toggle();
        var name = Messages.IsZh() ? Messages.Get("lang_zh") : Messages.Get("lang_en");
        Status(Messages.Get("lang_switched", name));
    }

    private void Status(string message)
    {
        ThreadHelper.ThrowIfNotOnUIThread();
        try
        {
            var sb = (IVsStatusbar?)GetService(typeof(SVsStatusbar));
            sb?.SetText(message);
        }
        catch { }
    }
}

internal static class Guids
{
    public const string PackageString = "A23AE15F-6AA8-4A0E-BCE7-9C721A0F82CD";
    public static readonly Guid Package = new(PackageString);
    public static readonly Guid CmdSet = new("5F9F1D62-A9EE-4E0B-ADDE-4CEB27DC5435");
    public const int CmdOptimize = 0x0101;
    public const int CmdToggleLang = 0x0102;
}
