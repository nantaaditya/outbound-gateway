package com.nantaaditya.example.configuration;

import com.nantaaditya.example.helper.TracerHelper;
import com.nantaaditya.example.model.constant.ConstantName;
import com.nantaaditya.example.model.constant.HeaderConstant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.ReactiveAuditorAware;
import reactor.core.publisher.Mono;

@Configuration
public class R2dbcAuditorConfiguration implements ReactiveAuditorAware<String> {

  @Autowired
  private TracerHelper tracerHelper;

  @Override
  public Mono<String> getCurrentAuditor() {
    String auditor = Optional.ofNullable(tracerHelper.getBaggage(HeaderConstant.CLIENT_ID))
        .orElseGet(() -> ConstantName.DEFAULT_SYSTEM_AUDITOR);
    return Mono.just(auditor);
  }
}
