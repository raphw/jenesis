package build.jenesis.step;

import module java.base;
import java.util.jar.Attributes;
import build.jenesis.BuildStep;

public interface TestEngine extends Serializable {

    String module();

    SequencedSet<String> coordinates();

    String mainClass();

    default Map<String, String> versions() {
        return Map.of();
    }

    default Map<String, String> properties() {
        return Map.of();
    }

    List<String> arguments();

    Map<String, String> markers();

    Map<String, String> runnerMarkers();

    List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods);

    static Optional<TestEngine> of(Iterable<Path> folders) throws IOException {
        List<Attributes> manifests = scanManifests(folders);
        for (TestEngine engine : List.<TestEngine>of(new JUnit5(), new JUnit4(), new TestNG())) {
            if (matches(manifests, engine.markers())) {
                return Optional.of(engine);
            }
        }
        return Optional.empty();
    }

    static boolean hasRunner(TestEngine engine, Iterable<Path> folders) throws IOException {
        return matches(scanManifests(folders), engine.runnerMarkers());
    }

    private static boolean matches(List<Attributes> manifests, Map<String, String> required) {
        if (required.isEmpty()) {
            return false;
        }
        for (Attributes attributes : manifests) {
            boolean ok = true;
            for (Map.Entry<String, String> entry : required.entrySet()) {
                if (!Objects.equals(entry.getValue(), attributes.getValue(entry.getKey()))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private static List<Attributes> scanManifests(Iterable<Path> folders) throws IOException {
        List<Attributes> manifests = new ArrayList<>();
        for (Path folder : folders) {
            for (String jarFolder : List.of(BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path jars = folder.resolve(jarFolder);
                if (!Files.exists(jars)) {
                    continue;
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(jars)) {
                    for (Path file : stream) {
                        if (!Files.isRegularFile(file)) {
                            continue;
                        }
                        try (JarInputStream jarStream = new JarInputStream(Files.newInputStream(file))) {
                            Manifest manifest = jarStream.getManifest();
                            if (manifest != null) {
                                manifests.add(manifest.getMainAttributes());
                            }
                        } catch (IOException e) {
                            throw e;
                        } catch (Exception _) {
                        }
                    }
                }
            }
        }
        return manifests;
    }
}
