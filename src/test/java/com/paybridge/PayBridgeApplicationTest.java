package com.paybridge;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class PayBridgeApplicationTest {
  @Test
  void delegatesTheEntryPointWithoutStartingARealDatabaseOrServer() {
    String[] arguments = { "--spring.profiles.active=test" };

    try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
      springApplication.when(() -> SpringApplication.run(PayBridgeApplication.class, arguments)).thenReturn(null);

      PayBridgeApplication.main(arguments);

      springApplication.verify(() -> SpringApplication.run(PayBridgeApplication.class, arguments));
    }
  }
}
