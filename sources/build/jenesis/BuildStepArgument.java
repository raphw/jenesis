package build.jenesis;

import module java.base;

public record BuildStepArgument(Path folder, Map<Path, ChecksumStatus> files, Map<Path, String> checksums) {

    private static final Path WILDCARD = Path.of(".");

    public BuildStepArgument(Path folder, Map<Path, ChecksumStatus> files) {
        this(folder, files, Map.of());
    }

    public String checksum(Path file) {
        return checksums.get(file);
    }

    public boolean hasChanged() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }

    public boolean hasChanged(Path... prefixes) {
        return hasChanged(Arrays.asList(prefixes));
    }

    public boolean hasChanged(Collection<Path> prefixes) {
        return files.entrySet().stream()
                .filter(entry -> prefixes.stream().anyMatch(prefix ->
                        WILDCARD.equals(prefix) || entry.getKey().startsWith(prefix)))
                .anyMatch(entry -> entry.getValue() != ChecksumStatus.RETAINED);
    }
}
