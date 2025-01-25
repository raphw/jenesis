package build.buildbuddy.project;

import build.buildbuddy.*;
import build.buildbuddy.step.Group;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MultiProjectModule implements BuildExecutorModule {

    public static final String IDENTIFY = "identify", GROUP = "group", BUILD = "build", MODULE = "module";
    public static final String RESOLVE = "resolve", ASSEMBLE = "assemble";

    private final Pattern QUALIFIER = Pattern.compile("../identify/module/([a-zA-Z0-9-]+)(?:/[a-zA-Z0-9-]+)?");

    private final String algorithm;
    private final BuildExecutorModule identifier;
    private final Function<String, Optional<String>> resolver;
    private final Function<SequencedMap<String, SequencedSet<String>>, MultiProject> factory;

    public MultiProjectModule(String algorithm,
                              BuildExecutorModule identifier,
                              Function<String, Optional<String>> resolver,
                              Function<SequencedMap<String, SequencedSet<String>>, MultiProject> factory) {
        this.algorithm = algorithm;
        this.identifier = identifier;
        this.resolver = resolver;
        this.factory = factory;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addModule(IDENTIFY, identifier, resolver);
        buildExecutor.addModule(BUILD, (process, identified) -> {
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
                            build.addModule(entry.getKey(), (group, previous) -> {
                                group.addStep(RESOLVE,
                                        new DependencyCoordinateResolver(algorithm, entry.getKey()),
                                        previous.sequencedKeySet());
                                group.addModule(ASSEMBLE,
                                        project.module(entry.getKey(), dependencies, arguments),
                                        Stream.concat(
                                                Stream.of(RESOLVE),
                                                previous.sequencedKeySet().stream()).collect(
                                                Collectors.toCollection(LinkedHashSet::new)));
                            }, dependencies.sequencedKeySet());
                            it.remove();
                        }
                    }
                }
            }, Stream.concat(
                    Stream.of(GROUP),
                    identified.sequencedKeySet().stream()).collect(Collectors.toCollection(LinkedHashSet::new)));
        }, IDENTIFY);
    }

    private record DependencyCoordinateResolver(String algorithm, String name) implements BuildStep {
        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedMap<String, String> coordinates = new LinkedHashMap<>(), dependencies = new LinkedHashMap<>();
            for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
                if (entry.getKey().startsWith(PREVIOUS + MODULE + "/" + name)) {
                    Path file = entry.getValue().folder().resolve(DEPENDENCIES);
                    if (Files.exists(file)) {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        properties.stringPropertyNames().forEach(property -> {
                            String value = properties.getProperty(property);
                            dependencies.put(property, value);
                        });
                    }
                } else if (entry.getKey().startsWith(PREVIOUS + MODULE + "/")) {
                    Path file = entry.getValue().folder().resolve(COORDINATES);
                    if (Files.exists(file)) {
                        Properties properties = new SequencedProperties();
                        try (Reader reader = Files.newBufferedReader(file)) {
                            properties.load(reader);
                        }
                        properties.stringPropertyNames().forEach(property -> {
                            String value = properties.getProperty(property);
                            if (!value.isEmpty()) {
                                coordinates.put(property, value);
                            }
                        });
                    }
                }
            }
            Properties properties = new SequencedProperties();
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance(algorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            for (Map.Entry<String, String> entry : dependencies.entrySet()) {
                String candidate = coordinates.get(entry.getKey());
                String value;
                if (candidate != null) {
                    if (candidate.isEmpty()) {
                        try (FileChannel channel = FileChannel.open(Path.of(candidate))) {
                            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                        }
                        value = algorithm + "/" + HexFormat.of().formatHex(digest.digest());
                        digest.reset();
                    } else {
                        value = candidate;
                    }
                } else {
                    value = entry.getValue();
                }
                properties.setProperty(entry.getKey(), value);
            }
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(DEPENDENCIES))) {
                properties.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
