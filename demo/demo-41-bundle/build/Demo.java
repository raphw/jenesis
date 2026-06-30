package build;

import module java.base;
import build.jenesis.Project;
import build.jenesis.project.InferredMultiProjectAssembler;

/**
 * Builds this modular app with the {@code bundle} target enabled, unpacks the
 * produced {@code bundle.zip}, and launches the app out of it on this JDK's own
 * {@code java} - exactly the way a consumer would run the bundle on a stock JRE
 * base image. Run it from this directory, passing whatever arguments you want the
 * app to receive:
 *
 *     java build/Demo.java Ada Lovelace
 *
 * which builds the bundle, unpacks it, and prints (from the launched app):
 *
 *     Hello, Ada Lovelace, from a Jenesis bundle.zip on a stock JRE!
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // Enable the bundle target on the assembler (the in-code equivalent of
        // -Djenesis.java.bundle=true) and run the default build, which writes a
        // bundle/bundle.zip for every module that declares a main class.
        Project project = new Project()
                .assembler(new InferredMultiProjectAssembler().bundle(true));
        project.build("build");

        // The bundle step writes the archive under .../package/bundle/output/bundle/bundle.zip.
        // It is not collected into stage/, so locate it in the build tree the same way the
        // launcher demo locates its jar.
        Path zip;
        try (Stream<Path> walk = Files.walk(Path.of("target"))) {
            zip = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("bundle.zip")
                            && path.getParent().getFileName().toString().equals("bundle")
                            && path.getParent().getParent().getFileName().toString().equals("output"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No bundle.zip was produced"));
        }

        // Unpack the bundle the way a deployment would: an application.properties plus a
        // modulepath/ (and, for a non-modular app, a classpath/) holding the launch closure.
        Path unpacked = Files.createTempDirectory("bundle-");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = unpacked.resolve(entry.getName()).normalize();
                if (!target.startsWith(unpacked)) {
                    throw new IOException("Bundle entry escapes the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = archive.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        // application.properties describes the launch: mainClass, mainModule (modular
        // launchers only), and selfContainedModuleGraph - whether the module path roots
        // itself from the main module's requires, or needs --add-modules ALL-MODULE-PATH.
        Properties application = new Properties();
        try (InputStream in = Files.newInputStream(unpacked.resolve("application.properties"))) {
            application.load(in);
        }
        String mainClass = application.getProperty("mainClass");
        String mainModule = application.getProperty("mainModule");
        boolean selfContained = Boolean.parseBoolean(application.getProperty("selfContainedModuleGraph", "true"));

        // Reconstruct the launch command a JRE-based deployment would run.
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        Path modulepath = unpacked.resolve("modulepath");
        Path classpath = unpacked.resolve("classpath");
        if (Files.isDirectory(modulepath)) {
            command.add("--module-path");
            command.add(modulepath.toString());
        }
        if (Files.isDirectory(classpath)) {
            command.add("-classpath");
            command.add(classpath.resolve("*").toString());
        }
        if (mainModule != null) {
            if (!selfContained) {
                command.add("--add-modules");
                command.add("ALL-MODULE-PATH");
            }
            command.add("-m");
            command.add(mainModule + "/" + mainClass);
        } else {
            command.add(mainClass);
        }
        command.addAll(List.of(args));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
