package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a sensitive field looks like in the HTTP log. The keep-count rule of {@code masking(String)}: with
 * {@code half = length / 2} it keeps {@code (length - half) / 2} characters at each end, so roughly the outer
 * quarter on both sides stays readable and the middle half is starred.
 */
class MaskingHelperTest {

  private static final Gson GSON = new Gson();
  private static final Set<String> KEYS = Set.of("password");

  @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
  @CsvSource({
      "abc,a*c",
      "abcd,a**d",
      "abcdefghij,ab******ij",
      "ab,ab",
      "a,a"
  })
  @DisplayName("default masking keeps the outer characters and stars the middle; values of 1-2 characters are untouched")
  void masking_default_keepsOuterCharacters(String value, String expected) {
    assertThat(MaskingHelper.masking(value)).isEqualTo(expected);
  }

  @Test
  @DisplayName("blank and null values are returned as they are")
  void masking_blankOrNull_unchanged() {
    assertThat(MaskingHelper.masking(null)).isNull();
    assertThat(MaskingHelper.masking("")).isEmpty();
    assertThat(MaskingHelper.masking("   ")).isEqualTo("   ");
  }

  @Test
  @DisplayName("explicit counts keep that many characters at each end")
  void masking_explicitCounts() {
    assertThat(MaskingHelper.masking("secret", 2, 2)).isEqualTo("se**et");
    assertThat(MaskingHelper.masking("secret", 1, 0)).isEqualTo("s*****");
  }

  @Test
  @DisplayName("a value no longer than the kept characters is not masked at all")
  void masking_valueNotLongerThanKept_unchanged() {
    assertThat(MaskingHelper.masking("abcd", 2, 2)).isEqualTo("abcd");
    assertThat(MaskingHelper.masking("abc", 2, 2)).isEqualTo("abc");
  }

  @Test
  @DisplayName("a sensitive key in a JSON object is masked and the other fields are left alone")
  void maskingJson_object_masksOnlySensitiveKey() {
    String result = MaskingHelper.maskingJson(GSON, KEYS, "{\"user\":\"bob\",\"password\":\"hunter2abc\"}");

    assertThat(result).isEqualTo("{\"user\":\"bob\",\"password\":\"hu******bc\"}");
  }

  @Test
  @DisplayName("the key is found at any depth, inside nested objects and arrays")
  void maskingJson_nested_masksEveryOccurrence() {
    String payload = "{\"a\":{\"password\":\"hunter2abc\"},\"b\":[{\"password\":\"hunter2abc\"}]}";

    String result = MaskingHelper.maskingJson(GSON, KEYS, payload);

    assertThat(result).isEqualTo("{\"a\":{\"password\":\"hu******bc\"},\"b\":[{\"password\":\"hu******bc\"}]}");
  }

  @Test
  @DisplayName("a top-level JSON array of objects is masked element by element")
  void maskingJson_topLevelArray_masked() {
    String result = MaskingHelper.maskingJson(GSON, KEYS, "[{\"password\":\"hunter2abc\"},{\"x\":1}]");

    assertThat(result).isEqualTo("[{\"password\":\"hu******bc\"},{\"x\":1}]");
  }

  @Test
  @DisplayName("a numeric value of a sensitive key is masked as text")
  void maskingJson_numericValue_maskedAsText() {
    String result = MaskingHelper.maskingJson(GSON, Set.of("pin"), "{\"pin\":123456}");

    assertThat(result).isEqualTo("{\"pin\":\"1****6\"}");
  }

  @Test
  @DisplayName("several sensitive keys are all masked")
  void maskingJson_severalKeys_allMasked() {
    String result = MaskingHelper.maskingJson(GSON, Set.of("password", "token"),
        "{\"password\":\"hunter2abc\",\"token\":\"tok-1234567\"}");

    assertThat(result).contains("\"password\":\"hu******bc\"").contains("\"token\":\"tok*****567\"");
    assertThat(result).doesNotContain("hunter2abc").doesNotContain("tok-1234567");
  }

  @Test
  @DisplayName("a blank or null payload is returned unchanged")
  void maskingJson_blankOrNull_unchanged() {
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, null)).isNull();
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, " ")).isEqualTo(" ");
  }

  @Test
  @DisplayName("broken JSON is reported as 'not a json' instead of being logged raw")
  void maskingJson_invalidJson_notAJson() {
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, "{\"password\":")).isEqualTo("not a json");
  }

  @Test
  @DisplayName("a payload the masker cannot walk (array of primitives, JSON null) is replaced, never logged raw")
  void maskingJson_unwalkablePayload_notAJson() {
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, "[1,2,3]")).isEqualTo("not a json");
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, "{\"password\":null}")).isEqualTo("not a json");
  }

  @Test
  @DisplayName("a payload that is neither an object nor an array yields null (current behaviour: it is dropped)")
  void maskingJson_plainText_returnsNull() {
    assertThat(MaskingHelper.maskingJson(GSON, KEYS, "plain text body")).isNull();
  }
}
