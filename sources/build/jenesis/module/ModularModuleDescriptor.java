package build.jenesis.module;

import module java.base;
import build.jenesis.project.ModuleDescriptor;

public record ModularModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {
}
