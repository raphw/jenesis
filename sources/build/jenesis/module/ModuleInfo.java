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
                         SequencedMap<String, String> plugins,
                         SequencedMap<String, String> versions,
                         SequencedMap<String, SequencedMap<String, String>> variants) {

    public ModuleInfo(String coordinate, SequencedSet<String> requires, SequencedSet<String> runtimeRequires) {
        this(coordinate, null, null, null, null, null, requires, runtimeRequires,
                Collections.emptyNavigableMap(), Collections.emptyNavigableMap(), Collections.emptyNavigableMap());
    }
}
