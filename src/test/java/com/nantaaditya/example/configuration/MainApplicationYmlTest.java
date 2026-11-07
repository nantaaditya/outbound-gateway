package com.nantaaditya.example.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.properties.SchedulerProperties;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The integration tests run with {@code src/test/resources/application.yml}, which shadows the real
 * {@code src/main/resources/application.yml} on the test classpath, so nothing else checks the shipped configuration.
 * This loads the shipped file by path (with its defaults, no environment variables set) and checks it.
 */
class MainApplicationYmlTest {

  private static final String MAIN_APPLICATION_YML = "src/main/resources/application.yml";

  @Test
  @DisplayName("every scheduler configured in the shipped application.yml is one the code asks for by name")
  void schedulers_areOnlyTheOnesTheCodeUses() throws IOException {
    SchedulerProperties schedulers = bind("apps.scheduler", SchedulerProperties.class);

    // "default-async": SchedulerHelper.from("default-async") in the purge/background services
    // "retry-listener": RetryEventListener
    assertThat(schedulers.configurations()).containsOnlyKeys("default-async", "retry-listener");
  }

  @Test
  @DisplayName("the shipped schedulers bind with the names and sizes written in application.yml")
  void schedulers_bindWithTheirSettings() throws IOException {
    SchedulerProperties schedulers = bind("apps.scheduler", SchedulerProperties.class);

    var defaultAsync = schedulers.configurations().get("default-async").getBoundedElastic();
    assertThat(defaultAsync.name()).isEqualTo("default-async");
    assertThat(defaultAsync.corePoolSize()).isEqualTo(5);
    assertThat(defaultAsync.queueSize()).isEqualTo(10);
    assertThat(defaultAsync.ttlInSeconds()).isEqualTo(60);
  }

  private static <T> T bind(String prefix, Class<T> type) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    for (PropertySource<?> source : new YamlPropertySourceLoader()
        .load("main-application-yml", new FileSystemResource(MAIN_APPLICATION_YML))) {
      environment.getPropertySources().addFirst(source);
    }
    return Binder.get(environment).bind(prefix, type).get();
  }
}
