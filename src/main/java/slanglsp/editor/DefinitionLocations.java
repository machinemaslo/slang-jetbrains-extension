package slanglsp.editor;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.usages.LocationData;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import slanglsp.language.SlangFileType;
import slanglsp.server.BuiltinFiles;

import java.util.ArrayList;
import java.util.List;

/** Some slangd versions report import-dependent columns for the same declaration. */
final class DefinitionLocations {
    private record Target(PsiFile file, Range range, List<Range> declarations) {
    }

    static List<LocationData> normalize(PsiFile source, int offset, String name, List<LocationData> locations) {
        if (name == null || locations.isEmpty()) {
            return locations;
        }

        List<LocationData> result = new ArrayList<>();

        for (var data : locations) {
            Location location = data.location();
            var target = ReadActions.compute(() -> {
                var file = LSPIJUtils.findResourceFor(location.getUri());

                if (file == null || file instanceof BuiltinFiles.BuiltinFile
                        || file.getFileType() != SlangFileType.INSTANCE) {
                    return null;
                }

                var psi = PsiManager.getInstance(source.getProject()).findFile(file);
                Range range = psi == null ? null : identifierRange(psi, name, location.getRange());
                var declarations = psi == null
                        ? List.<Range>of()
                        : identifierRanges(psi, name, location.getRange()).stream()
                                .filter(candidate -> OverloadMatcher.isFunctionDeclaration(psi, candidate))
                                .toList();

                return new Target(psi, range, declarations);
            });

            if (target == null) {
                result.add(data);
                continue;
            }

            Range range = target.range;

            if (target.declarations.size() > 1) {
                // Even a wrong column can land exactly on another overload.
                range = OverloadMatcher.resolve(
                        source, offset, target.file, name, target.declarations, data.languageServer());
            } else if (range == null && target.declarations.size() == 1) {
                range = target.declarations.getFirst();
            }

            if (range == null) {
                // A partial/ambiguous result cannot establish that a candidate is unrelated.
                return List.of();
            }

            result.add(new LocationData(new Location(location.getUri(), range), data.languageServer()));
        }

        return result;
    }

    static Range identifierRange(PsiFile file, String name, Range reported) {
        var candidates = identifierRanges(file, name, reported);

        for (var candidate : candidates) {
            if (candidate.getStart().equals(reported.getStart())) {
                return candidate;
            }
        }

        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static List<Range> identifierRanges(PsiFile file, String name, Range reported) {
        var document = LSPIJUtils.getDocument(file);

        if (document == null || reported == null || reported.getStart() == null) {
            return List.of();
        }

        int line = reported.getStart().getLine();

        if (line < 0 || line >= document.getLineCount()) {
            return List.of();
        }

        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        CharSequence text = document.getImmutableCharSequence();
        List<Range> result = new ArrayList<>();

        for (int offset = start; offset < end;) {
            var leaf = file.findElementAt(offset);

            if (leaf == null) {
                return List.of();
            }

            int tokenStart = leaf.getTextRange().getStartOffset();
            int tokenEnd = leaf.getTextRange().getEndOffset();

            if (tokenStart >= start && tokenEnd <= end
                    && SlangFindUsagesProvider.isIdentifier(leaf)
                    && name.contentEquals(text.subSequence(tokenStart, tokenEnd))) {
                result.add(new Range(new Position(line, tokenStart - start), new Position(line, tokenEnd - start)));
            }

            offset = tokenEnd;
        }

        return result;
    }
}
