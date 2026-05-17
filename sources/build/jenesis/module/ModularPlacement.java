package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;

public class ModularPlacement implements Function<Path, Optional<Path>>, Serializable {

    private final String version;
    private final boolean includeTests;

    public ModularPlacement() {
        this(System.getProperty("jenesis.buildVersion"), false);
    }

    public ModularPlacement(boolean includeTests) {
        this(System.getProperty("jenesis.buildVersion"), includeTests);
    }

    public ModularPlacement(String version) {
        this(version, false);
    }

    public ModularPlacement(String version, boolean includeTests) {
        this.version = version == null || version.isEmpty() ? null : version;
        this.includeTests = includeTests;
    }

    @Override
    public Optional<Path> apply(Path file) {
        Path filename = file.getFileName();
        if (filename == null) {
            return Optional.empty();
        }
        String suffix = switch (filename.toString()) {
            case "classes.jar" -> ".jar";
            case "sources.jar" -> "-sources.jar";
            case "javadoc.jar" -> "-javadoc.jar";
            default -> null;
        };
        if (suffix == null) {
            return Optional.empty();
        }
        Path parent = file.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        Properties module = readProperties(parent.resolve(BuildStep.MODULE));
        if (!includeTests && module != null && module.getProperty("tests") != null) {
            return Optional.empty();
        }
        String moduleName = module == null ? null : module.getProperty("module");
        if (moduleName == null) {
            Path dir = parent.getFileName();
            if (dir == null) {
                return Optional.empty();
            }
            moduleName = dir.toString();
        }
        if (version != null) {
            return Optional.of(Path.of(moduleName, version, moduleName + suffix));
        }
        return Optional.of(Path.of(moduleName, moduleName + suffix));
    }

    private static Properties readProperties(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException _) {
            return null;
        }
        return properties;
    }
}
