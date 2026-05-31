package build.jenesis;

import module java.base;

public enum ModulePathPredicate {

    CLASS_PATH(false) {
        @Override
        public boolean test(Path entry) {
            return false;
        }

        @Override
        public build.jenesis.ModulePathPredicate requiresModules(boolean requiresModules) {
            return requiresModules ? INFERRED : this;
        }
    },

    MODULE_PATH(true) {
        @Override
        public boolean test(Path entry) {
            return true;
        }
    },

    INFERRED(true) {
        @Override
        public boolean test(Path entry) throws IOException {
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
        }
    };

    private final boolean modular;

    ModulePathPredicate(boolean modular) {
        this.modular = modular;
    }

    public abstract boolean test(Path entry) throws IOException;

    public boolean modular() {
        return modular;
    }

    public ModulePathPredicate requiresModules(boolean requiresModules) {
        return this;
    }
}
