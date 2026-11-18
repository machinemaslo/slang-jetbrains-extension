package slanglsp.editor;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;
import slanglsp.language.SlangFileType;

import java.util.HashMap;

/** Exact module/owner/name lookup; typing never parses other project documents. */
public final class GenericDeclarationsIndex extends ScalarIndexExtension<String> {
    static final ID<String, Void> NAME = ID.create("slang.generic.declarations");

    static String symbol(String owner, String name) {
        return (owner == null ? "" : owner) + ":" + name;
    }

    static String key(String module, String symbol) {
        return module + ":" + symbol;
    }

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return input -> {
            var context = new GenericContext(input.getContentAsText());
            var declarations = context.genericDeclarations();
            var result = new HashMap<String, Void>();
            String filename = input.getFile().getNameWithoutExtension();
            for (String symbol : declarations) {
                result.put(key(filename, symbol), null);
                if (context.moduleName() != null) {
                    result.put(key(context.moduleName(), symbol), null);
                }
            }
            return result;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(SlangFileType.INSTANCE);
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }
}
