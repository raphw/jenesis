package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.project.InferredMultiProjectAssembler;

/**
 * Builds this project with the launcher target enabled, then runs the produced
 * executable jar with {@code java -jar}, forwarding this program's own arguments.
 *
 * The launcher target (selected by a {@code launcher=true} packaging.properties)
 * resolves {@code build.jenesis:build.jenesis.launcher} from Maven Central and shades
 * it into an executable jar: the launcher's classes sit in the jar root as its
 * {@code Main-Class}, every dependency is exploded into its own {@code classpath/<name>/}
 * or {@code modulepath/<name>/} subfolder, and {@code application.properties} names the
 * entry point - so {@code java -jar foo.jar} reconstructs the module graph and runs the
 * application while keeping full modularity.
 *
 *     java build/DemoLauncher.java Ada Lovelace
 */
public class DemoLauncher {

    static void main(String[] args) throws Exception {
        // Select the launcher target through a packaging.properties (launcher=true) written
        // to a configuration location of its own, the same key a project would commit; the
        // stock assembler then wires the launcher module with no extra configuration.
        Path configuration = Files.createTempDirectory("packaging-");
        Files.writeString(configuration.resolve("packaging.properties"), "launcher=true\n");
        Project project = new Project()
                .configuration(configuration)
                .assembler(new InferredMultiProjectAssembler());
        project.build("build");

        // The launcher step writes the executable jar under
        // <module>/launcher/bundle/output/launcher/<name>.jar; locate it in the build tree.
        Path jar;
        try (Stream<Path> walk = Files.walk(Path.of("target"))) {
            jar = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jar")
                            && path.getParent().getFileName().toString().equals("launcher")
                            && path.getParent().getParent().getFileName().toString().equals("output"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No launcher jar was produced"));
        }

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(List.of(args));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
