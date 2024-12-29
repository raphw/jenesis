package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Define implements BuildStep {

    public static final String DEFINITION = "definition/";

    private final Map<String, Identifier> identifiers;

    public Define(Map<String, Identifier> identifiers) {
        this.identifiers = identifiers;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Properties definition = new SequencedProperties(), dependencies = new SequencedProperties();
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<String, Identifier> entry : identifiers.entrySet()) {
                entry.getValue().identify(argument.folder()).ifPresent(identification -> {
                    definition.setProperty(entry.getKey() + "/" + identification.coordinate(), "");
                    identification.dependencies().forEach((dependency, expectation) ->
                            dependencies.setProperty(entry.getKey() + "/" + dependency, expectation));
                });
            }
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(context.next().resolve(DEFINITION))
                .resolve("definition.properties"))) {
            definition.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(context.next().resolve(FlattenDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            dependencies.store(writer, null);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
