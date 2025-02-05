package build.buildbuddy.module;

import build.buildbuddy.Repository;
import build.buildbuddy.RepositoryItem;
import build.buildbuddy.Resolver;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
                                                     SequencedSet<String> coordinates) throws IOException {
        SequencedMap<String, String> dependencies = new LinkedHashMap<>();
        SequencedSet<String> unresolved = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>(coordinates);
        while (!queue.isEmpty()) { // TODO: consider multi-release-jars better?
            String current = queue.remove();
            RepositoryItem item = repositories.getOrDefault(prefix, Repository.empty()).fetch(
                    executor,
                    current).orElse(null);
            if (item == null) {
                if (fallback == null) {
                    throw new IllegalArgumentException("No module found for " + current);
                }
                unresolved.add(current);
            } else {
                dependencies.put(prefix + "/" + current, "");
                Path file = item.getFile().orElse(null);
                ModuleDescriptor descriptor;
                if (file == null) {
                    try (ZipInputStream inputStream = new ZipInputStream(item.toInputStream())) {
                        descriptor = toDescriptor(inputStream, current);
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
                descriptor.requires().stream()
                        .filter(requires -> !requires.accessFlags().contains(AccessFlag.STATIC_PHASE))
                        .map(ModuleDescriptor.Requires::name)
                        .filter(module -> !module.startsWith("java.") && !module.startsWith("jdk."))
                        .forEach(module -> {
                            if (!unresolved.contains(module) && !dependencies.containsKey(prefix + "/" + module)) {
                                queue.add(module);
                            }
                        });
            }
        }
        if (!unresolved.isEmpty()) {
            fallback.dependencies(executor, prefix, repositories, unresolved).forEach(dependencies::putIfAbsent);
        }
        return dependencies;
    }

    private static ModuleDescriptor toDescriptor(ZipInputStream inputStream, String module) throws IOException {
        ZipEntry entry;
        while ((entry = inputStream.getNextEntry()) != null) {
            if (entry.getName().equals("module-info.class")
                    || entry.getName().startsWith("META-INF/versions/")
                    && entry.getName().endsWith("module-info.class")) {
                return ModuleDescriptor.read(inputStream);
            }
        }
        return ModuleDescriptor.newAutomaticModule(module).build();
    }
}
