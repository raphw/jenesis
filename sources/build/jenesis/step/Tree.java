package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.DependencyTreeReport;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

public class Tree implements BuildStep {

    private final transient PrintStream out;

    public Tree() {
        this(System.out);
    }

    public Tree(PrintStream out) {
        this.out = out;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return true;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        DependencyTreeReport report = new DependencyTreeReport(out);
        for (BuildStepArgument argument : arguments.values()) {
            Path inventoryFile = argument.folder().resolve(Inventory.INVENTORY);
            if (!Files.isRegularFile(inventoryFile)) {
                continue;
            }
            SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
            String prefix = inventoryPrefix(inventory);
            if (prefix == null) {
                continue;
            }
            List<Path> graphs = Inventory.paths(inventory, argument.folder(), prefix + ".graph");
            List<Path> licenses = Inventory.paths(inventory, argument.folder(), prefix + ".licenses");
            SequencedMap<String, Resolver.Resolution> resolutions = Dependencies.graph(graphs, licenses);
            resolutions.forEach((groupScope, resolution) ->
                    report.render(resolution, groupScope + " (" + prefix + ")"));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String inventoryPrefix(SequencedProperties inventory) {
        for (String key : inventory.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                return key.substring(0, dot);
            }
        }
        return null;
    }
}
