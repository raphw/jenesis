package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.*;
import build.buildbuddy.project.DependenciesModule;
import build.buildbuddy.project.JavaModule;
import build.buildbuddy.project.MultiProjectDependencies;
import build.buildbuddy.project.MultiProjectModule;
import build.buildbuddy.project.RepositoryMultiProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Maven {

    public static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"),
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.printing(System.out));

        root.addModule("maven", MavenProject.make(Path.of("."), "SHA256", _ -> new JavaModule()));

        root.execute();
    }
}
