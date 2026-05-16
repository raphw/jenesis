package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;

public interface TestEngine extends Serializable {

    String module();

    Set<String> coordinates();

    String markerClass();

    String mainClass();

    String prefix();

    default String methodPrefix() {
        return null;
    }

    default Map<String, String> versions() {
        return Map.of();
    }

    List<String> arguments();

    static Optional<TestEngine> of(Iterable<Path> folders) throws IOException {
        String jupiterEntry = JUnit5.JUPITER_MARKER_CLASS.replace('.', '/') + ".class";
        String junit4Entry = JUnit4.MARKER_CLASS.replace('.', '/') + ".class";
        boolean jupiter = false, junit4 = false;
        for (Path folder : folders) {
            Path artifacts = folder.resolve(BuildStep.ARTIFACTS);
            if (!Files.exists(artifacts)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                for (Path file : stream) {
                    try (JarFile jarFile = new JarFile(file.toFile())) {
                        if (!jupiter && jarFile.getEntry(jupiterEntry) != null) {
                            jupiter = true;
                        }
                        if (!junit4 && jarFile.getEntry(junit4Entry) != null) {
                            junit4 = true;
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception _) {
                    }
                }
            }
        }
        if (jupiter) {
            return Optional.of(new JUnit5());
        } else if (junit4) {
            return Optional.of(new JUnit4());
        }
        return Optional.empty();
    }

    static boolean hasRunner(TestEngine engine, Iterable<Path> folders) throws IOException {
        String entry = engine.mainClass().replace('.', '/') + ".class";
        for (Path folder : folders) {
            Path artifacts = folder.resolve(BuildStep.ARTIFACTS);
            if (!Files.exists(artifacts)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                for (Path file : stream) {
                    try (JarFile jarFile = new JarFile(file.toFile())) {
                        if (jarFile.getEntry(entry) != null) {
                            return true;
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception _) {
                    }
                }
            }
        }
        return false;
    }

}
