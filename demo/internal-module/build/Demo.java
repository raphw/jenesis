package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.InternalModule;

/**
 * Showcase for {@code InternalModule}: load and run a build module that lives
 * in this project's own sources (the {@code plugin/} directory).
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // The plugin's module-info requires build.jenesis, so the InternalModule
        // needs a build.jenesis module jar to compile and run it against. Build
        // one from the sources behind the build/jenesis symlink.
        Path jenesisSources = Path.of("build", "jenesis").toRealPath().getParent().getParent();
        Path jenesisJar = buildJenesisJar(jenesisSources);

        // A tiny module repository that resolves the build.jenesis module name to
        // the jar we just built. (A real project would use the published module.)
        Repository jenesis = (executor, coordinate) -> {
            int slash = coordinate.indexOf('/');
            String module = slash < 0 ? coordinate : coordinate.substring(0, slash);
            return module.equals("build.jenesis")
                    ? Optional.of(RepositoryItem.ofFile(jenesisJar))
                    : Optional.empty();
        };

        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addModule("plugin", new InternalModule(
                "module",                                   // resolution prefix
                "tool",                                     // qualifier: an independent trail
                Path.of("plugin"),                          // the plugin's source directory
                Map.of("module", jenesis),
                Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = root.execute();
        Path greeting = steps.get("plugin/greeting").resolve("greeting.txt");
        System.out.println();
        System.out.println("InternalModule ran the plugin. It produced:");
        System.out.println("  " + Files.readString(greeting));
    }

    private static Path buildJenesisJar(Path sourceRoot) throws Exception {
        Path classes = Files.createTempDirectory("jenesis-classes");
        List<String> javac = new ArrayList<>(List.of("--release", "25", "-d", classes.toString()));
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> javac.add(path.toString()));
        }
        int compiled = ToolProvider.findFirst("javac").orElseThrow()
                .run(System.out, System.err, javac.toArray(String[]::new));
        if (compiled != 0) {
            throw new IllegalStateException("Failed to compile build.jenesis sources: " + compiled);
        }
        Path jar = Files.createTempFile("build.jenesis", ".jar");
        int archived = ToolProvider.findFirst("jar").orElseThrow()
                .run(System.out, System.err, "--create", "--file", jar.toString(), "-C", classes.toString(), ".");
        if (archived != 0) {
            throw new IllegalStateException("Failed to archive build.jenesis jar: " + archived);
        }
        return jar;
    }
}
