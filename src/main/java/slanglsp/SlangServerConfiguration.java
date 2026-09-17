package slanglsp;

import org.eclipse.lsp4j.ConfigurationParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SlangServerConfiguration {
    private SlangServerConfiguration() { }

    static List<Object> select(Map<String, Object> settings, ConfigurationParams params) {
        var values = new ArrayList<Object>();
        for (var item : params.getItems()) {
            String section = item.getSection();
            values.add(section == null || section.isEmpty() ? settings : settings.get(section));
        }
        return values;
    }
}
