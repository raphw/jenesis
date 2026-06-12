package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlatformTest {

    @Test
    public void normalizes_tokens_to_sorted_lower_case() {
        assertThat(Platform.tokens(" Windows , AARCH64 ")).containsExactly("aarch64", "windows");
    }

    @Test
    public void rejects_blank_token_list() {
        assertThatThrownBy(() -> Platform.tokens(" , "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No platform tokens");
    }

    @Test
    public void returns_fallback_when_no_guard_matches() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.select("key", "1.0", guarded, Platform.tokens("linux,x86_64"))).isEqualTo("1.0");
    }

    @Test
    public void returns_null_without_fallback_or_match() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.select("key", null, guarded, Platform.tokens("linux,x86_64"))).isNull();
    }

    @Test
    public void matching_guard_wins_over_fallback() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("windows", ":win:1.0"));
        assertThat(Platform.select("key", "1.0", guarded, Platform.tokens("windows,x86_64"))).isEqualTo(":win:1.0");
    }

    @Test
    public void more_specific_guard_wins() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":win:1.0");
        guarded.put("aarch64,windows", ":win-aarch64:1.0");
        assertThat(Platform.select("key", "1.0", guarded, Platform.tokens("windows,aarch64")))
                .isEqualTo(":win-aarch64:1.0");
    }

    @Test
    public void guard_matching_is_subset_based() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>(Map.of("aarch64,windows", ":win-aarch64:1.0"));
        assertThat(Platform.select("key", null, guarded, Platform.tokens("windows,x86_64"))).isNull();
    }

    @Test
    public void equally_specific_distinct_guards_are_ambiguous() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":win:1.0");
        guarded.put("x86_64", ":x64:1.0");
        assertThatThrownBy(() -> Platform.select("key", null, guarded, Platform.tokens("windows,x86_64")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous")
                .hasMessageContaining("key");
    }

    @Test
    public void equally_specific_guards_with_equal_value_are_tolerated() {
        SequencedMap<String, String> guarded = new LinkedHashMap<>();
        guarded.put("windows", ":native:1.0");
        guarded.put("x86_64", ":native:1.0");
        assertThat(Platform.select("key", null, guarded, Platform.tokens("windows,x86_64")))
                .isEqualTo(":native:1.0");
    }
}
