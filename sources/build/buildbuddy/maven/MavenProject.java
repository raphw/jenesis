package build.buildbuddy.maven;

import build.buildbuddy.*;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class MavenProject implements BuildExecutorDelegate {

    public static final String POMS = "poms/";

    private final Path root;
    private final MavenPomResolver resolver;

    public MavenProject(Path root, MavenPomResolver resolver) {
        this.root = root;
        this.resolver = resolver;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        if (!Files.exists(root.resolve("pom.xml"))) {
            return;
        }
        buildExecutor.addStep("prepare", new BuildStep() {
            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments)
                    throws IOException {
                Path poms = Files.createDirectory(context.next().resolve(POMS));
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path pom = dir.resolve("pom.xml");
                        if (Files.exists(pom)) {
                            Files.createLink(
                                    Files.createDirectories(poms.resolve(root.relativize(dir))).resolve("pom.xml"),
                                    pom);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }

            @Override
            public boolean isAlwaysRun() {
                return true;
            }
        });
        buildExecutor.add("process", (modules, folders) -> {
            for (Map.Entry<Path, MavenLocalPom> entry : resolver
                    .local(folders.get("prepare").resolve(POMS))
                    .entrySet()) {
                modules.add(entry.getValue().toString(), (module, _) -> {

                });
                // TODO: take paths and bind sources/resources and test sources/resources and coordinates and
                //  dependencies to different steps in 'modules'
            }
        }, "prepare");
    }
}
