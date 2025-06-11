package build.buildbuddy.test;

import build.buildbuddy.ChecksumStatus;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumStatusTest {

    @Test
    public void can_find_added() {
        Map<Path, ChecksumStatus> status = ChecksumStatus.diff(
                Map.of(),
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}));
        assertThat(status).containsOnly(Map.entry(Path.of("foo"), ChecksumStatus.ADDED));
    }

    @Test
    public void can_find_removed() {
        Map<Path, ChecksumStatus> status = ChecksumStatus.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of());
        assertThat(status).containsOnly(Map.entry(Path.of("foo"), ChecksumStatus.REMOVED));
    }

    @Test
    public void can_find_retained() {
        Map<Path, ChecksumStatus> status = ChecksumStatus.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}));
        assertThat(status).containsOnly(Map.entry(Path.of("foo"), ChecksumStatus.RETAINED));
    }

    @Test
    public void can_find_altered() {
        Map<Path, ChecksumStatus> status = ChecksumStatus.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of(Path.of("foo"), new byte[]{4, 5, 6}));
        assertThat(status).containsOnly(Map.entry(Path.of("foo"), ChecksumStatus.ALTERED));
    }
}
