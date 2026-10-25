package com.nantaaditya.example.helper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.RemovalCause;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Routine removals (TTL, explicit evict, overwrite) are noise at WARN; losing an entry to the size cap is not. */
class CacheHelperRemovalLevelTest {

  @ParameterizedTest
  @EnumSource(value = RemovalCause.class, names = {"EXPIRED", "EXPLICIT", "REPLACED"})
  @DisplayName("expected removals are logged at INFO")
  void levelFor_routineCauses_info(RemovalCause cause) {
    assertThat(CacheHelper.levelFor(cause)).isEqualTo(Level.INFO);
  }

  @ParameterizedTest
  @EnumSource(value = RemovalCause.class, names = {"SIZE", "COLLECTED"})
  @DisplayName("capacity or GC pressure is logged at WARN")
  void levelFor_pressureCauses_warn(RemovalCause cause) {
    assertThat(CacheHelper.levelFor(cause)).isEqualTo(Level.WARN);
  }
}
