package com.nantaaditya.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.nantaaditya.example.crypto.EncryptionHelper;
import com.nantaaditya.example.entity.OutboundHostConfiguration;
import com.nantaaditya.example.service.impl.GatewayWebClientBuilder;
import com.nantaaditya.example.service.impl.ResilienceRegistryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import reactor.netty.resources.ConnectionProvider;

/**
 * A context load reads the host row, an admin edit then evicts the host, and the load goes on to build its client from
 * the row it read before the edit. Without a version check that outdated pool would sit in the cache under the host's
 * url and serve every later load, which reads the NEW row but is handed the OLD pool.
 */
class GatewayWebClientBuilderStaleHostTest {

  private static final String BASE_URL = "https://api.example.com";

  /** Every pool it creates is a new spied provider, so which pool a call got and which were disposed is visible. */
  private static class RecordingBuilder extends GatewayWebClientBuilder {
    final List<ConnectionProvider> providers = new ArrayList<>();

    RecordingBuilder() {
      super(WebClient.builder(),
          new ResilienceRegistryService(new SimpleMeterRegistry()),
          Logbook.builder().build(),
          new EncryptionHelper("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }

    @Override
    protected ConnectionProvider newConnectionProvider(OutboundHostConfiguration hostConfig) {
      ConnectionProvider provider = spy(ConnectionProvider.builder("pool-" + providers.size()).maxConnections(1).build());
      providers.add(provider);
      return provider;
    }

    @Override
    protected Duration disposeGracePeriod() {
      return Duration.ZERO;
    }
  }

  @Test
  @DisplayName("the same host row (same version) keeps using the pool it already has")
  void sameVersion_reusesPool() {
    RecordingBuilder builder = new RecordingBuilder();

    WebClient first = builder.getOrCreateWebClient(host(3));
    WebClient second = builder.getOrCreateWebClient(host(3));

    assertThat(second).isSameAs(first);
    assertThat(builder.providers).hasSize(1);
  }

  @Test
  @DisplayName("a load that read the host row before an edit cannot replace the pool built from the edited row")
  void olderRow_doesNotReplaceNewerPool() {
    RecordingBuilder builder = new RecordingBuilder();
    WebClient fromNewRow = builder.getOrCreateWebClient(host(2));

    WebClient fromOldRow = builder.getOrCreateWebClient(host(1));

    assertThat(fromOldRow).isSameAs(fromNewRow);
    assertThat(builder.providers).hasSize(1);
  }

  @Test
  @DisplayName("an outdated pool created after the eviction is replaced by the first load that reads the edited row")
  void newerRow_replacesOutdatedPool_andDisposesIt() {
    RecordingBuilder builder = new RecordingBuilder();
    builder.getOrCreateWebClient(host(1));
    builder.evictWebClient(BASE_URL);                        // the admin edit: host row is now version 2
    WebClient outdated = builder.getOrCreateWebClient(host(1));   // the slow load that read version 1 earlier

    WebClient fresh = builder.getOrCreateWebClient(host(2));      // the next load reads version 2

    assertThat(fresh).isNotSameAs(outdated);
    assertThat(builder.providers).hasSize(3);                // v1 (evicted), v1 again (stale), v2
    verify(builder.providers.get(0), timeout(3000)).disposeLater();   // evicted by the admin edit
    verify(builder.providers.get(1), timeout(3000)).disposeLater();   // the outdated one, replaced
    verify(builder.providers.get(2), after(300).never()).disposeLater();
    assertThat(builder.getOrCreateWebClient(host(2))).isSameAs(fresh);
    assertThat(builder.providers).hasSize(3);
  }

  private static OutboundHostConfiguration host(long version) {
    return OutboundHostConfiguration.builder()
        .baseUrl(BASE_URL)
        .connectTimeOutMs(1000)
        .maxConnections(1)
        .pendingAcquireMaxCount(1)
        .pendingAcquireTimeoutMs(1000)
        .version(version)
        .build();
  }
}
