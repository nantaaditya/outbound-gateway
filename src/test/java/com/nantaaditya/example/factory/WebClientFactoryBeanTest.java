package com.nantaaditya.example.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nantaaditya.example.factory.impl.WebClientFactoryBean;
import com.nantaaditya.example.properties.ClientProperties;
import com.nantaaditya.example.properties.embedded.ClientConfiguration;
import com.nantaaditya.example.properties.embedded.ClientTimeOutConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.zalando.logbook.Logbook;

class WebClientFactoryBeanTest {

  private Logbook logbook;

  @BeforeEach
  void setUp() {
    logbook = Logbook.builder().build();
  }

  @Test
  @DisplayName("getObjectType returns WebClientFactory class")
  void getObjectType_returnsWebClientFactoryClass() {
    var bean = new WebClientFactoryBean(new ClientProperties(null), logbook);

    assertThat(bean.getObjectType()).isEqualTo(WebClientFactory.class);
  }

  @Test
  @DisplayName("getObject returns empty factory when configurations is null")
  void getObject_nullConfigurations_returnsEmptyFactory() throws Exception {
    var bean = new WebClientFactoryBean(new ClientProperties(null), logbook);

    WebClientFactory factory = bean.getObject();

    assertThat(factory).isNotNull();
    assertThat(factory.of("any")).isNull();
  }

  @Test
  @DisplayName("getObject creates a WebClient for each configured client")
  void getObject_withConfigurations_createsWebClientPerClient() throws Exception {
    ClientConfiguration cfg = new ClientConfiguration(
        "https://api.example.com",
        Map.of("Accept", List.of("application/json")),
        new ClientTimeOutConfiguration(5000, 5000, 30000, 30000),
        null,
        false,
        false
    );
    var bean = new WebClientFactoryBean(new ClientProperties(Map.of("example", cfg)), logbook);

    WebClientFactory factory = bean.getObject();

    assertThat(factory).isNotNull();
    assertThat(factory.of("example")).isNotNull();
  }

  @Test
  @DisplayName("getObject with SSL verification disabled creates WebClient without throwing")
  void getObject_sslDisabled_createsWebClient() throws Exception {
    ClientConfiguration cfg = new ClientConfiguration(
        "https://internal.example.com",
        Map.of(),
        new ClientTimeOutConfiguration(3000, 3000, 10000, 10000),
        null,
        false,
        true
    );
    var bean = new WebClientFactoryBean(new ClientProperties(Map.of("internal", cfg)), logbook);

    WebClientFactory factory = bean.getObject();

    assertThat(factory.of("internal")).isNotNull();
  }

  @Test
  @DisplayName("getObject returns null for an unknown client name")
  void getObject_unknownClientName_returnsNull() throws Exception {
    ClientConfiguration cfg = new ClientConfiguration(
        "https://api.example.com",
        Map.of(),
        new ClientTimeOutConfiguration(5000, 5000, 30000, 30000),
        null,
        false,
        false
    );
    var bean = new WebClientFactoryBean(new ClientProperties(Map.of("known", cfg)), logbook);

    WebClientFactory factory = bean.getObject();

    assertThat(factory.of("unknown")).isNull();
  }
}
