package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.ExternalModule;

/**
 * Showcase for {@code ExternalModule}: resolve a build module from a repository
 * coordinate, download it, and run it - the plugin is consumed as a published
 * artifact rather than compiled from local source.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        Path jenesisSources = Path.of("build", "jenesis").toRealPath().getParent().getParent();

        // Pre-build the two module jars the demo serves "from a repository":
        //  - build.jenesis (the plugin's dependency)
        //  - demo.external.plugin (the published build module itself)
        Path jenesisJar = buildModuleJar("build.jenesis", List.of(jenesisSources), List.of());
        Path pluginJar = buildModuleJar("demo.external.plugin", List.of(Path.of("plugin-src")), List.of(jenesisJar));

        // A module repository resolving each module name to its jar. This stands
        // in for a real Jenesis/Maven module repository.
        Map<String, Path> published = Map.of(
                "demo.external.plugin", pluginJar,
                "build.jenesis", jenesisJar);
        Repository repository = (executor, coordinate) -> {
            int slash = coordinate.indexOf('/');
            String module = slash < 0 ? coordinate : coordinate.substring(0, slash);
            Path jar = published.get(module);
            return jar == null ? Optional.empty() : Optional.of(RepositoryItem.ofFile(jar));
        };

        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addModule("plugin", new ExternalModule(
                "module/demo.external.plugin",              // the coordinate to resolve
                "tool",                                     // qualifier: an independent trail
                Map.of("module", repository),
                Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = root.execute();
        Path stamp = steps.get("plugin/stamp").resolve("stamp.txt");
        System.out.println();
        System.out.println("ExternalModule resolved and ran the plugin. It produced:");
        System.out.println("  " + Files.readString(stamp));
    }

    private static Path buildModuleJar(String moduleName, List<Path> sourceRoots, List<Path> modulePath) throws Exception {
        Path classes = Files.createTempDirectory(moduleName + "-classes");
        List<String> javac = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        if (!modulePath.isEmpty()) {
            javac.add("--module-path");
            javac.add(modulePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> javac.add(path.toString()));
            }
        }
        int compiled = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, javac.toArray(String[]::new));
        if (compiled != 0) {
            throw new IllegalStateException("Failed to compile " + moduleName + ": " + compiled);
        }
        Path jar = Files.createTempFile(moduleName, ".jar");
        int archived = ToolProvider.findFirst("jar").orElseThrow()
                .run(System.out, System.err, "--create", "--file", jar.toString(), "-C", classes.toString(), ".");
        if (archived != 0) {
            throw new IllegalStateException("Failed to archive " + moduleName + ": " + archived);
        }
        return jar;
    }
}
