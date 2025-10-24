package build.jenesis.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Group;

import module java.base;

public class MultiProjectModule implements BuildExecutorModule {

    public static final String IDENTIFY = "identify", GROUP = "group", BUILD = "build", MODULE = "module";

    private final BuildExecutorModule identifier;
    private final Function<String, Optional<String>> resolver;
    private final Function<SequencedMap<String, SequencedSet<String>>, MultiProject> factory;

    public MultiProjectModule(BuildExecutorModule identifier,
                              Function<String, Optional<String>> resolver,
                              Function<SequencedMap<String, SequencedSet<String>>, MultiProject> factory) {
        this.identifier = identifier;
        this.resolver = resolver;
        this.factory = factory;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule(IDENTIFY, identifier);
        buildExecutor.addModule(BUILD, (process, identified) -> {
            SequencedMap<String, String> modules = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> identifiers = new LinkedHashMap<>();
            for (String identifier : identified.sequencedKeySet()) {
                if (identifier.startsWith(PREVIOUS + IDENTIFY + "/")) {
                    resolver.apply(identifier.substring(12)).ifPresent(module -> {
                        String name = URLEncoder.encode(module, StandardCharsets.UTF_8);
                        if (name.isEmpty()) {
                            throw new IllegalArgumentException("Module name must not be empty");
                        }
                        modules.put(identifier, name);
                        identifiers.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(identifier);
                    });
                }
            }
            process.addStep(GROUP,
                    new Group(identifier -> Optional.of(modules.get(identifier))),
                    modules.sequencedKeySet());
            process.addModule(MODULE, (build, paths) -> {
                SequencedMap<String, SequencedSet<String>> projects = new LinkedHashMap<>();
                Path groups = paths.get(PREVIOUS + GROUP).resolve(Group.GROUPS);
                for (Map.Entry<String, SequencedSet<String>> entry : identifiers.entrySet()) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(groups.resolve(entry.getKey() + ".properties"))) {
                        properties.load(reader);
                    }
                    projects.put(entry.getKey(), new LinkedHashSet<>(properties.stringPropertyNames()));
                }
                MultiProject project = factory.apply(projects);
                SequencedMap<String, SequencedSet<String>> pending = new LinkedHashMap<>(projects);
                while (!pending.isEmpty()) {
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
                            build.addModule(entry.getKey(), project.module(entry.getKey(),
                                    dependencies,
                                    arguments), Stream.of(
                                            arguments.sequencedKeySet().stream(),
                                            dependencies.sequencedKeySet().stream(),
                                            inherited.sequencedKeySet().stream()
                                                    .map(identifier -> PREVIOUS.repeat(2) + identifier))
                                    .flatMap(Function.identity()));
                            it.remove();
                        }
                    }
                }
            }, Stream.concat(Stream.of(GROUP), identified.sequencedKeySet().stream()));
        }, Stream.concat(Stream.of(IDENTIFY), inherited.sequencedKeySet().stream()));
    }
}
