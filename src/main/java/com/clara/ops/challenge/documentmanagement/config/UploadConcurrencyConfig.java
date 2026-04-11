package com.clara.ops.challenge.documentmanagement.config;

import com.clara.ops.challenge.documentmanagement.adapter.in.web.UploadAdmissionFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class UploadConcurrencyConfig {

  /**
   * HTTP admission gate: limits how many upload requests proceed past the filter before multipart
   * parsing begins. Sized conservatively to keep JVM heap within the 50 MB budget under concurrent
   * load. See ADR-008.
   */
  @Bean("admissionSemaphore")
  public Semaphore admissionSemaphore(
      @Value("${upload.admission.max-concurrent:2}") int maxConcurrent) {
    return new Semaphore(maxConcurrent, true);
  }

  /**
   * Storage throttle: limits concurrent MinIO putObject calls. Each active slot allocates one
   * PART_SIZE (5 MB) buffer in heap. See ADR-006, ADR-007.
   */
  @Bean("storageSemaphore")
  public Semaphore storageSemaphore(
      @Value("${upload.storage.max-concurrent:3}") int maxConcurrent) {
    return new Semaphore(maxConcurrent, true);
  }

  /**
   * Registers UploadAdmissionFilter for POST /document-management/upload only. Running at
   * HIGHEST_PRECEDENCE + 10 ensures the gate fires before Spring's MultipartFilter and
   * DispatcherServlet, so admitted requests are the only ones whose request bodies get parsed.
   */
  @Bean
  public FilterRegistrationBean<UploadAdmissionFilter> uploadAdmissionFilterRegistration(
      @Qualifier("admissionSemaphore") Semaphore admissionSemaphore,
      ObjectMapper objectMapper,
      @Value("${upload.admission.acquire-timeout-seconds:15}") long acquireTimeoutSeconds) {
    FilterRegistrationBean<UploadAdmissionFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(
        new UploadAdmissionFilter(admissionSemaphore, objectMapper, acquireTimeoutSeconds));
    registration.addUrlPatterns("/document-management/upload");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }
}
