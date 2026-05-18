package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

@FunctionalInterface
public interface DependencyProcessingBuildStep extends BuildStep {

    @Override
    default CompletionStage<BuildStepResult> apply(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolve
        SequencedMap<String, SequencedMap<String, String>> groups = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, String>> versions = new LinkedHashMap<>();
        Map<String, SequencedMap<String, SequencedMap<String, String>>> sources = new LinkedHashMap<>();
        sources.put(REQUIRES, groups);
        sources.put(VERSIONS, versions);
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<String, SequencedMap<String, SequencedMap<String, String>>> source : sources.entrySet()) {
                Path file = argument.folder().resolve(source.getKey());
                if (!Files.exists(file)) {
                    continue;
                }
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
                for (String property : properties.stringPropertyNames()) {
                    int index = property.indexOf('/');
                    source.getValue().computeIfAbsent(property.substring(0, index), _ -> new LinkedHashMap<>()).merge(
                            property.substring(index + 1),
                            properties.getProperty(property),
                            (left, right) -> left.isEmpty() ? right : left);
                }
            }
        }
        CompletionStage<Properties> requiresStage = transform(executor, context, arguments, groups, versions);
        CompletionStage<Properties> versionsStage = transformVersions(executor, context, arguments, versions);
        return requiresStage.thenCombineAsync(versionsStage, (requiresProperties, versionsProperties) -> {
            if (requiresProperties != null) {
                try (Writer writer = Files.newBufferedWriter(context.next().resolve(REQUIRES))) {
                    requiresProperties.store(writer, null);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }
            if (versionsProperties != null) {
                try (Writer writer = Files.newBufferedWriter(context.next().resolve(VERSIONS))) {
                    versionsProperties.store(writer, null);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }
            return new BuildStepResult(true);
        }, executor);
    }

    CompletionStage<Properties> transform(Executor executor,
                                          BuildStepContext context,
                                          SequencedMap<String, BuildStepArgument> arguments,
                                          SequencedMap<String, SequencedMap<String, String>> groups,
                                          SequencedMap<String, SequencedMap<String, String>> versions) throws IOException;

    default CompletionStage<Properties> transformVersions(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments,
                                                          SequencedMap<String, SequencedMap<String, String>> versions)
            throws IOException {
        return CompletableFuture.completedStage(null);
    }
}
