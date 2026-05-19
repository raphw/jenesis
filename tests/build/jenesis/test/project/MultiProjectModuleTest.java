package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.FilePlacement;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiProjectModuleTest {

    @TempDir
    private Path root, module1, module2, module3, source1, source2, source3;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop());
    }

    @Test
    public void can_resolve_project() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            SequencedProperties coordinates1 = new SequencedProperties();
            coordinates1.put("foo/bar", "");
            coordinates1.store(module1.resolve(BuildStep.IDENTITY));
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            SequencedProperties coordinates2 = new SequencedProperties();
            coordinates2.put("foo/qux", "");
            coordinates2.store(module2.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies2 = new SequencedProperties();
            dependencies2.put("foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
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
        assertThat(paths).containsKeys("project/2/step");
        assertThat(paths.get("project/2/step").resolve("file")).content().contains("foobar");
    }

    @Test
    public void can_resolve_project_transitives() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            SequencedProperties coordinates1 = new SequencedProperties();
            coordinates1.put("foo/bar", "");
            coordinates1.store(module1.resolve(BuildStep.IDENTITY));
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "foo"));
            SequencedProperties coordinates2 = new SequencedProperties();
            coordinates2.put("foo/qux", "");
            coordinates2.store(module2.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies2 = new SequencedProperties();
            dependencies2.put("foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            SequencedProperties coordinates3 = new SequencedProperties();
            coordinates3.put("foo/baz", "");
            coordinates3.store(module3.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies3 = new SequencedProperties();
            dependencies3.put("foo/qux", "");
            dependencies3.store(module3.resolve(BuildStep.REQUIRES));
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
        assertThat(paths).containsKeys("project/3/step");
        assertThat(paths.get("project/3/step").resolve("file")).content().contains("foobarqux");
    }

    @Test
    public void linkBySubModule_returns_target_for_matching_filename() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        assertThat(placement.apply(file, new SequencedProperties(), new SequencedProperties())).contains(Path.of("module-foo", "classes.jar"));
    }

    @Test
    public void linkBySubModule_returns_empty_for_unmatched_filename() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/readme.txt");
        assertThat(placement.apply(file, new SequencedProperties(), new SequencedProperties())).isEmpty();
    }

    @Test
    public void linkBySubModule_returns_empty_when_no_module_segment() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule("classes.jar");
        assertThat(placement.apply(Path.of("/wrap/some/other/place/classes.jar"),
                new SequencedProperties(),
                new SequencedProperties())).isEmpty();
    }

    @Test
    public void linkBySubModule_uses_segment_directly_under_module() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule("classes.jar");
        Path nested = Path.of("/a/build/module/outer/build/module/inner/classes.jar");
        assertThat(placement.apply(nested, new SequencedProperties(), new SequencedProperties())).contains(Path.of("inner", "classes.jar"));
    }

    @Test
    public void linkBySubModule_accepts_multiple_filenames() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule("classes.jar", "pom.xml");
        Path jar = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        Path pom = Path.of("/wrap/build/module/module-foo/build/pom/output/pom.xml");
        Path other = Path.of("/wrap/build/module/module-foo/build/java/classes/output/A.class");
        assertThat(placement.apply(jar, new SequencedProperties(), new SequencedProperties())).contains(Path.of("module-foo", "classes.jar"));
        assertThat(placement.apply(pom, new SequencedProperties(), new SequencedProperties())).contains(Path.of("module-foo", "pom.xml"));
        assertThat(placement.apply(other, new SequencedProperties(), new SequencedProperties())).isEmpty();
    }

    @Test
    public void linkBySubModule_returns_empty_when_no_filenames_configured() throws IOException {
        FilePlacement placement = MultiProjectModule.linkBySubModule();
        Path file = Path.of("/wrap/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar");
        assertThat(placement.apply(file, new SequencedProperties(), new SequencedProperties())).isEmpty();
    }

}
