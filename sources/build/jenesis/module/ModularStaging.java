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
        this(Boolean.getBoolean("jenesis.stage.tests"));
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
                continue;
            }
            Path artifact = single(Inventory.paths(inventory, argument.folder(), prefix + ".artifacts"),
                    prefix, "artifacts", true, ".jar", inventoryFile);
            Path sources = single(Inventory.paths(inventory, argument.folder(), prefix + ".sources"),
                    prefix, "sources", false, ".jar", inventoryFile);
            Path javadoc = single(Inventory.paths(inventory, argument.folder(), prefix + ".documentation"),
                    prefix, "documentation", false, ".jar", inventoryFile);
            Path jmod = single(Inventory.paths(inventory, argument.folder(), prefix + ".jmod"),
                    prefix, "jmod", false, ".jmod", inventoryFile);
            String pomRelative = inventory.getProperty(prefix + ".pom");
            Path pom = pomRelative == null ? null : argument.folder().resolve(pomRelative).normalize();
            String version = inventory.getProperty(prefix + ".version");
            Path target = version == null
                    ? context.next().resolve(moduleName)
                    : context.next().resolve(moduleName).resolve(version);
            Files.createDirectories(target);
            link(artifact, target.resolve(moduleName + ".jar"));
            link(sources, target.resolve(moduleName + "-sources.jar"));
            link(javadoc, target.resolve(moduleName + "-javadoc.jar"));
            link(jmod, target.resolve(moduleName + ".jmod"));
            link(pom, target.resolve(moduleName + ".pom"));
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

    private static Path single(List<Path> entries,
                               String prefix,
                               String kind,
                               boolean required,
                               String extension,
                               Path inventoryFile) {
        if (entries.isEmpty()) {
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
        List<Path> matches = new ArrayList<>();
        for (Path candidate : entries) {
            if (candidate.getFileName().toString().endsWith(extension) && Files.isRegularFile(candidate)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            if (required) {
                throw new IllegalStateException("No '" + extension + "' file listed in '"
                        + prefix
                        + "."
                        + kind
                        + "' ("
                        + entries
                        + ") in inventory: "
                        + inventoryFile);
            }
            return null;
        }
        if (matches.size() > 1) {
            throw new IllegalStateException((required ? "Expected exactly one '" : "Expected at most one '")
                    + extension
                    + "' in '"
                    + prefix
                    + "."
                    + kind
                    + "', got "
                    + matches.size()
                    + " ("
                    + matches
                    + ") in inventory: "
                    + inventoryFile);
        }
        return matches.getFirst();
    }

    private static void link(Path source, Path target) throws IOException {
        if (source != null && !Files.exists(target)) {
            BuildStep.linkOrCopy(target, source);
        }
    }
}
