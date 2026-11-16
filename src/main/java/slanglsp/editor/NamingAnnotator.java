package slanglsp.editor;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.lang.annotation.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import com.intellij.openapi.util.TextRange;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.psi.PsiFile;
import com.intellij.application.options.CodeStyle;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolSupport;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import slanglsp.SlangBundle;
import slanglsp.settings.NamingRule;
import slanglsp.settings.SlangCodeStyleSettings;
import java.util.*;

public final class NamingAnnotator extends ExternalAnnotator<NamingAnnotator.Input, NamingAnnotator.Result> {
    public record Input(UnusedSymbolsAnnotator.Input source, List<NamingRule> rules, String abbreviations) {}
    public record Candidate(String name, NamingRule.Category category, TextRange range) {}
    public record Result(Input input, List<Candidate> candidates, Map<Integer, String> unusedMessages) {}

    @Override
    public String getPairedBatchInspectionShortName() {
        return InconsistentNamingInspection.ID;
    }

    static boolean disabled(PsiFile file) {
        var key = HighlightDisplayKey.find(InconsistentNamingInspection.ID);
        return key == null || !InspectionProfileManager.getInstance(file.getProject())
                .getCurrentProfile().isToolEnabled(key, file);
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file, @NotNull com.intellij.openapi.editor.Editor editor,
                                    boolean hasErrors) {
        return hasErrors ? null : collectInformation(file);
    }

