package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.SequencedProperties;
import build.buildbuddy.step.Group;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiProjectModule implements BuildExecutorModule {

    private final Pattern QUALIFIER = Pattern.compile("../identify/module/([a-zA-Z0-9-]+)(?:/[a-zA-Z0-9-]+)?");

    private final BuildExecutorModule identifier;
    private final Function<String, Optional<String>> resolver;
    private final MultiProjectBuilder builder;

    public MultiProjectModule(BuildExecutorModule identifier,
                              Function<String, Optional<String>> resolver,
                              MultiProjectBuilder builder) {
        this.identifier = identifier;
        this.resolver = resolver;
        this.builder = builder;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule("identify", identifier, resolver);
        buildExecutor.addModule("process", (module, identified) -> {
            SequencedMap<String, String> names = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> identifiers = new LinkedHashMap<>();
            for (String identifier : identified.keySet()) {
                Matcher matcher = QUALIFIER.matcher(identifier);
                if (matcher.matches()) {
                    String name = matcher.group(1);
                    names.put(identifier, name);
                    identifiers.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(identifier);
                }
            }
            module.addStep("group",
                    new Group(identifier -> Optional.of(names.get(identifier))),
                    names.sequencedKeySet());
            module.addModule("execute", (build, paths) -> {
                Path groups = paths.get("../group").resolve(Group.GROUPS);
                SequencedMap<String, SequencedSet<String>> pending = new LinkedHashMap<>();




                for (String name : identifiers.sequencedKeySet()) {
                    Path file = groups.resolve(name + ".properties");
                    if (Files.exists(file)) {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        pending.put(name, new LinkedHashSet<>(properties.stringPropertyNames()));
                    } else {
                        pending.put(name, new LinkedHashSet<>());
                    }
                }
                while (!pending.isEmpty()) {
                    Iterator<Map.Entry<String, SequencedSet<String>>> it = pending.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, SequencedSet<String>> entry = it.next();
                        if (Collections.disjoint(entry.getValue(), pending.keySet())) {
                            SequencedSet<String> dependencies = new LinkedHashSet<>();
                            SequencedMap<String, SequencedSet<String>> x = new LinkedHashMap<>();
                            entry.getValue().forEach(dependency -> {
                                SequencedSet<String> dependents = identifiers.get(dependency);
                                x.put(dependency, dependents);
                                dependencies.addAll(dependents);
                            });
                            build.addModule(entry.getKey(), builder.apply(entry.getKey(), x), dependencies);
                            it.remove();
                        }
                    }
                }
            }, "group");
        }, "identify");
    }
}
