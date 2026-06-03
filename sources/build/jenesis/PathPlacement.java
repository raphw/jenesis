package build.jenesis;

import module java.base;

public enum PathPlacement {

    CLASS_PATH(false) {
        @Override
        public boolean test(Path path) {
            return false;
        }

        @Override
        public PathPlacement forModuleInfo(boolean moduleInfoPresent) {
            return moduleInfoPresent ? INFERRED : this;
        }
    },

    MODULE_PATH(true) {
        @Override
        public boolean test(Path path) {
            return true;
        }
    },

    INFERRED(true) {
        @Override
        public boolean test(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                return Files.exists(path.resolve("module-info.class"));
            }
            ModuleReference reference;
            try {
                reference = ModuleFinder.of(path).findAll().stream().findFirst().orElse(null);
            } catch (FindException _) {
                return false;
            }
            if (reference == null) {
                return false;
            }
            if (!reference.descriptor().isAutomatic()) {
                return true;
            }
            try (JarFile jar = new JarFile(path.toFile())) {
                Manifest manifest = jar.getManifest();
                return manifest != null
                        && manifest.getMainAttributes().getValue("Automatic-Module-Name") != null;
            }
        }
    };

    private final boolean modular;

    PathPlacement(boolean modular) {
        this.modular = modular;
    }

    public boolean modular() {
        return modular;
    }

    public abstract boolean test(Path path) throws IOException;

    public PathPlacement forModuleInfo(boolean moduleInfoPresent) {
        return moduleInfoPresent ? this : CLASS_PATH;
    }
}
