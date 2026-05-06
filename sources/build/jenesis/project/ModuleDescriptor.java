package build.jenesis.project;

import module java.base;

public interface ModuleDescriptor {

    String name();

    SequencedSet<String> dependencies();
}
