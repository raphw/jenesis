package build.buildbuddy;

import module java.base;

public enum ChecksumStatus {

    ADDED, REMOVED, ALTERED, RETAINED;

    public static Map<Path, ChecksumStatus> diff(Map<Path, byte[]> expected, Map<Path, byte[]> actual) {
        Map<Path, ChecksumStatus> diff = new LinkedHashMap<>();
        Map<Path, byte[]> removed = new LinkedHashMap<>(expected);
        for (Map.Entry<Path, byte[]> entry : actual.entrySet()) {
            byte[] other = removed.remove(entry.getKey());
            if (other == null) {
                diff.put(entry.getKey(), ADDED);
            } else if (Arrays.equals(other, entry.getValue())) {
                diff.put(entry.getKey(), RETAINED);
            } else {
                diff.put(entry.getKey(), ALTERED);
            }
        }
        for (Path path : removed.keySet()) {
            diff.put(path, REMOVED);
        }
        return diff;
    }

    public static Map<Path, ChecksumStatus> added(Set<Path> paths) {
        Map<Path, ChecksumStatus> diff = new LinkedHashMap<>();
        paths.forEach(path -> diff.put(path, ADDED));
        return diff;
    }
}
