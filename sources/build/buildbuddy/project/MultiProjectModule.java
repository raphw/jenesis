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
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        buildExecutor.addModule("process", (process, identified) -> {
            SequencedMap<String, String> modules = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> identifiers = new LinkedHashMap<>();
            for (String identifier : identified.keySet()) {
                Matcher matcher = QUALIFIER.matcher(identifier);
                if (matcher.matches()) {
                    String name = matcher.group(1);
                    modules.put(identifier, name);
                    identifiers.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(identifier);
                }
            }
            process.addStep("group",
                    new Group(identifier -> Optional.of(modules.get(identifier))),
                    modules.sequencedKeySet());
            process.addModule("build", (build, paths) -> {
                SequencedMap<String, SequencedSet<String>> pending = new LinkedHashMap<>();
                Path groups = paths.get("../group").resolve(Group.GROUPS);
                for (Map.Entry<String, SequencedSet<String>> entry : identifiers.entrySet()) {
                    Properties properties = new SequencedProperties();
                    try (Reader reader = Files.newBufferedReader(groups.resolve(entry.getKey() + ".properties"))) {
                        properties.load(reader);
                    }
                    pending.put(entry.getKey(), new LinkedHashSet<>(properties.stringPropertyNames()));
                }
                while (!pending.isEmpty()) {
                    Iterator<Map.Entry<String, SequencedSet<String>>> it = pending.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, SequencedSet<String>> entry = it.next();
                        if (Collections.disjoint(entry.getValue(), pending.keySet())) {
                            SequencedSet<String> dependencies = new LinkedHashSet<>(entry.getValue());
                            identifiers.get(entry.getKey()).forEach(identifier -> dependencies.add("../" + identifier));
                            build.addModule(entry.getKey(), builder.apply(entry.getKey(), identifiers), dependencies);
                            it.remove();
                        }
                    }
                }
            }, Stream.concat(
                    Stream.of("group"),
                    identified.sequencedKeySet().stream()).collect(Collectors.toCollection(LinkedHashSet::new)));
        }, "identify");
    }
}
