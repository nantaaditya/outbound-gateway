package com.nantaaditya.example.api;

import com.nantaaditya.example.base.BaseIntegrationTest;
import com.nantaaditya.example.base.RestApiAssertion;
import com.nantaaditya.example.base.RestApiRequest;
import com.nantaaditya.example.model.constant.HeaderConstant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
public class TestControllerIntegrationTest extends BaseIntegrationTest {

  @Test
  @DisplayName("/api/test - success")
  void test() {
    RestApiRequest request = new RestApiRequest<>(
        HttpMethod.GET,
        "/api/test",
        Collections.emptyMap(),
        null,
        new RestApiAssertion<>(
            HttpStatus.OK, Map.of(HeaderConstant.CLIENT_ID.getHeader(), "test-client"), "true"),
        null
    );

    execute(request);
  }
}
