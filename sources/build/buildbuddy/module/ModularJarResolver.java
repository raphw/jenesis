package build.buildbuddy.module;

import build.buildbuddy.Repository;
import build.buildbuddy.RepositoryItem;
import build.buildbuddy.Resolver;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ModularJarResolver implements Resolver {

    private final Repository repository;

    public ModularJarResolver(Repository repository) {
        this.repository = repository;
    }

    @Override
    public SequencedMap<String, String> dependencies(Executor executor, SequencedSet<String> coordinates) throws IOException {
        SequencedMap<String, String> dependencies = new LinkedHashMap<>();
        coordinates.forEach(coordinate -> dependencies.put(coordinate, ""));
        Queue<String> queue = new ArrayDeque<>(coordinates);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            RepositoryItem item = repository.fetch(
                    executor,
                    current).orElseThrow(() -> new IllegalArgumentException("Cannot resolve module: " + current));
            Path file = item.getFile().orElse(null);
            ModuleDescriptor descriptor;
            if (file == null) {
                try (ZipInputStream inputStream = new ZipInputStream(item.toInputStream())) {
                    descriptor = toDescriptor(inputStream, current);
                }
            } else {
                descriptor = ModuleFinder.of(file).findAll().stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Does not describe a module: " + current))
                        .descriptor();
            }
            if (descriptor.isAutomatic()) {
                throw new IllegalArgumentException("No module-info.class found for " + current);
            }
            descriptor.requires().stream()
                    .filter(requires -> !requires.name().equals("java.base"))
                    .filter(requires -> !requires.accessFlags().contains(AccessFlag.STATIC_PHASE))
                    .map(ModuleDescriptor.Requires::name)
                    .forEach(module -> {
                        if (dependencies.putIfAbsent(module, "") == null) {
                            queue.add(module);
                        }
                    });
        }
        return dependencies;
    }

    private static ModuleDescriptor toDescriptor(ZipInputStream inputStream, String module) throws IOException {
        ZipEntry entry;
        while ((entry = inputStream.getNextEntry()) != null) {
            if (entry.getName().equals("module-info.class")) { // TODO: MR-JARs? Better API in JDK to do this?
                return ModuleDescriptor.read(inputStream);
            }
        }
        throw new IllegalArgumentException("No module-info.class found for " + module);
    }
}
