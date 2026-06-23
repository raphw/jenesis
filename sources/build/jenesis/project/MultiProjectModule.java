package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Group;
import build.jenesis.step.Inventory;

public record MultiProjectModule(BuildExecutorModule identifier,
                                 Function<String, Optional<String>> resolver,
                                 Function<SequencedMap<String, SequencedSet<String>>, MultiProject> factory)
        implements BuildExecutorModule {

    public static final String IDENTIFIER = "identifier",
            COMPOSE = "compose",
            MODULE = "module",
            PACKAGE = "package",
            SELECTION = "selection";

    public static final String SOURCES = "sources",
            MANIFESTS = "manifests",
            COORDINATES = "coordinates",
            PREPARE = "prepare",
            PRODUCE = "produce",
            ASSIGN = "assign",
            INVENTORY = "inventory",
            DEPENDENCIES = "dependencies";

    private static final String GROUP = "group";

    public static final String IDENTIFIER_PATH = PREVIOUS.repeat(3) + IDENTIFIER + "/";

    @Override
    public Optional<String> resolve(String path) {
        if (path.startsWith(IDENTIFIER + "/")) {
            if (path.endsWith("/" + COORDINATES)) {
                return Optional.empty();
            }
            return Optional.of(path.substring(IDENTIFIER.length() + 1));
        }
        String composeModulePrefix = COMPOSE + "/" + MODULE + "/";
        if (path.startsWith(composeModulePrefix)) {
            return Optional.of(path.substring(composeModulePrefix.length()));
        }
        return Optional.empty();
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule(IDENTIFIER, identifier, inherited.sequencedKeySet().stream());
        buildExecutor.addModule(COMPOSE, (process, identified) -> {
            SequencedMap<String, String> modules = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> identifiers = new LinkedHashMap<>();
            for (String identifier : identified.sequencedKeySet()) {
                if (identifier.startsWith(PREVIOUS + IDENTIFIER + "/")) {
                    resolver.apply(identifier.substring(PREVIOUS.length() + IDENTIFIER.length() + 1)).ifPresent(module -> {
                        if (module.isEmpty()) {
                            throw new IllegalArgumentException("Module name must not be empty");
                        }
                        modules.put(identifier, module);
                        identifiers.computeIfAbsent(module, _ -> new LinkedHashSet<>()).add(identifier);
                    });
                }
            }
            process.addStep(GROUP,
                    new Group(identifier -> Optional.of(modules.get(identifier)))
                            .requiresPath(BuildStep.REQUIRES),
                    modules.sequencedKeySet());
            process.addModule(MODULE, (build, paths) -> {
                SequencedMap<String, SequencedSet<String>> projects = new LinkedHashMap<>();
                Path groups = paths.get(PREVIOUS + GROUP).resolve(Group.GROUPS);
                for (Map.Entry<String, SequencedSet<String>> entry : identifiers.entrySet()) {
                    SequencedProperties properties = SequencedProperties.ofFiles(groups.resolve(entry.getKey() + ".properties"));
                    projects.put(entry.getKey(), new LinkedHashSet<>(properties.stringPropertyNames()));
                }
                MultiProject project = factory.apply(projects);
                SequencedMap<String, AssemblyDescriptor> assemblies = new LinkedHashMap<>();
                SequencedMap<String, SequencedSet<String>> pending = new LinkedHashMap<>(projects);
                while (!pending.isEmpty()) {
                    boolean progressed = false;
                    Iterator<Map.Entry<String, SequencedSet<String>>> it = pending.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, SequencedSet<String>> entry = it.next();
                        if (Collections.disjoint(entry.getValue(), pending.keySet())) {
                            SequencedMap<String, Path> arguments = new LinkedHashMap<>();
                            identifiers.get(entry.getKey()).forEach(identifier -> arguments.put(
                                    PREVIOUS + identifier,
                                    paths.get(PREVIOUS + identifier)));
                            SequencedMap<String, SequencedSet<String>> dependencies = new LinkedHashMap<>();
                            Queue<String> queue = new LinkedList<>(entry.getValue());
                            while (!queue.isEmpty()) {
                                String current = queue.remove();
                                if (!dependencies.containsKey(current)) {
                                    SequencedSet<String> values = projects.get(current);
                                    dependencies.put(current, values);
                                    queue.addAll(values);
                                }
                            }
                            AssemblyDescriptor assembly = project.module(entry.getKey(), dependencies, arguments);
                            build.addModule(entry.getKey(), assembly.build(), Stream.of(
                                            arguments.sequencedKeySet().stream(),
                                            dependencies.sequencedKeySet().stream(),
                                            inherited.sequencedKeySet().stream()
                                                    .map(identifier -> PREVIOUS.repeat(2) + identifier))
                                    .flatMap(Function.identity()));
                            assemblies.put(entry.getKey(), assembly);
                            it.remove();
                            progressed = true;
                        }
                    }
                    if (!progressed) {
                        throw new IllegalStateException("Cyclic module dependencies: " + pending.keySet());
                    }
                }
                if (assemblies.values().stream().anyMatch(assembly -> !assembly.tail().isEmpty())) {
                    build.addModule(PACKAGE, (packaged, packageInherited) -> {
                        for (Map.Entry<String, AssemblyDescriptor> entry : assemblies.entrySet()) {
                            SequencedMap<String, BuildExecutorModule> tail = entry.getValue().tail();
                            if (tail.isEmpty()) {
                                continue;
                            }
                            String ownPrefix = PREVIOUS + entry.getKey() + "/";
                            SequencedMap<String, String> inputs = new LinkedHashMap<>();
                            for (String key : packageInherited.sequencedKeySet()) {
                                if (key.startsWith(ownPrefix)) {
                                    inputs.put(key, key.substring(ownPrefix.length()));
                                } else if (Files.isRegularFile(packageInherited.get(key).resolve(Inventory.INVENTORY))) {
                                    inputs.put(key, SELECTION + "/" + key.substring(PREVIOUS.length()));
                                }
                            }
                            packaged.addModule(entry.getKey(), (phases, phaseInherited) -> {
                                SequencedMap<String, String> phaseInputs = new LinkedHashMap<>();
                                for (String key : phaseInherited.sequencedKeySet()) {
                                    phaseInputs.put(key, key.substring(PREVIOUS.length()));
                                }
                                for (Map.Entry<String, BuildExecutorModule> phase : tail.entrySet()) {
                                    phases.addModule(phase.getKey(), phase.getValue(), phaseInputs);
                                }
                            }, inputs);
                        }
                    }, assemblies.sequencedKeySet().stream());
                }
            }, Stream.concat(Stream.of(GROUP), identified.sequencedKeySet().stream()));
        }, Stream.concat(Stream.of(IDENTIFIER), inherited.sequencedKeySet().stream()));
    }
}
