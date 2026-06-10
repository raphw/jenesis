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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MultiProjectModuleTest {

    @TempDir
    private Path root,
            module1, module2, module3, module4, module5,
            source1, source2, source3, source4, source5;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
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
            dependencies2.put("main/compile/foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), modules -> {
            assertThat(modules).containsExactly(
                    Map.entry("1", Collections.emptyNavigableSet()),
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
                    assertThat(dependencies).containsExactly(Map.entry("1", Collections.emptyNavigableSet()));
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
            dependencies2.put("main/compile/foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            SequencedProperties coordinates3 = new SequencedProperties();
            coordinates3.put("foo/baz", "");
            coordinates3.store(module3.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies3 = new SequencedProperties();
            dependencies3.put("main/compile/foo/qux", "");
            dependencies3.store(module3.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("3-module", module3);
            buildExecutor.addSource("3-source", Files.writeString(Files.createDirectory(source3
                    .resolve(BuildStep.SOURCES)).resolve("source"), "qux"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), modules -> {
            assertThat(modules).containsExactly(
                    Map.entry("1", Collections.emptyNavigableSet()),
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
                    assertThat(dependencies).containsExactly(Map.entry("1", Collections.emptyNavigableSet()));
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
                            Map.entry("1", Collections.emptyNavigableSet()));
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
    public void direct_transitive_sibling_is_visible_in_inherited() {
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
            dependencies2.put("main/compile/foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            SequencedProperties coordinates3 = new SequencedProperties();
            coordinates3.put("foo/baz", "");
            coordinates3.store(module3.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies3 = new SequencedProperties();
            dependencies3.put("main/compile/foo/qux", "");
            dependencies3.store(module3.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("3-module", module3);
            buildExecutor.addSource("3-source", Files.writeString(Files.createDirectory(source3
                    .resolve(BuildStep.SOURCES)).resolve("source"), "qux"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), _ -> (name, _, _) -> switch (name) {
            case "1" -> (module, _) -> module.addStep("step", (_, context, _) -> {
                Files.writeString(context.next().resolve("file"), "1");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            });
            case "2" -> (module, _) -> module.addStep("step", (_, context, _) -> {
                Files.writeString(context.next().resolve("file"), "2");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            });
            case "3" -> (module, inherited) -> {
                assertThat(inherited).containsKeys("../1/step", "../2/step");
                module.addStep("step", (_, context, _) -> {
                    Files.writeString(context.next().resolve("file"), "3");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                });
            };
            default -> throw new AssertionError("Unexpected module: " + name);
        }));
        buildExecutor.execute();
    }

    @Test
    public void indirect_transitive_sibling_is_visible_in_inherited() {
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
            dependencies2.put("main/compile/foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "bar"));
            SequencedProperties coordinates3 = new SequencedProperties();
            coordinates3.put("foo/baz", "");
            coordinates3.store(module3.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies3 = new SequencedProperties();
            dependencies3.put("main/compile/foo/qux", "");
            dependencies3.store(module3.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("3-module", module3);
            buildExecutor.addSource("3-source", Files.writeString(Files.createDirectory(source3
                    .resolve(BuildStep.SOURCES)).resolve("source"), "qux"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')), _ -> (name, _, _) -> switch (name) {
            case "1" -> (module, _) -> module.addStep("step", (_, context, _) -> {
                Files.writeString(context.next().resolve("file"), "1");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            });
            case "2" -> (module, inherited) -> {
                assertThat(inherited).containsKey("../1/step");
                module.addStep("step", (_, context, _) -> {
                    Files.writeString(context.next().resolve("file"), "2");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                });
            };
            case "3" -> (module, inherited) -> {
                assertThat(inherited).containsKeys("../1/step", "../2/step");
                module.addStep("step", (_, context, _) -> {
                    Files.writeString(context.next().resolve("file"), "3");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                });
            };
            default -> throw new AssertionError("Unexpected module: " + name);
        }));
        buildExecutor.execute();
    }

    @Test
    public void selector_builds_transitive_dependencies_but_not_dependents() {
        Set<String> built = ConcurrentHashMap.newKeySet();
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            SequencedProperties coordinates1 = new SequencedProperties();
            coordinates1.put("foo/a", "");
            coordinates1.store(module1.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies1 = new SequencedProperties();
            dependencies1.put("main/compile/foo/b", "");
            dependencies1.store(module1.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("1-module", module1);
            buildExecutor.addSource("1-source", Files.writeString(Files.createDirectory(source1
                    .resolve(BuildStep.SOURCES)).resolve("source"), "a"));
            SequencedProperties coordinates2 = new SequencedProperties();
            coordinates2.put("foo/b", "");
            coordinates2.store(module2.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies2 = new SequencedProperties();
            dependencies2.put("main/compile/foo/c", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
            buildExecutor.addSource("2-source", Files.writeString(Files.createDirectory(source2
                    .resolve(BuildStep.SOURCES)).resolve("source"), "b"));
            SequencedProperties coordinates3 = new SequencedProperties();
            coordinates3.put("foo/c", "");
            coordinates3.store(module3.resolve(BuildStep.IDENTITY));
            buildExecutor.addSource("3-module", module3);
            buildExecutor.addSource("3-source", Files.writeString(Files.createDirectory(source3
                    .resolve(BuildStep.SOURCES)).resolve("source"), "c"));
            SequencedProperties coordinates4 = new SequencedProperties();
            coordinates4.put("foo/d", "");
            coordinates4.store(module4.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies4 = new SequencedProperties();
            dependencies4.put("main/compile/foo/e", "");
            dependencies4.store(module4.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("4-module", module4);
            buildExecutor.addSource("4-source", Files.writeString(Files.createDirectory(source4
                    .resolve(BuildStep.SOURCES)).resolve("source"), "d"));
            SequencedProperties coordinates5 = new SequencedProperties();
            coordinates5.put("foo/e", "");
            coordinates5.store(module5.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies5 = new SequencedProperties();
            dependencies5.put("main/compile/foo/c", "");
            dependencies5.store(module5.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("5-module", module5);
            buildExecutor.addSource("5-source", Files.writeString(Files.createDirectory(source5
                    .resolve(BuildStep.SOURCES)).resolve("source"), "e"));
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')),
                _ -> (name, _, _) -> (module, _) -> {
            built.add(name);
            module.addStep("step", (_, context, _) -> {
                Files.writeString(context.next().resolve("file"), name);
                return CompletableFuture.completedStage(new BuildStepResult(true));
            });
        }));
        buildExecutor.execute("project/compose/module/1", "project/compose/module/5");
        assertThat(built).contains("1", "2", "3", "5").doesNotContain("4");
    }

    @Test
    public void rejects_cyclic_modules() {
        buildExecutor.addModule("project", new MultiProjectModule((buildExecutor, _) -> {
            SequencedProperties coordinates1 = new SequencedProperties();
            coordinates1.put("foo/bar", "");
            coordinates1.store(module1.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies1 = new SequencedProperties();
            dependencies1.put("main/compile/foo/qux", "");
            dependencies1.store(module1.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("1-module", module1);
            SequencedProperties coordinates2 = new SequencedProperties();
            coordinates2.put("foo/qux", "");
            coordinates2.store(module2.resolve(BuildStep.IDENTITY));
            SequencedProperties dependencies2 = new SequencedProperties();
            dependencies2.put("main/compile/foo/bar", "");
            dependencies2.store(module2.resolve(BuildStep.REQUIRES));
            buildExecutor.addSource("2-module", module2);
        }, identifier -> Optional.of(identifier.substring(0, identifier.indexOf('-')).replace('-', '/')),
                _ -> (name, _, _) -> (module, _) -> {
            throw new AssertionError("Unexpected module: " + name);
        }));
        assertThatThrownBy(buildExecutor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Cyclic module dependencies")
                .hasMessageContaining("1")
                .hasMessageContaining("2");
    }

}
