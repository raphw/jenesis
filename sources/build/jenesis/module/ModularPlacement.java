package build.jenesis.module;

import module java.base;

public class ModularPlacement implements Function<Path, Optional<Path>>, Serializable {

    private final String version;

    public ModularPlacement() {
        this(System.getProperty("jenesis.buildVersion"));
    }

    public ModularPlacement(String version) {
        this.version = version == null || version.isEmpty() ? null : version;
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
        String moduleName = readModuleName(parent.resolve("metadata.properties"));
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

    private static String readModuleName(Path metadata) {
        if (!Files.isRegularFile(metadata)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(metadata)) {
            properties.load(reader);
        } catch (IOException _) {
            return null;
        }
        return properties.getProperty("project.module");
    }
}
