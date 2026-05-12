package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.maven.MavenPomEmitter;
import build.jenesis.maven.MavenRepositoryLayout;
import build.jenesis.step.Export;

public class Release {

    static void main(String... args) throws IOException {
        MavenPomEmitter.Metadata metadata = new MavenPomEmitter.Metadata(
                "Jenesis",
                "A build tool for Java projects, written and configured in Java itself.",
                "https://github.com/raphw/jenesis",
                List.of(new MavenPomEmitter.Metadata.License(
                        "Apache-2.0",
                        "https://www.apache.org/licenses/LICENSE-2.0.txt")),
                List.of(new MavenPomEmitter.Metadata.Developer(
                        "raphw",
                        "Rafael Winterhalter",
                        "rafael.wth@gmail.com")),
                new MavenPomEmitter.Metadata.Scm(
                        "scm:git:https://github.com/raphw/jenesis.git",
                        "scm:git:git@github.com:raphw/jenesis.git",
                        "https://github.com/raphw/jenesis"));

        Project.Builder builder = Project.builder()
                .sources(true)
                .javadoc(true)
                .pom(true)
                .metadata(metadata)
                .resolveProperties();
        builder = builder.layout(stagingLayout(builder.layout()));
        builder.build(args.length == 0 ? new String[]{"stage"} : args);
    }

    private static Project.Layout stagingLayout(Project.Layout inner) {
        return (executor, builder, assembler) -> {
            Function<String, String> resolver = inner.apply(executor, builder, assembler);
            executor.addStep("stage",
                    new Export(Path.of("out/staging-deploy"), mainArtifactPlacement()),
                    "collect");
            return resolver;
        };
    }

    @SuppressWarnings("unchecked")
    private static <F extends Function<Path, Optional<Path>> & Serializable> F mainArtifactPlacement() {
        MavenRepositoryLayout layout = new MavenRepositoryLayout();
        return (F) (Function<Path, Optional<Path>> & Serializable) (file -> layout.apply(file)
                .filter(result -> result.getNameCount() > 2
                        && "jenesis".equals(result.getName(2).toString())));
    }
}
