package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Define implements BuildStep {

    private final Map<String, Identifier> identifiers;

    public Define(Map<String, Identifier> identifiers) {
        this.identifiers = identifiers;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolution if irrelevant files have changed.
        Properties coordinates = new SequencedProperties(), dependencies = new SequencedProperties();
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<String, Identifier> entry : identifiers.entrySet()) {
                entry.getValue().identify(argument.folder()).ifPresent(identification -> {
                    coordinates.setProperty(entry.getKey() + "/" + identification.coordinate(), "");
                    identification.dependencies().forEach((dependency, expectation) -> dependencies.setProperty(
                            entry.getKey() + "/" + dependency,
                            expectation));
                });
            }
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(COORDINATES))) {
            coordinates.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
