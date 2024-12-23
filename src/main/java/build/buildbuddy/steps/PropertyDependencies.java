package build.buildbuddy.steps;

import build.buildbuddy.*;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class PropertyDependencies implements BuildStep {

    public static final String DEPENDENCIES = "dependencies/";

    private final MavenPomResolver resolver;

    public PropertyDependencies(MavenPomResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        List<Object> resolved = new ArrayList<>();
        for (BuildStepArgument result : arguments.values()) {
            Path dependencies = result.folder().resolve(DEPENDENCIES);
            if (!Files.exists(dependencies)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dependencies, "*.properties")) {
                for (Path path : stream) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(result.folder().resolve(path))) {
                        properties.load(reader);
                    }
                    for (String dependency : properties.stringPropertyNames()) {
                        int index = dependency.indexOf('|');
                        String groupId = dependency.substring(0, index),
                                artifactId = dependency.substring(index + 1),
                                version = properties.getProperty(dependency);
                    }
                }
            }
        }
        // TODO: resolve virtual pom
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
