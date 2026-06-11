package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.PathPlacement;
import build.jenesis.step.Dependencies;

public interface TestEngine extends Serializable {

    String runnerModule();

    String mainClass();

    boolean isEngine(ModuleDescriptor module);

    boolean isRunner(ModuleDescriptor module);

    SequencedMap<String, String> coordinates(ModuleDescriptor engine);

    default Map<String, String> properties() {
        return Map.of();
    }

    List<String> commands(Path supplement,
                          Path output,
                          SequencedSet<String> classes,
                          SequencedMap<String, SequencedSet<String>> methods,
                          SequencedSet<String> groups,
                          boolean parallel,
                          boolean reporting);

    default Optional<ModuleDescriptor> match(List<ModuleDescriptor> modules) {
        for (ModuleDescriptor module : modules) {
            if (isEngine(module)) {
                return Optional.of(module);
            }
        }
        return Optional.empty();
    }

    default boolean hasRunner(List<ModuleDescriptor> modules) {
        for (ModuleDescriptor module : modules) {
            if (isRunner(module)) {
                return true;
            }
        }
        return false;
    }

    static Optional<TestEngine> of(List<ModuleDescriptor> modules) {
        for (TestEngine engine : List.<TestEngine>of(new JUnitPlatform(), new JUnit4(), new TestNG())) {
            if (engine.match(modules).isPresent()) {
                return Optional.of(engine);
            }
        }
        return Optional.empty();
    }

    static Optional<TestEngine> of(Iterable<Path> folders) throws IOException {
        return of(scan(folders));
    }

    static boolean hasRunner(TestEngine engine, Iterable<Path> folders) throws IOException {
        return engine.hasRunner(scan(folders));
    }

    static List<ModuleDescriptor> scan(Iterable<Path> folders) throws IOException {
        List<ModuleDescriptor> modules = new ArrayList<>();
        for (Path folder : folders) {
            List<Path> jars = new ArrayList<>();
            Path artifacts = folder.resolve(BuildStep.ARTIFACTS);
            if (Files.exists(artifacts)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                    for (Path file : stream) {
                        if (Files.isRegularFile(file)) {
                            jars.add(file);
                        }
                    }
                }
            }
            jars.addAll(Dependencies.all(folder));
            for (Path file : jars) {
                ModuleDescriptor module = PathPlacement.moduleDescriptor(file);
                if (module != null) {
                    modules.add(module);
                }
            }
        }
        modules.sort(Comparator.comparing(ModuleDescriptor::name));
        return modules;
    }
}
