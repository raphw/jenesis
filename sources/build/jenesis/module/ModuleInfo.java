package build.jenesis.module;

import module java.base;

public record ModuleInfo(String coordinate,
                         String release,
                         String name,
                         String description,
                         String testOf,
                         String main,
                         SequencedSet<String> requires,
                         SequencedSet<String> runtimeRequires,
                         SequencedSet<String> plugins,
                         SequencedMap<String, String> versions) {

    public ModuleInfo(String coordinate, SequencedSet<String> requires, SequencedSet<String> runtimeRequires) {
        this(coordinate, null, null, null, null, null, requires, runtimeRequires,
                Collections.emptyNavigableSet(), Collections.emptyNavigableMap());
    }
}
