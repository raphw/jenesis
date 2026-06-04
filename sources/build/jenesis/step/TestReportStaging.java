package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class TestReportStaging implements BuildStep {

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
            List<Path> reports = Inventory.paths(inventory, argument.folder(), prefix + ".testreport");
            if (reports.isEmpty()) {
                continue;
            }
            Path target = context.next().resolve(prefix);
            Files.createDirectories(target);
            for (Path report : reports) {
                if (!Files.isRegularFile(report)) {
                    continue;
                }
                Path destination = target.resolve(report.getFileName().toString());
                if (!Files.exists(destination)) {
                    BuildStep.linkOrCopy(destination, report);
                }
            }
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
}
