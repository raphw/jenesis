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
        public boolean test(Path path) {
            return moduleDescriptor(path) != null;
        }

        @Override
        public boolean place(Path file, List<String> modulePath, List<String> classPath) {
            ModuleDescriptor descriptor = moduleDescriptor(file);
            (descriptor != null ? modulePath : classPath).add(file.toString());
            return descriptor != null && descriptor.isAutomatic();
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

    public boolean place(Path file, List<String> modulePath, List<String> classPath) throws IOException {
        (test(file) ? modulePath : classPath).add(file.toString());
        return false;
    }

    /**
     * Whether the jar (or exploded directory) is an automatic module - one with an
     * {@code Automatic-Module-Name} but no module descriptor. A module path that carries one needs
     * {@code --add-modules ALL-MODULE-PATH} at launch, since an automatic module declares no
     * {@code requires} and so never pulls its own named dependencies into the run-time module graph.
     */
    public static boolean automatic(Path file) {
        ModuleDescriptor descriptor = moduleDescriptor(file);
        return descriptor != null && descriptor.isAutomatic();
    }

    public PathPlacement forModuleInfo(boolean moduleInfoPresent) {
        return moduleInfoPresent ? this : CLASS_PATH;
    }

    public static ModuleDescriptor moduleDescriptor(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Path moduleInfo = path.resolve("module-info.class");
                if (!Files.exists(moduleInfo)) {
                    return null;
                }
                try (InputStream input = Files.newInputStream(moduleInfo)) {
                    return ModuleDescriptor.read(input);
                }
            }
            try (JarFile jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
                JarEntry moduleInfo = jar.getJarEntry("module-info.class");
                if (moduleInfo != null) {
                    try (InputStream input = jar.getInputStream(moduleInfo)) {
                        return ModuleDescriptor.read(input);
                    }
                }
                Manifest manifest = jar.getManifest();
                String automatic = manifest == null
                        ? null
                        : manifest.getMainAttributes().getValue("Automatic-Module-Name");
                return automatic == null ? null : ModuleDescriptor.newAutomaticModule(automatic).build();
            }
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }
}
