package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

public class ModularStaging implements BuildStep {

    private final boolean includeTests;

    public ModularStaging() {
        this(false);
    }

    public ModularStaging(boolean includeTests) {
        this.includeTests = includeTests;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            Path inventoryFile = argument.folder().resolve(Inventory.INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            String prefix = inventoryPrefix(inventory, inventoryFile);
            String testsOf = inventory.getProperty(prefix + ".test");
            if (!includeTests && testsOf != null) {
                continue;
            }
            String moduleName = inventory.getProperty(prefix + ".module");
            if (moduleName == null) {
                throw new IllegalStateException("Missing 'module' in inventory: " + inventoryFile);
            }
            Path artifact = singleJar(argument.folder(), inventory.getProperty(prefix + ".artifacts"), prefix, "artifacts", true, inventoryFile);
            Path sources = singleJar(argument.folder(), inventory.getProperty(prefix + ".sources"), prefix, "sources", false, inventoryFile);
            Path javadoc = singleJar(argument.folder(), inventory.getProperty(prefix + ".documentation"), prefix, "documentation", false, inventoryFile);
            String version = inventory.getProperty(prefix + ".version");
            Path target = version == null
                    ? context.next().resolve(moduleName)
                    : context.next().resolve(moduleName).resolve(version);
            Files.createDirectories(target);
            link(artifact, target.resolve(moduleName + ".jar"));
            link(sources, target.resolve(moduleName + "-sources.jar"));
            link(javadoc, target.resolve(moduleName + "-javadoc.jar"));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String inventoryPrefix(SequencedProperties inventory, Path file) {
        for (String key : inventory.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                return key.substring(0, dot);
            }
        }
        throw new IllegalStateException("Inventory contains no prefixed keys: " + file);
    }

    private static Path singleJar(Path base,
                                  String value,
                                  String prefix,
                                  String kind,
                                  boolean required,
                                  Path inventoryFile) {
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new IllegalStateException("Missing '"
                        + prefix
                        + "."
                        + kind
                        + "' in inventory: "
                        + inventoryFile);
            }
            return null;
        }
        List<Path> jars = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty() || !trimmed.endsWith(".jar")) {
                continue;
            }
            Path candidate = base.resolve(trimmed).normalize();
            if (Files.isRegularFile(candidate)) {
                jars.add(candidate);
            }
        }
        if (jars.isEmpty()) {
            if (required) {
                throw new IllegalStateException("No '.jar' file listed in '"
                        + prefix
                        + "."
                        + kind
                        + "' (value: "
                        + value
                        + ") in inventory: "
                        + inventoryFile);
            }
            return null;
        }
        if (jars.size() > 1) {
            throw new IllegalStateException((required ? "Expected exactly one '.jar' in '" : "Expected at most one '.jar' in '")
                    + prefix
                    + "."
                    + kind
                    + "', got "
                    + jars.size()
                    + " ("
                    + jars
                    + ") in inventory: "
                    + inventoryFile);
        }
        return jars.getFirst();
    }

    private static void link(Path source, Path target) throws IOException {
        if (source != null && !Files.exists(target)) {
            Files.createLink(target, source);
        }
    }
}
