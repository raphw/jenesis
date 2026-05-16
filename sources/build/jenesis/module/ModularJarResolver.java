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
        SequencedMap<String, String> propagated = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>(coordinates);
        int runtime = Runtime.version().feature();
        while (!queue.isEmpty()) {
            String raw = queue.remove();
            int versionSplit = raw.indexOf('/');
            String current = versionSplit < 0 ? raw : raw.substring(0, versionSplit);
            String inlineVersion = versionSplit < 0 ? null : raw.substring(versionSplit + 1);
            if (resolved.contains(current) || unresolved.contains(current)) {
                continue;
            }
            String pinValue = versions.get(current);
            String pin, checksum;
            if (pinValue == null) {
                pin = null;
                checksum = null;
            } else {
                int split = pinValue.indexOf(' ');
                pin = split < 0 ? pinValue : pinValue.substring(0, split);
                checksum = split < 0 ? null : pinValue.substring(split + 1).trim();
            }
            String hint = propagated.get(current);
            String requested = pin != null ? pin : (hint != null ? hint : inlineVersion);
            Repository repository = repositories.getOrDefault(prefix, Repository.empty());
            RepositoryItem item = requested == null
                    ? repository.fetch(executor, current).orElse(null)
                    : repository.fetch(executor, current + "/" + requested)
                            .or(() -> {
                                try {
                                    return repository.fetch(executor, current);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }).orElse(null);
            if (item == null) {
                if (fallback == null) {
                    throw new IllegalArgumentException("No module found for " + current);
                }
                unresolved.add(current);
            } else {
                Path file = item.getFile().orElse(null);
                ModuleDescriptor descriptor;
                if (file == null) {
                    NavigableMap<Integer, byte[]> candidates = new TreeMap<>();
                    try (ZipInputStream inputStream = new ZipInputStream(item.toInputStream())) {
                        ZipEntry entry;
                        while ((entry = inputStream.getNextEntry()) != null) {
                            String name = entry.getName();
                            int version;
                            if (name.equals("module-info.class")) {
                                version = 0;
                            } else if (name.startsWith("META-INF/versions/")
                                    && name.endsWith("/module-info.class")) {
                                String segment = name.substring(
                                        "META-INF/versions/".length(),
                                        name.length() - "/module-info.class".length());
                                try {
                                    version = Integer.parseInt(segment);
                                } catch (NumberFormatException _) {
                                    continue;
                                }
                                if (version > runtime) {
                                    continue;
                                }
                            } else {
                                continue;
                            }
                            candidates.put(version, inputStream.readAllBytes());
                        }
                    }
                    Map.Entry<Integer, byte[]> selected = candidates.lastEntry();
                    descriptor = selected == null
                            ? ModuleDescriptor.newAutomaticModule(current).build()
                            : ModuleDescriptor.read(ByteBuffer.wrap(selected.getValue()));
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
                String version = requested != null ? requested : descriptor.rawVersion().orElse(null);
                dependencies.put(prefix + "/" + current + (version == null ? "" : "/" + version),
                        checksum == null ? "" : checksum);
                resolved.add(current);
                descriptor.requires().stream()
                        .filter(requires -> !requires.accessFlags().contains(AccessFlag.STATIC_PHASE)
                                || compile && requires.accessFlags().contains(AccessFlag.TRANSITIVE))
                        .filter(requires -> !requires.name().startsWith("java.") && !requires.name().startsWith("jdk."))
                        .sorted(Comparator.comparing(ModuleDescriptor.Requires::name))
                        .forEach(requires -> {
                            String name = requires.name();
                            requires.rawCompiledVersion().ifPresent(v -> propagated.putIfAbsent(name, v));
                            if (!unresolved.contains(name) && !resolved.contains(name)) {
                                queue.add(name);
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
