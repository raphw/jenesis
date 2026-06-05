package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;

public final class Dependencies {

    private Dependencies() {
    }

    public static List<Path> select(Path folder, String scope) throws IOException {
        Path file = folder.resolve(BuildStep.DEPENDENCY_INDEX);
        if (!Files.exists(file)) {
            return List.of();
        }
        SequencedProperties properties = SequencedProperties.ofFiles(file);
        List<Path> selected = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            int slash = key.indexOf('/');
            if (slash > 0 && key.substring(0, slash).equals(scope)) {
                Path jar = folder.resolve(properties.getProperty(key)).normalize();
                if (Files.exists(jar)) {
                    selected.add(jar);
                }
            }
        }
        return selected;
    }
}
