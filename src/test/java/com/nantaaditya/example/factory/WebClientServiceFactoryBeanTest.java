package com.nantaaditya.example.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nantaaditya.example.factory.impl.WebClientServiceFactoryBean;
import com.nantaaditya.example.properties.ClientProperties;
import com.nantaaditya.example.properties.embedded.ClientConfiguration;
import com.nantaaditya.example.properties.embedded.ClientTimeOutConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebClientServiceFactoryBeanTest {

  private WebClientFactory webClientFactory;

  @BeforeEach
  void setUp() {
    webClientFactory = mock(WebClientFactory.class);
  }

  @Test
  @DisplayName("getObjectType returns WebClientServiceFactory class")
  void getObjectType_returnsWebClientServiceFactoryClass() {
    var bean = factoryBean(new ClientProperties(null));

    assertThat(bean.getObjectType()).isEqualTo(WebClientServiceFactory.class);
  }

  @Test
  @DisplayName("getObject returns empty factory when configurations is null")
  void getObject_nullConfigurations_returnsEmptyFactory() throws Exception {
    var bean = factoryBean(new ClientProperties(null));

    WebClientServiceFactory factory = bean.getObject();

    assertThat(factory).isNotNull();
    assertThat(factory.of("any")).isNull();
  }

  @Test
  @DisplayName("getObject creates a WebClientService for each configured client")
  void getObject_withConfigurations_createsServicePerClient() throws Exception {
    ClientConfiguration cfg = new ClientConfiguration(
        "https://api.example.com",
        Map.of(),
        new ClientTimeOutConfiguration(5000, 5000, 30000, 30000),
        null,
        false,
        false
    );
    var bean = factoryBean(new ClientProperties(Map.of("example", cfg)));

    WebClientServiceFactory factory = bean.getObject();

    assertThat(factory).isNotNull();
    assertThat(factory.of("example")).isNotNull();
  }

  @Test
  @DisplayName("getObject returns null for an unknown service name")
  void getObject_unknownServiceName_returnsNull() throws Exception {
    ClientConfiguration cfg = new ClientConfiguration(
        "https://api.example.com",
        Map.of(),
        new ClientTimeOutConfiguration(5000, 5000, 30000, 30000),
        null,
        false,
        false
    );
    var bean = factoryBean(new ClientProperties(Map.of("known", cfg)));

    WebClientServiceFactory factory = bean.getObject();

    assertThat(factory.of("unknown")).isNull();
  }

  private WebClientServiceFactoryBean factoryBean(ClientProperties props) {
    WebClientServiceFactoryBean bean = new WebClientServiceFactoryBean();
    bean.setClientProperties(props);
    bean.setWebClientFactory(webClientFactory);
    return bean;
  }
}
