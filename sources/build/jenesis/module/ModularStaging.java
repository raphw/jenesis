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
            SequencedProperties inv = SequencedProperties.ofFiles(inventoryFile);
            String prefix = inventoryPrefix(inv, inventoryFile);
            String testsOf = inv.getProperty(prefix + ".tests");
            if (!includeTests && testsOf != null) {
                continue;
            }
            String moduleName = inv.getProperty(prefix + ".module");
            if (moduleName == null) {
                throw new IllegalStateException("Missing 'module' in inventory: " + inventoryFile);
            }
            Path artifact = resolve(argument.folder(), inv.getProperty(prefix + ".artifact"));
            Path sources = resolve(argument.folder(), inv.getProperty(prefix + ".artifact.sources"));
            Path javadoc = resolve(argument.folder(), inv.getProperty(prefix + ".artifact.javadoc"));
            String version = inv.getProperty(prefix + ".version");
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

    private static Path resolve(Path base, String relative) {
        if (relative == null) {
            return null;
        }
        Path resolved = base.resolve(relative).normalize();
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    private static void link(Path source, Path target) throws IOException {
        if (source != null && !Files.exists(target)) {
            Files.createLink(target, source);
        }
    }
}
