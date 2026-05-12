package build.jenesis.maven;

import module java.base;
import build.jenesis.project.ModuleDescriptor;

public record MavenModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {
}
