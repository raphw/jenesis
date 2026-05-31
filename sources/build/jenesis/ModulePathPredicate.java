package build.jenesis;

import module java.base;

@FunctionalInterface
public interface ModulePathPredicate extends Serializable {

    boolean test(Path entry) throws IOException;

    ModulePathPredicate INFERRED = entry -> {
        if (Files.isDirectory(entry)) {
            return Files.exists(entry.resolve("module-info.class"));
        }
        ModuleReference reference;
        try {
            reference = ModuleFinder.of(entry).findAll().stream().findFirst().orElse(null);
        } catch (FindException _) {
            return false;
        }
        if (reference == null) {
            return false;
        }
        if (!reference.descriptor().isAutomatic()) {
            return true;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            Manifest manifest = jar.getManifest();
            return manifest != null
                    && manifest.getMainAttributes().getValue("Automatic-Module-Name") != null;
        }
    };
}
