package build.jenesis.module;

import module java.base;

public class ModularLayout implements Function<Path, Optional<Path>>, Serializable {

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
        Path moduleName = parent.getFileName();
        if (moduleName == null) {
            return Optional.empty();
        }
        return Optional.of(Path.of(moduleName.toString(), moduleName + suffix));
    }
}
