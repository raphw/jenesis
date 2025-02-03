package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.module.DownloadModuleUris;
import build.buildbuddy.module.ModularProject;
import build.buildbuddy.project.JavaModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Modular {

    public static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"),
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.printing(System.out));
        root.addStep("download", new DownloadModuleUris());
        root.addModule("modules", ModularProject.make(Path.of("."),
                "SHA256",
                (_, _) -> (buildExecutor, inherited) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        Stream.concat(Stream.of("../dependencies/artifacts"), inherited.sequencedKeySet().stream()
                                .filter(identity -> identity.startsWith("../../../"))).collect(
                                Collectors.toCollection(LinkedHashSet::new)))), "download");
        root.execute();
    }
}
