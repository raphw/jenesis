package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface DependencyTransformingBuildStep extends BuildStep {

    @Override
    default CompletionStage<BuildStepResult> apply(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolve
        SequencedMap<String, SequencedMap<String, String>> groups = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path dependencies = argument.folder().resolve(DEPENDENCIES);
            if (!Files.exists(dependencies)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(dependencies)) {
                properties.load(reader);
            }
            for (String property : properties.stringPropertyNames()) {
                int index = property.indexOf('/');
                groups.computeIfAbsent(property.substring(0, index), _ -> new LinkedHashMap<>()).merge(
                        property.substring(index + 1),
                        properties.getProperty(property),
                        (left, right) -> left.isEmpty() ? right : left);
            }
        }
        return transform(executor, context, arguments, groups).thenComposeAsync(properties -> {
            CompletableFuture<BuildStepResult> result = new CompletableFuture<>();
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
                properties.store(writer, null);
                result.complete(new BuildStepResult(true));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
            return result;
        }, executor);
    }

    CompletionStage<Properties> transform(Executor executor,
                                          BuildStepContext context,
                                          SequencedMap<String, BuildStepArgument> arguments,
                                          SequencedMap<String, SequencedMap<String, String>> groups) throws IOException;
}

