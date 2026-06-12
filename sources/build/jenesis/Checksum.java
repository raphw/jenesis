package build.jenesis;

import module java.base;

public final class Checksum {

    public static final Checksum ADDED = new Checksum(ChecksumStatus.ADDED, (Supplier<String>) null);
    public static final Checksum REMOVED = new Checksum(ChecksumStatus.REMOVED, (Supplier<String>) null);
    public static final Checksum ALTERED = new Checksum(ChecksumStatus.ALTERED, (Supplier<String>) null);
    public static final Checksum RETAINED = new Checksum(ChecksumStatus.RETAINED, (Supplier<String>) null);

    private final ChecksumStatus status;
    private final Supplier<String> value;

    private Checksum(ChecksumStatus status, Supplier<String> value) {
        this.status = status;
        this.value = value;
    }

    public static Checksum of(ChecksumStatus status, String encoded) {
        return new Checksum(status, encoded == null ? null : () -> encoded);
    }

    public ChecksumStatus status() {
        return status;
    }

    public String encoded() {
        return value == null ? null : value.get();
    }

    public static Map<Path, Checksum> diff(Map<Path, byte[]> expected, Map<Path, byte[]> actual, HashFunction hash) {
        Map<Path, Checksum> diff = new LinkedHashMap<>();
        Map<Path, byte[]> removed = new LinkedHashMap<>(expected);
        for (Map.Entry<Path, byte[]> entry : actual.entrySet()) {
            byte[] other = removed.remove(entry.getKey());
            ChecksumStatus status;
            if (other == null) {
                status = ChecksumStatus.ADDED;
            } else if (Arrays.equals(other, entry.getValue())) {
                status = ChecksumStatus.RETAINED;
            } else {
                status = ChecksumStatus.ALTERED;
            }
            byte[] bytes = entry.getValue();
            diff.put(entry.getKey(), new Checksum(status, () -> hash.encoded(bytes)));
        }
        for (Path path : removed.keySet()) {
            diff.put(path, REMOVED);
        }
        return diff;
    }

    public static Map<Path, Checksum> added(Map<Path, byte[]> actual, HashFunction hash) {
        Map<Path, Checksum> added = new LinkedHashMap<>();
        actual.forEach((path, bytes) -> added.put(path, new Checksum(ChecksumStatus.ADDED, () -> hash.encoded(bytes))));
        return added;
    }
}
