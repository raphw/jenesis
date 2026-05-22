package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.SequencedProperties;

public class Translate implements DependencyProcessingBuildStep {

    private final Map<String, Function<String, String>> translators;

    public <F extends Function<String, String> & Serializable> Translate(Map<String, F> translators) {
        this.translators = new LinkedHashMap<>(translators);
    }

    @Override
    public CompletionStage<SequencedProperties> transform(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments,
                                                          SequencedMap<String, SequencedMap<String, String>> groups,
                                                          SequencedMap<String, SequencedMap<String, String>> versions,
                                                          SequencedMap<String, SequencedMap<String, SequencedSet<String>>> exclusions)
            throws IOException {
        return CompletableFuture.completedStage(doTransform(groups));
    }

    @Override
    public CompletionStage<SequencedProperties> transformVersions(Executor executor,
                                                                  BuildStepContext context,
                                                                  SequencedMap<String, BuildStepArgument> arguments,
                                                                  SequencedMap<String, SequencedMap<String, String>> versions){
        return CompletableFuture.completedStage(doTransform(versions));
    }

    private SequencedProperties doTransform(SequencedMap<String, SequencedMap<String, String>> groups) {
        SequencedProperties properties = new SequencedProperties();
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
        return properties;
    }
}
