package build.jenesis.step;

import build.jenesis.BuildStep;

import module java.base;

public interface TestEngine extends Serializable {

    String module();

    Set<String> coordinates();

    String markerClass();

    String mainClass();

    String prefix();

    List<String> arguments();

    static Optional<TestEngine> of(Iterable<Path> folders) throws IOException {
        return scan(folders, Arrays.asList(TestDefaultEngine.values()), TestEngine::markerClass);
    }

    static boolean hasRunner(TestEngine engine, Iterable<Path> folders) throws IOException {
        return scan(folders, List.of(engine), TestEngine::mainClass).isPresent();
    }

    private static Optional<TestEngine> scan(Iterable<Path> folders,
                                             List<? extends TestEngine> candidates,
                                             Function<TestEngine, String> probe) throws IOException {
        TestEngine result = null;
        int rank = -1;
        for (Path folder : folders) {
            Path artifacts = folder.resolve(BuildStep.ARTIFACTS);
            if (!Files.exists(artifacts)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                for (Path file : stream) {
                    try (JarFile jarFile = new JarFile(file.toFile())) {
                        for (int index = 0; index < candidates.size(); index++) {
                            TestEngine candidate = candidates.get(index);
                            String className = probe.apply(candidate);
                            if (className == null) {
                                continue;
                            }
                            if (jarFile.getEntry(className.replace('.', '/') + ".class") != null) {
                                if (result == null || index > rank) {
                                    result = candidate;
                                    rank = index;
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception _) {
                    }
                }
            }
        }
        return Optional.ofNullable(result);
    }
}
