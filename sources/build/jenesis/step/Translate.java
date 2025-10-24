package build.jenesis.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.SequencedProperties;

import module java.base;

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
