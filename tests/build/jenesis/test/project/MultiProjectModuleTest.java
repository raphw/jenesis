package build.jenesis.test.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.project.MultiProjectModule;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiProjectModuleTest {

    @TempDir
    private Path root, module1, module2, module3, source1, source2, source3;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
    }

    @Test
    public void can_resolve_project() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            Properties coordinates1 = new Properties();
            coordinates1.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module1.resolve(BuildStep.COORDINATES))) {
                coordinates1.store(writer, null);
            }
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            Properties coordinates2 = new Properties();
            coordinates2.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.COORDINATES))) {
                coordinates2.store(writer, null);
            }
            Properties dependencies2 = new Properties();
            dependencies2.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.DEPENDENCIES))) {
                dependencies2.store(writer, null);
            }
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), modules -> {
            assertThat(modules).containsExactly(
                    Map.entry("1", new LinkedHashSet<>()),
                    Map.entry("2", new LinkedHashSet<>(Set.of("1"))));
            return (name, dependencies, identifiers) -> switch (name) {
                case "1" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identify/1-module",
                            "../../identify/1-source");
                    assertThat(dependencies).isEmpty();
                    yield (module1, inherited) -> {
                        assertThat(inherited).containsOnlyKeys(
                                "../../../identify/1-module",
                                "../../../identify/1-source");
                        module1.addStep("step", (_, context, _) -> {
                            Files.writeString(context.next().resolve("file"), "foo");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    };
                }
                case "2" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identify/2-module",
                            "../../identify/2-source");
                    assertThat(dependencies).containsExactly(Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identify/2-module",
                                "../../../identify/2-source",
                                "../1/step");
                        module2.addStep("step", (_, context, arguments) -> {
                            Files.writeString(
                                    context.next().resolve("file"),
                                    Files.readString(arguments.get("../1/step").folder().resolve("file")) + "bar");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        }, "../1/step");
                    };
                }
                default -> throw new AssertionError();
            };
        }));
        SequencedMap<String, Path> paths = buildExecutor.execute();
        assertThat(paths).containsKeys("project/build/module/2/step");
        assertThat(paths.get("project/build/module/2/step").resolve("file")).content().contains("foobar");
    }

    @Test
    public void can_resolve_project_transitives() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            Properties coordinates1 = new Properties();
            coordinates1.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module1.resolve(BuildStep.COORDINATES))) {
                coordinates1.store(writer, null);
            }
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            Properties coordinates2 = new Properties();
            coordinates2.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.COORDINATES))) {
                coordinates2.store(writer, null);
            }
            Properties dependencies2 = new Properties();
            dependencies2.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.DEPENDENCIES))) {
                dependencies2.store(writer, null);
            }
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            Properties coordinates3 = new Properties();
            coordinates3.put("foo/baz", "");
            try (Writer writer = Files.newBufferedWriter(module3.resolve(BuildStep.COORDINATES))) {
                coordinates3.store(writer, null);
            }
            Properties dependencies3 = new Properties();
            dependencies3.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module3.resolve(BuildStep.DEPENDENCIES))) {
                dependencies3.store(writer, null);
            }
            buildExecutor.addSource("3-module", module3);
            buildExecutor.addSource("3-source", Files.writeString(Files.createDirectory(source3
                    .resolve(BuildStep.SOURCES)).resolve("source"), "qux"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), modules -> {
            assertThat(modules).containsExactly(
                    Map.entry("1", new LinkedHashSet<>()),
                    Map.entry("2", new LinkedHashSet<>(Set.of("1"))),
                    Map.entry("3", new LinkedHashSet<>(Set.of("2"))));
            return (name, dependencies, identifiers) -> switch (name) {
                case "1" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identify/1-module",
                            "../../identify/1-source");
                    assertThat(dependencies).isEmpty();
                    yield (module1, inherited) -> {
                        assertThat(inherited).containsOnlyKeys(
                                "../../../identify/1-module",
                                "../../../identify/1-source");
                        module1.addStep("step", (_, context, _) -> {
                            Files.writeString(context.next().resolve("file"), "foo");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    };
                }
                case "2" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identify/2-module",
                            "../../identify/2-source");
                    assertThat(dependencies).containsExactly(Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identify/2-module",
                                "../../../identify/2-source",
                                "../1/step");
                        module2.addStep("step", (_, context, arguments) -> {
                            Files.writeString(
                                    context.next().resolve("file"),
                                    Files.readString(arguments.get("../1/step").folder().resolve("file")) + "bar");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        }, "../1/step");
                    };
                }
                case "3" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identify/3-module",
                            "../../identify/3-source");
                    assertThat(dependencies).containsExactly(
                            Map.entry("2", new LinkedHashSet<>(Set.of("1"))),
                            Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identify/3-module",
                                "../../../identify/3-source",
                                "../1/step",
                                "../2/step");
                        module2.addStep("step", (_, context, arguments) -> {
                            Files.writeString(
                                    context.next().resolve("file"),
                                    Files.readString(arguments.get("../2/step").folder().resolve("file")) + "qux");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        }, "../2/step");
                    };
                }
                default -> throw new AssertionError();
            };
        }));
        SequencedMap<String, Path> paths = buildExecutor.execute();
        assertThat(paths).containsKeys("project/build/module/3/step");
        assertThat(paths.get("project/build/module/3/step").resolve("file")).content().contains("foobarqux");
    }
}
