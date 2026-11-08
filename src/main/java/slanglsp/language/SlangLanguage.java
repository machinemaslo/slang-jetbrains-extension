package slanglsp.language;

import com.intellij.lang.Language;

public class SlangLanguage extends Language {
    public static final SlangLanguage INSTANCE = new SlangLanguage();
    SlangLanguage() {
        super("Slang");
    }
}
