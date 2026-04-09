package com.clara.ops.challenge.documentmanagement.config;

import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UploadConcurrencyConfig {

  @Bean("storageSemaphore")
  public Semaphore storageSemaphore(
      @Value("${upload.storage.max-concurrent:3}") int maxConcurrent) {
    // fair = true ensures FIFO ordering, preventing starvation under sustained load
    return new Semaphore(maxConcurrent, true);
  }
}
