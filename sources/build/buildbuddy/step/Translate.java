package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.SequencedProperties;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

public class Translate implements DependencyTransformingBuildStep {

    private final Map<String, Function<String, String>> translators;

    public Translate(Map<String, Function<String, String>> translators) {
        this.translators = translators;
    }

    @Override
    public CompletionStage<Properties> transform(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> groups)
            throws IOException {
        Properties properties = new SequencedProperties();
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            Function<String, String> translator = translators.get(group.getKey());
            if (translator == null) {
                group.getValue().forEach((coordinate, expectation) -> properties.setProperty(
                        group.getKey() + "/" + coordinate,
                        expectation));
            } else {
                group.getValue().forEach((coordinate, expectation) -> properties.setProperty(
                        translator.apply(coordinate),
                        expectation));
            }
        }
        return CompletableFuture.completedStage(properties);
    }
}
