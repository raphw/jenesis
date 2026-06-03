package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;

public interface TestEngine extends Serializable {

    String runnerModule();

    String mainClass();

    boolean isEngine(ModuleDescriptor module);

    boolean isRunner(ModuleDescriptor module);

    SequencedMap<String, String> coordinates(ModuleDescriptor engine);

    default Map<String, String> properties() {
        return Map.of();
    }

    List<String> arguments(Path supplement, String group, boolean parallel);

    List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods);

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
            for (String jarFolder : List.of(BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path jars = folder.resolve(jarFolder);
                if (!Files.exists(jars)) {
                    continue;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(jars)) {
                    for (Path file : stream) {
                        if (!Files.isRegularFile(file)) {
                            continue;
                        }
                        ModuleDescriptor module = inspect(file);
                        if (module != null) {
                            modules.add(module);
                        }
                    }
                }
            }
        }
        return modules;
    }

    private static ModuleDescriptor inspect(Path file) {
        try (JarFile jar = new JarFile(file.toFile())) {
            JarEntry moduleInfo = jar.getJarEntry("module-info.class");
            if (moduleInfo != null) {
                try (InputStream input = jar.getInputStream(moduleInfo)) {
                    return ModuleDescriptor.read(input);
                }
            }
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String automatic = manifest.getMainAttributes().getValue("Automatic-Module-Name");
                if (automatic != null) {
                    return ModuleDescriptor.newAutomaticModule(automatic).build();
                }
            }
            return null;
        } catch (Exception _) {
            return null;
        }
    }
}
