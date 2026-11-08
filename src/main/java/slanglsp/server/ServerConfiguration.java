package slanglsp.server;

import org.eclipse.lsp4j.ConfigurationParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ServerConfiguration {
    private ServerConfiguration() { }

    static List<Object> select(Map<String, Object> settings, ConfigurationParams params) {
        List<Object> values = new ArrayList<>();
        for (var item : params.getItems()) {
            String section = item.getSection();
            values.add(section == null || section.isEmpty() ? settings : settings.get(section));
        }
        return values;
    }
}
