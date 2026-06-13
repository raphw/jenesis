package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlatformTest {

    @Test
    public void normalizes_tokens_to_sorted_lower_case() {
        assertThat(Platform.of(" Windows , AARCH64 ").tokens()).containsExactly("aarch64", "windows");
    }

    @Test
    public void canonical_renders_sorted_comma_form() {
        assertThat(Platform.of("windows, aarch64").canonical()).isEqualTo("aarch64,windows");
    }

    @Test
    public void rejects_blank_token_list() {
        assertThatThrownBy(() -> Platform.of(" , "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No platform tokens");
    }

    @Test
    public void matches_is_subset_based() {
        Platform active = Platform.of("windows,x86_64");
        assertThat(active.matches(Platform.of("windows"))).isTrue();
        assertThat(active.matches(Platform.of("windows,x86_64"))).isTrue();
        assertThat(active.matches(Platform.of("windows,aarch64"))).isFalse();
    }

    @Test
    public void select_returns_fallback_when_no_guard_matches() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.of("linux,x86_64").select("key", "1.0", guarded)).isEqualTo("1.0");
    }

    @Test
    public void select_returns_null_without_fallback_or_match() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.of("linux,x86_64").select("key", null, guarded)).isNull();
    }

    @Test
    public void select_prefers_matching_guard_over_fallback() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.of("windows,x86_64").select("key", "1.0", guarded)).isEqualTo(":win:1.0");
    }

    @Test
    public void select_prefers_more_specific_guard() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":win:1.0");
        guarded.put("aarch64,windows", ":win-aarch64:1.0");
        assertThat(Platform.of("windows,aarch64").select("key", "1.0", guarded)).isEqualTo(":win-aarch64:1.0");
    }

    @Test
    public void select_rejects_equally_specific_distinct_guards() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":win:1.0");
        guarded.put("x86_64", ":x64:1.0");
        assertThatThrownBy(() -> Platform.of("windows,x86_64").select("key", null, guarded))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous")
                .hasMessageContaining("key");
    }

    @Test
    public void select_tolerates_equally_specific_guards_with_equal_value() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":native:1.0");
        guarded.put("x86_64", ":native:1.0");
        assertThat(Platform.of("windows,x86_64").select("key", null, guarded)).isEqualTo(":native:1.0");
    }

    @Test
    public void equal_token_sets_are_equal_regardless_of_input_order() {
        assertThat(Platform.of("windows,x86_64")).isEqualTo(Platform.of("x86_64, WINDOWS"));
    }

    @Test
    public void detected_platform_has_an_operating_system_and_chipset() {
        assertThat(new Platform().tokens()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    public void declared_flag_adds_a_token_on_top_of_the_detected_ones() {
        Platform detected = new Platform();
        System.setProperty("jenesis.platform.fips", "true");
        try {
            Platform extended = new Platform();
            assertThat(extended.tokens()).contains("fips");
            assertThat(extended.tokens()).containsAll(detected.tokens());
            assertThat(detected.tokens()).doesNotContain("fips");
        } finally {
            System.clearProperty("jenesis.platform.fips");
        }
    }

    @Test
    public void declared_flag_must_be_true_to_add() {
        System.setProperty("jenesis.platform.fips", "yes");
        try {
            assertThat(new Platform().tokens()).doesNotContain("fips");
        } finally {
            System.clearProperty("jenesis.platform.fips");
        }
    }

    @Test
    public void declared_false_flag_removes_a_detected_token() {
        Platform detected = new Platform();
        assertThat(detected.tokens()).hasSizeGreaterThanOrEqualTo(2);
        String removed = detected.tokens().getFirst();
        System.setProperty("jenesis.platform." + removed, "false");
        try {
            SequencedSet<String> expected = new TreeSet<>(detected.tokens());
            expected.remove(removed);
            assertThat(new Platform().tokens()).containsExactlyElementsOf(expected);
        } finally {
            System.clearProperty("jenesis.platform." + removed);
        }
    }
}
