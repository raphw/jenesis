package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.SequencedProperties;
import build.buildbuddy.step.Group;

import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultiProjectModule implements BuildExecutorModule {

    public static final String MODULE = "module", SOURCE = "source", RESOURCE = "resource";

    private final Pattern QUALIFIER = Pattern.compile("../identify/module/([a-zA-Z0-9-]+)/([a-z]+)(?:-[0-9]+)?");

    private final BuildExecutorModule identifier;
    private final Function<String, Optional<String>> resolver;

    public MultiProjectModule(BuildExecutorModule identifier, Function<String, Optional<String>> resolver) {
        this.identifier = identifier;
        this.resolver = resolver;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule("identify", identifier, resolver);
        buildExecutor.addModule("process", (module, identified) -> {
            SequencedMap<String, String> identifiers = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> sources = new LinkedHashMap<>(), resources = new LinkedHashMap<>();
            for (String identifier : identified.keySet()) {
                Matcher matcher = QUALIFIER.matcher(identifier);
                if (matcher.matches()) {
                    String name = matcher.group(1), type = matcher.group(2);
                    switch (type) {
                        case MODULE -> identifiers.put(identifier, name);
                        case SOURCE -> sources.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(identifier);
                        case RESOURCE -> resources.computeIfAbsent(name, _ -> new LinkedHashSet<>()).add(identifier);
                        default -> throw new IllegalArgumentException("Unexpected module type: " + type);
                    }
                }
            }
            module.addStep("group", new Group(identifiers::get), identifiers.sequencedKeySet());
            buildExecutor.addModule("execute", (_, paths) -> {
                Path groups = paths.get("../group").resolve(Group.GROUPS);
                Map<String, Properties> modules = new HashMap<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(groups, "*.properties")) {
                    for (Path file : stream) {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        String name = file.getFileName().toString();
                        modules.put(name.substring(0, name.length() - 11), properties);
                    }
                }
            });
        }, "identify");
    }
}
