package slanglsp.editor;

import com.intellij.openapi.project.Project;
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
    static List<LocationData> normalize(Project project, String name, List<LocationData> locations) {
        if (name == null || locations.isEmpty()) {
            return locations;
        }
        return ReadActions.compute(() -> {
            List<LocationData> result = new ArrayList<>();
            for (var data : locations) {
                Location location = data.location();
                var file = LSPIJUtils.findResourceFor(location.getUri());
                if (file == null || file instanceof BuiltinFiles.BuiltinFile
                        || file.getFileType() != SlangFileType.INSTANCE) {
                    result.add(data);
                    continue;
                }
                var psi = PsiManager.getInstance(project).findFile(file);
                Range range = psi == null ? null : identifierRange(psi, name, location.getRange());
                if (range == null) {
                    // A partial/ambiguous result cannot establish that a candidate is unrelated.
                    return List.of();
                }
                result.add(new LocationData(new Location(location.getUri(), range), data.languageServer()));
            }
            return result;
        });
    }

    static Range identifierRange(PsiFile file, String name, Range reported) {
        var document = LSPIJUtils.getDocument(file);
        if (document == null || reported == null || reported.getStart() == null) {
            return null;
        }
        int line = reported.getStart().getLine();
        if (line < 0 || line >= document.getLineCount()) {
            return null;
        }
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        CharSequence text = document.getImmutableCharSequence();
        int found = -1;
        int reportedOffset = start + reported.getStart().getCharacter();
        for (int offset = start; offset < end;) {
            var leaf = file.findElementAt(offset);
            if (leaf == null) {
                return null;
            }
            int tokenStart = leaf.getTextRange().getStartOffset();
            int tokenEnd = leaf.getTextRange().getEndOffset();
            if (tokenStart >= start && tokenEnd <= end
                    && SlangFindUsagesProvider.isIdentifier(leaf)
                    && name.contentEquals(text.subSequence(tokenStart, tokenEnd))) {
                if (tokenStart == reportedOffset) {
                    return new Range(new Position(line, tokenStart - start), new Position(line, tokenEnd - start));
                }
                found = found == -1 ? tokenStart : -2;
            }
            offset = tokenEnd;
        }
        return found < 0 ? null
                : new Range(new Position(line, found - start), new Position(line, found - start + name.length()));
    }
}
