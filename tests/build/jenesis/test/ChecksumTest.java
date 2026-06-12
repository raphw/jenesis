package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumTest {

    private static final HashDigestFunction HASH = new HashDigestFunction("MD5");

    @Test
    public void can_find_added() {
        Map<Path, Checksum> status = Checksum.diff(
                Map.of(),
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                HASH);
        assertThat(status.get(Path.of("foo")).status()).isEqualTo(ChecksumStatus.ADDED);
    }

    @Test
    public void can_find_removed() {
        Map<Path, Checksum> status = Checksum.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of(),
                HASH);
        assertThat(status.get(Path.of("foo")).status()).isEqualTo(ChecksumStatus.REMOVED);
    }

    @Test
    public void can_find_retained() {
        Map<Path, Checksum> status = Checksum.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                HASH);
        assertThat(status.get(Path.of("foo")).status()).isEqualTo(ChecksumStatus.RETAINED);
    }

    @Test
    public void can_find_altered() {
        Map<Path, Checksum> status = Checksum.diff(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                Map.of(Path.of("foo"), new byte[]{4, 5, 6}),
                HASH);
        assertThat(status.get(Path.of("foo")).status()).isEqualTo(ChecksumStatus.ALTERED);
    }

    @Test
    public void lazily_encodes_the_checksum() {
        Map<Path, Checksum> status = Checksum.added(
                Map.of(Path.of("foo"), new byte[]{1, 2, 3}),
                HASH);
        assertThat(status.get(Path.of("foo")).encoded()).isEqualTo(HASH.encoded(new byte[]{1, 2, 3}));
    }
}
