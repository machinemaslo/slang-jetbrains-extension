package slanglsp.editor;

import com.intellij.spellchecker.BundledDictionaryProvider;
import org.jetbrains.annotations.NotNull;

public final class SlangDictionaryProvider implements BundledDictionaryProvider {
    @Override
    public @NotNull String[] getBundledDictionaries() {
        return new String[] {"/dictionaries/slang.dic"};
    }
}
