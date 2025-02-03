package build.buildbuddy.step;

import build.buildbuddy.BuildStep;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public enum TestEngine {

    JUNIT4("junit", "org.junit.runner.JUnitCore", ""),
    JUNIT5("org.junit.platform.console",
            "org.junit.platform.console.ConsoleLauncher",
            "-select-class=",
            "execute",
            "--disable-banner",
            "--disable-ansi-colors");

    final String module, mainClass, prefix;
    final List<String> arguments;

    TestEngine(String module, String mainClass, String prefix, String... arguments) {
        this.module = module;
        this.mainClass = mainClass;
        this.prefix = prefix;
        this.arguments = List.of(arguments);
    }

    public static Optional<TestEngine> of(Iterable<Path> folders) throws IOException {
        TestEngine engine = null;
        for (Path folder : folders) {
            Path artifacts = folder.resolve(BuildStep.ARTIFACTS);
            if (Files.exists(artifacts)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                    for (Path file : stream) {
                        try (JarFile jarFile = new JarFile(file.toFile())) {
                            Manifest manifest = jarFile.getManifest();
                            if (manifest != null) {
                                TestEngine candidate = switch (manifest
                                        .getMainAttributes()
                                        .getValue(Attributes.Name.IMPLEMENTATION_TITLE)) {
                                    case "JUnit" -> TestEngine.JUNIT4;
                                    case "junit-platform-console" -> TestEngine.JUNIT5;
                                    case null, default -> null;
                                };
                                if (candidate != null) {
                                    if (engine == null || candidate.ordinal() > engine.ordinal()) {
                                        engine = candidate;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            throw e;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(engine);
    }
}
