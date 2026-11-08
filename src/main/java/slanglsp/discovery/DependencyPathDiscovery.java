package slanglsp.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Extracts installed dependency roots from Meson's compiler and linker arguments. */
final class DependencyPathDiscovery {
    private static final Set<String> SEPARATE_PATH_FLAGS = Set.of(
            "-I", "-isystem", "-L", "/I", "-imsvc", "/external:I"
    );

    private static final List<String> PATH_PREFIXES = List.of(
            "-isystem", "/external:I", "-imsvc", "-I", "-L", "/I", "/LIBPATH:"
    );

    private DependencyPathDiscovery() { }

    static void paths(DiscoveryContext context, Path base, List<String> arguments, Set<String> seen) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            String value = null;

            if (SEPARATE_PATH_FLAGS.contains(argument)) {
                if (index + 1 < arguments.size()) {
                    value = arguments.get(++index);
                }
            } else {
                value = attachedPath(argument);
            }

            if (value != null && seen.size() < 256 && seen.add(base + "\n" + value)) {
                context.dependencyPath(base, value);
            }
        }
    }

    private static String attachedPath(String argument) {
        for (String prefix : PATH_PREFIXES) {
            if (argument.startsWith(prefix) && argument.length() > prefix.length()) {
                return argument.substring(prefix.length());
            }
        }

        return null;
    }
}
