package build.jenesis.module;

import module java.base;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;

public class ModularJarResolver implements Resolver {

    private final boolean resolveAutomaticModules;

    private final Resolver fallback;

    public ModularJarResolver(boolean resolveAutomaticModules) {
        this.resolveAutomaticModules = resolveAutomaticModules;
        fallback = null;
    }

    public ModularJarResolver(boolean resolveAutomaticModules, Resolver fallback) {
        this.resolveAutomaticModules = resolveAutomaticModules;
        this.fallback = fallback;
    }

    @Override
    public SequencedMap<String, String> dependencies(Executor executor,
                                                     String prefix,
                                                     Map<String, Repository> repositories,
                                                     SequencedSet<String> coordinates,
                                                     SequencedMap<String, String> versions,
                                                     boolean compile) throws IOException {
        SequencedMap<String, String> dependencies = new LinkedHashMap<>();
        SequencedSet<String> resolved = new LinkedHashSet<>();
        SequencedSet<String> unresolved = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>(coordinates);
        while (!queue.isEmpty()) { // TODO: consider multi-release-jars better?
            String current = queue.remove();
            if (resolved.contains(current) || unresolved.contains(current)) {
                continue;
            }
            RepositoryItem item = repositories.getOrDefault(prefix, Repository.empty()).fetch(
                    executor,
                    current).orElse(null);
            if (item == null) {
                if (fallback == null) {
                    throw new IllegalArgumentException("No module found for " + current);
                }
                unresolved.add(current);
            } else {
                Path file = item.getFile().orElse(null);
                ModuleDescriptor descriptor;
                if (file == null) {
                    try (ZipInputStream inputStream = new ZipInputStream(item.toInputStream())) {
                        ModuleDescriptor read = null;
                        ZipEntry entry;
                        while ((entry = inputStream.getNextEntry()) != null) {
                            if (entry.getName().equals("module-info.class")
                                    || entry.getName().startsWith("META-INF/versions/")
                                    && entry.getName().endsWith("module-info.class")) {
                                read = ModuleDescriptor.read(inputStream);
                                break;
                            }
                        }
                        descriptor = read == null ? ModuleDescriptor.newAutomaticModule(current).build() : read;
                    }
                } else {
                    descriptor = ModuleFinder.of(file).findAll().stream()
                            .findFirst()
                            .map(ModuleReference::descriptor)
                            .orElseGet(() -> ModuleDescriptor.newAutomaticModule(current).build());
                }
                if (descriptor.isAutomatic()) {
                    if (resolveAutomaticModules) {
                        continue;
                    }
                    throw new IllegalArgumentException("No module-info.class found for " + current);
                }
                String pin = versions.get(current);
                String version = pin != null ? pin : descriptor.rawVersion().orElse(null);
                dependencies.put(prefix + "/" + current + (version == null ? "" : "/" + version), "");
                resolved.add(current);
                descriptor.requires().stream()
                        .filter(requires -> !requires.accessFlags().contains(AccessFlag.STATIC_PHASE)
                                || compile && requires.accessFlags().contains(AccessFlag.TRANSITIVE))
                        .map(ModuleDescriptor.Requires::name)
                        .filter(module -> !module.startsWith("java.") && !module.startsWith("jdk."))
                        .sorted()
                        .forEach(module -> {
                            if (!unresolved.contains(module) && !resolved.contains(module)) {
                                queue.add(module);
                            }
                        });
            }
        }
        if (!unresolved.isEmpty()) {
            fallback.dependencies(executor, prefix, repositories, unresolved, versions, compile).forEach(dependencies::putIfAbsent);
        }
        return dependencies;
    }

}