    @Override
    public Input collectInformation(@NotNull PsiFile file) {
        if (disabled(file) || file.getVirtualFile() == null
                || file.getVirtualFile() instanceof slanglsp.server.BuiltinFiles.BuiltinFile
                || com.intellij.openapi.project.DumbService.isDumb(file.getProject())) {
            return null;
        }
        Document document = LSPIJUtils.getDocument(file);
        if (document == null) {
            return null;
        }
        var settings = CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings.class);
        return new Input(new UnusedSymbolsAnnotator.Input(file, document, document.getModificationStamp(),
                com.intellij.psi.util.PsiModificationTracker.getInstance(file.getProject()).getModificationCount(),
                document.getText()), settings.getNamingRules(), settings.NAMING_ABBREVIATIONS);
    }

    @Override
    public Result doAnnotate(Input input) {
        var source = input.source();
        var support = new LSPDocumentSymbolSupport(source.file());
        try {
            var future = ReadActions.compute(() -> support.getDocumentSymbols(new DocumentSymbolParams(
                    new TextDocumentIdentifier(UsageSearch.fileUri(source.file().getVirtualFile()).toString()))));
            var symbols = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
            if (symbols == null) {
                return null;
            }
            var candidates = ReadActions.compute(() -> {
                if (source.document().getModificationStamp() != source.stamp()) {
                    return null;
                }
                Map<Integer, Candidate> collected = new LinkedHashMap<>();
                for (var symbol : symbols) {
                    collect(source, symbol.getDocumentSymbol(), null, collected);
                }
                return List.copyOf(collected.values());
            });
            if (candidates == null) {
                return null;
            }
            var unusedAnnotator = new UnusedSymbolsAnnotator();
            var unusedInput = ReadActions.compute(() -> unusedAnnotator.collectInformation(source.file()));
            Map<Integer, String> unusedMessages = new HashMap<>();
            if (unusedInput != null && unusedInput.stamp() == source.stamp()
                    && unusedInput.projectStamp() == source.projectStamp()) {
                var unused = unusedAnnotator.doAnnotate(unusedInput);
                if (unused != null) {
                    unused.unused().forEach(candidate ->
                            unusedMessages.put(candidate.range().getStartOffset(), candidate.message()));
                }
            }
            return new Result(input, candidates, Map.copyOf(unusedMessages));
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (RuntimeException failure) {
            com.intellij.openapi.diagnostic.Logger.getInstance(getClass()).debug("Naming analysis unavailable", failure);
            return null;
        } finally {
            support.cancel();
        }
    }

    static void collect(UnusedSymbolsAnnotator.Input input, DocumentSymbol symbol, SymbolKind parent,
                        Map<Integer, Candidate> result) {
        if (symbol == null || symbol.getSelectionRange() == null || symbol.getRange() == null) {
            return;
        }
        int start = LSPIJUtils.toOffset(symbol.getSelectionRange().getStart(), input.document());
        int end = start + symbol.getName().length();
        if (start < 0 || end > input.text().length()
                || !input.text().substring(start, end).equals(symbol.getName())) {
            return;
        }
        int boundary = start;
        while (boundary > 0 && ";{}".indexOf(input.text().charAt(boundary - 1)) < 0) {
            boundary--;
        }
        String prefix = input.text().substring(boundary, start);
        boolean external = UnusedDeclarations.isExternal(symbol.getName(), prefix);
        NamingRule.Category category = switch (symbol.getKind()) {
            case Struct, Class -> NamingRule.Category.TYPES;
            case Interface -> NamingRule.Category.INTERFACES;
            case Enum -> NamingRule.Category.ENUMS;
            case EnumMember -> NamingRule.Category.ENUM_MEMBERS;
            case Namespace -> NamingRule.Category.NAMESPACES;
            case Function, Method -> parent == SymbolKind.Struct || parent == SymbolKind.Class
                    ? NamingRule.Category.METHODS : NamingRule.Category.FUNCTIONS;
            case Field, Property -> NamingRule.Category.FIELDS;
            case Constant, Variable -> parent == SymbolKind.Function || parent == SymbolKind.Method
                    ? NamingRule.Category.LOCALS
                    : parent == SymbolKind.Struct || parent == SymbolKind.Class
                            ? NamingRule.Category.FIELDS : NamingRule.Category.GLOBALS;
            default -> null;
        };
        if (prefix.matches("(?s).*\\bstatic\\b.*") && prefix.matches("(?s).*\\bconst\\b.*")
                && (category == NamingRule.Category.GLOBALS || category == NamingRule.Category.FIELDS
                || category == NamingRule.Category.LOCALS)) {
            category = NamingRule.Category.CONSTANTS;
        }
        if (!external && category != null && parent != SymbolKind.Interface && !symbol.getName().startsWith("__")) {
            result.putIfAbsent(start, new Candidate(symbol.getName(), category, new TextRange(start, end)));
        }
        if (symbol.getKind() == SymbolKind.Function || symbol.getKind() == SymbolKind.Method) {
            int bodyEnd = Math.min(input.text().length(), LSPIJUtils.toOffset(symbol.getRange().getEnd(), input.document()));
            for (var local : UnusedDeclarations.locals(input.text(), end, bodyEnd)) {
                int localStart = local.range().getStartOffset();
                int localBoundary = localStart;
                while (localBoundary > end && ";{}".indexOf(input.text().charAt(localBoundary - 1)) < 0) {
                    localBoundary--;
                }
                String localPrefix = input.text().substring(localBoundary, localStart);
                var localCategory = localPrefix.matches("(?s).*\\bstatic\\b.*")
                        && localPrefix.matches("(?s).*\\bconst\\b.*")
                        ? NamingRule.Category.CONSTANTS : NamingRule.Category.LOCALS;
                result.put(local.range().getStartOffset(),
                        new Candidate(local.name(), localCategory, local.range()));
            }
            NamingParameters.collect(input.text(), end, bodyEnd, result);
        } else if (symbol.getKind() == SymbolKind.Struct || symbol.getKind() == SymbolKind.Class
                || symbol.getKind() == SymbolKind.Interface) {
            NamingParameters.collect(input.text(), end, Math.min(input.text().length(),
                    LSPIJUtils.toOffset(symbol.getRange().getEnd(), input.document())), result);
        }
        if (symbol.getChildren() != null) {
            SymbolKind childParent = symbol.getKind();
            if (childParent == SymbolKind.Struct || childParent == SymbolKind.Class) {
                int body = input.text().indexOf('{', end);
                if (body >= end && input.text().substring(end, body).contains(":")) {
                    // Members may fulfill interface requirements without an explicit override modifier.
                    childParent = SymbolKind.Interface;
                }
            }
            for (var child : symbol.getChildren()) {
                collect(input, child, childParent, result);
            }
        }
    }

    static NamingRule rule(Input input, Candidate candidate) {
        return input.rules().stream().filter(rule -> rule.category() == candidate.category()
                || rule.category() == NamingRule.Category.ALL).findFirst().orElse(null);
    }

    @Override
    public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
        if (result == null || disabled(file)
                || result.input().source().document().getModificationStamp() != result.input().source().stamp()
                || com.intellij.psi.util.PsiModificationTracker.getInstance(file.getProject()).getModificationCount()
                        != result.input().source().projectStamp()) {
            return;
        }
        var current = CodeStyle.getSettings(file).getCustomSettings(SlangCodeStyleSettings.class);
        if (!current.getNamingRules().equals(result.input().rules())
                || !current.NAMING_ABBREVIATIONS.equals(result.input().abbreviations())) {
            return;
        }
        var key = HighlightDisplayKey.find(InconsistentNamingInspection.ID);
        if (key == null) {
            return;
        }
        var severity = InspectionProfileManager.getInstance(file.getProject()).getCurrentProfile()
                .getErrorLevel(key, file).getSeverity();
        for (var candidate : result.candidates()) {
            var rule = rule(result.input(), candidate);
            if (rule == null) {
                continue;
            }
            String suggestion = rule.suggest(candidate.name(), result.input().abbreviations());
            if (suggestion.equals(candidate.name()) || !RenamePlan.validName(suggestion)) {
                continue;
            }
            var element = file.findElementAt(candidate.range().getStartOffset());
            if (element == null || !element.getText().equals(candidate.name())) {
                continue;
            }
            // Informational styling must not compete with the unused warning for hover selection.
            // Its fix remains available through Alt+Enter over the entire identifier.
            String unusedMessage = result.unusedMessages().get(candidate.range().getStartOffset());
            var annotation = unusedMessage != null
                    ? holder.newSilentAnnotation(com.intellij.lang.annotation.HighlightSeverity.INFORMATION)
                            .textAttributes(com.intellij.openapi.editor.colors.CodeInsightColors.WARNINGS_ATTRIBUTES)
                    : holder.newAnnotation(severity,
                            SlangBundle.message("naming.warning", candidate.name(), rule.name(), suggestion));
            annotation.range(TextRange.from(candidate.range().getStartOffset(), Math.min(2, candidate.name().length())))
                    .newFix(new NamingRenameFix(element, suggestion)).range(candidate.range()).registerFix().create();
        }
    }
}
