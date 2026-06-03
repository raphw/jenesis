package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.project.JavaMultiProjectAssembler;

/**
 * Builds this project's {@code stage} goal with jpackage packaging enabled, then
 * launches the produced application image, forwarding this program's own
 * arguments to the packaged application's {@code main} method.
 *
 * Run it from this directory, passing whatever arguments you want the packaged
 * app to receive:
 *
 *     java build/Demo.java Ada Lovelace
 *
 * which builds the image and then prints (from the launched app):
 *
 *     Hello, Ada Lovelace, from a packaged Java module built by Jenesis!
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // Configure packaging explicitly on the assembler instead of through the
        // -Djenesis.java.package system property: hand the Project a stock
        // JavaMultiProjectAssembler with its packaging type set, via the wither, to a
        // self-contained app-image.
        Project project = new Project()
                .assembler(new JavaMultiProjectAssembler().packaging("app-image"));

        // `stage/packages` is a fixed build target: building `stage` returns a map keyed
        // by the steps that ran, so the image folder is read straight from that map under
        // the fixed `stage/packages` key rather than reconstructed by hand.
        SequencedMap<String, Path> outputs = project.build("stage");
        Path output = outputs.get("stage/packages");

        // The image folder is fixed too: jpackage names it after --name, which the build
        // derives from this project's coordinate (the module name). So the launcher path
        // is fully determined - no directory scanning needed.
        // jpackage names the image after --name (here the module name) and lays the
        // launcher out per platform - Windows <name>/<name>.exe, macOS a <name>.app
        // bundle, every other OS <name>/bin/<name> - so the path is fixed once the OS
        // is known; no scanning is needed.
        String name = "demo.modular.executable";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path launcher;
        if (os.contains("win")) {
            launcher = output.resolve(name).resolve(name + ".exe");
        } else if (os.contains("mac")) {
            launcher = output.resolve(name + ".app").resolve("Contents").resolve("MacOS").resolve(name);
        } else {
            launcher = output.resolve(name).resolve("bin").resolve(name);
        }

        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
