package slanglsp.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Meson's saved introspection replies are usable without invoking build scripts. */
final class MesonDiscoveryAdapter implements DiscoveryAdapter {
    @Override
    public void discover(DiscoveryContext context) {
        if (context.read(context.root.resolve("meson.build")).isBlank()) {
            return;
        }

        context.buildDirectory(context.root, "builddir");

        for (Path directory : List.copyOf(context.buildDirectories)) {
            JsonElement data = context.json(directory.resolve("meson-info/intro-dependencies.json"));

            for (JsonElement element : CmakeDiscoveryAdapter.array(data)) {
                if (element instanceof JsonObject dependency) {
                    readDependency(context, directory, dependency);
                }
            }
        }
    }

    private void readDependency(DiscoveryContext context, Path directory, JsonObject dependency) {
        for (JsonElement path : CmakeDiscoveryAdapter.array(dependency.get("include_directories"))) {
            context.dependencyPath(directory, CmakeDiscoveryAdapter.string(path));
        }

        List<String> arguments = new ArrayList<>();

        for (String key : List.of("compile_args", "link_args")) {
            for (JsonElement argument : CmakeDiscoveryAdapter.array(dependency.get(key))) {
                String value = CmakeDiscoveryAdapter.string(argument);

                if (value != null) {
                    arguments.add(value);
                }
            }
        }

        DependencyPathDiscovery.paths(context, directory, arguments, new HashSet<>());
    }
}
