package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.project.MultiProjectModule;

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
            try (Writer writer = Files.newBufferedWriter(module1.resolve(BuildStep.IDENTITY))) {
                coordinates1.store(writer, null);
            }
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            Properties coordinates2 = new Properties();
            coordinates2.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.IDENTITY))) {
                coordinates2.store(writer, null);
            }
            Properties dependencies2 = new Properties();
            dependencies2.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.REQUIRES))) {
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
                            "../../identifier/1-module",
                            "../../identifier/1-source");
                    assertThat(dependencies).isEmpty();
                    yield (module1, inherited) -> {
                        assertThat(inherited).containsOnlyKeys(
                                "../../../identifier/1-module",
                                "../../../identifier/1-source");
                        module1.addStep("step", (_, context, _) -> {
                            Files.writeString(context.next().resolve("file"), "foo");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    };
                }
                case "2" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identifier/2-module",
                            "../../identifier/2-source");
                    assertThat(dependencies).containsExactly(Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identifier/2-module",
                                "../../../identifier/2-source",
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
        assertThat(paths).containsKeys("project/compose/module/2/step");
        assertThat(paths.get("project/compose/module/2/step").resolve("file")).content().contains("foobar");
    }

    @Test
    public void can_resolve_project_transitives() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            Properties coordinates1 = new Properties();
            coordinates1.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module1.resolve(BuildStep.IDENTITY))) {
                coordinates1.store(writer, null);
            }
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            Properties coordinates2 = new Properties();
            coordinates2.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.IDENTITY))) {
                coordinates2.store(writer, null);
            }
            Properties dependencies2 = new Properties();
            dependencies2.put("foo/bar", "");
            try (Writer writer = Files.newBufferedWriter(module2.resolve(BuildStep.REQUIRES))) {
                dependencies2.store(writer, null);
            }
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            Properties coordinates3 = new Properties();
            coordinates3.put("foo/baz", "");
            try (Writer writer = Files.newBufferedWriter(module3.resolve(BuildStep.IDENTITY))) {
                coordinates3.store(writer, null);
            }
            Properties dependencies3 = new Properties();
            dependencies3.put("foo/qux", "");
            try (Writer writer = Files.newBufferedWriter(module3.resolve(BuildStep.REQUIRES))) {
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
                            "../../identifier/1-module",
                            "../../identifier/1-source");
                    assertThat(dependencies).isEmpty();
                    yield (module1, inherited) -> {
                        assertThat(inherited).containsOnlyKeys(
                                "../../../identifier/1-module",
                                "../../../identifier/1-source");
                        module1.addStep("step", (_, context, _) -> {
                            Files.writeString(context.next().resolve("file"), "foo");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    };
                }
                case "2" -> {
                    assertThat(identifiers).containsOnlyKeys(
                            "../../identifier/2-module",
                            "../../identifier/2-source");
                    assertThat(dependencies).containsExactly(Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identifier/2-module",
                                "../../../identifier/2-source",
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
                            "../../identifier/3-module",
                            "../../identifier/3-source");
                    assertThat(dependencies).containsExactly(
                            Map.entry("2", new LinkedHashSet<>(Set.of("1"))),
                            Map.entry("1", new LinkedHashSet<>()));
                    yield (module2, inherited) -> {
                        assertThat(inherited).containsKeys(
                                "../../../identifier/3-module",
                                "../../../identifier/3-source",
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
        assertThat(paths).containsKeys("project/compose/module/3/step");
        assertThat(paths.get("project/compose/module/3/step").resolve("file")).content().contains("foobarqux");
    }

    @Test
    public void linkBySubModule_returns_target_for_matching_filename() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        assertThat(placement.apply(file)).contains(Path.of("module-foo", "classes.jar"));
    }

    @Test
    public void linkBySubModule_returns_empty_for_unmatched_filename() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/readme.txt");
        assertThat(placement.apply(file)).isEmpty();
    }

    @Test
    public void linkBySubModule_returns_empty_when_no_module_segment() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule("classes.jar");
        assertThat(placement.apply(Path.of("/wrap/some/other/place/classes.jar"))).isEmpty();
    }

    @Test
    public void linkBySubModule_uses_segment_directly_under_module() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path nested = Path.of("/a/build/module/outer/build/module/inner/classes.jar");
        assertThat(placement.apply(nested)).contains(Path.of("inner", "classes.jar"));
    }

    @Test
    public void linkBySubModule_accepts_multiple_filenames() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule("classes.jar", "pom.xml");
        Path jar = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        Path pom = Path.of("/wrap/build/module/module-foo/build/pom/output/pom.xml");
        Path other = Path.of("/wrap/build/module/module-foo/build/java/classes/output/A.class");
        assertThat(placement.apply(jar)).contains(Path.of("module-foo", "classes.jar"));
        assertThat(placement.apply(pom)).contains(Path.of("module-foo", "pom.xml"));
        assertThat(placement.apply(other)).isEmpty();
    }

    @Test
    public void linkBySubModule_returns_empty_when_no_filenames_configured() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.linkBySubModule();
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        assertThat(placement.apply(file)).isEmpty();
    }

    @Test
    public void artifactsByModule_links_classes_sources_javadoc_and_pom_under_sub_module_folder() {
        Function<Path, Optional<Path>> placement = MultiProjectModule.artifactsByModule();
        Path classes = Path.of("/wrap/build/module/module-foo/produce/java/artifacts/output/artifacts/classes.jar");
        Path sources = Path.of("/wrap/build/module/module-foo/produce/sources/output/artifacts/sources.jar");
        Path javadoc = Path.of("/wrap/build/module/module-foo/produce/javadoc/artifacts/output/artifacts/javadoc.jar");
        Path pom = Path.of("/wrap/build/module/module-foo/build/pom/output/pom.xml");
        Path other = Path.of("/wrap/build/module/module-foo/build/java/classes/output/A.class");
        assertThat(placement.apply(classes)).contains(Path.of("module-foo", "classes.jar"));
        assertThat(placement.apply(sources)).contains(Path.of("module-foo", "sources.jar"));
        assertThat(placement.apply(javadoc)).contains(Path.of("module-foo", "javadoc.jar"));
        assertThat(placement.apply(pom)).contains(Path.of("module-foo", "pom.xml"));
        assertThat(placement.apply(other)).isEmpty();
    }
}
