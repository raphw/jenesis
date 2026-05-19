package build.jenesis.module;

import module java.base;
import build.jenesis.SequencedProperties;
import build.jenesis.step.FilePlacement;

public class ModularPlacement implements FilePlacement {

    private final boolean includeTests;

    public ModularPlacement() {
        this(false);
    }

    public ModularPlacement(boolean includeTests) {
        this.includeTests = includeTests;
    }

    @Override
    public Optional<Path> apply(Path file,
                                SequencedProperties module,
                                SequencedProperties metadata) throws IOException {
        String suffix = switch (file.getFileName().toString()) {
            case "classes.jar" -> ".jar";
            case "sources.jar" -> "-sources.jar";
            case "javadoc.jar" -> "-javadoc.jar";
            default -> null;
        };
        if (suffix == null) {
            return Optional.empty();
        }
        if (!includeTests && module.getProperty("tests") != null) {
            return Optional.empty();
        }
        String moduleName = module.getProperty("module");
        if (moduleName == null) {
            throw new IllegalStateException("Missing 'module' property in module.properties for " + file);
        }
        String version = metadata.getProperty("version");
        if (version != null) {
            return Optional.of(Path.of(moduleName, version, moduleName + suffix));
        }
        return Optional.of(Path.of(moduleName, moduleName + suffix));
    }
}
