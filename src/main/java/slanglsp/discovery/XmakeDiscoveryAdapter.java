package slanglsp.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Reads Xmake's serialized config without loading or executing Lua. */
final class XmakeDiscoveryAdapter implements DiscoveryAdapter {
    @Override
    public void discover(DiscoveryContext c) {
        if (c.read(c.root.resolve("xmake.lua")).isBlank()) {
            return;
        }

        readProjectConfig(c);
        readGlobalConfig(c);
    }

    private void readProjectConfig(DiscoveryContext c) {
        Path configRoot = c.resolve(c.root, c.environment.get("XMAKE_CONFIGDIR"));

        if (configRoot == null) {
            configRoot = c.root;
        }

        // Only Xmake's documented host/architecture config locations, not project directories.
        int count = 0;
        for (Path host : c.children(configRoot.resolve(".xmake"), "*")) {
            for (Path arch : c.children(host, "*")) {
                if (++count > 16) {
                    break;
                }

                read(c, arch.resolve("xmake.conf"));
            }
            if (count > 16) {
                break;
            }
        }
    }

    private void readGlobalConfig(DiscoveryContext c) {
        String directory = c.environment.getOrDefault("XMAKE_GLOBALDIR", System.getProperty("user.home"));
        Path global = c.resolve(c.root, directory);

        if (global != null) {
            read(c, global.resolve(".xmake/xmake.conf"));
        }
    }

    private void read(DiscoveryContext c, Path file) {
        String text = c.read(file);
        for (String key : List.of("vcpkg", "buildir", "builddir")) {
            var match = Pattern.compile("(?m)^\\s*(?:" + key + "|\\[\"" + key + "\"])\\s*=\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,?\\s*$").matcher(text);
            if (match.find()) {
                String value = match.group(1).replace("\\\\", "\\");

                if (key.equals("vcpkg")) {
                    c.vcpkgRoot(c.root, value);
                } else {
                    c.buildDirectory(c.root, value);
                }
            }
        }
    }
}
