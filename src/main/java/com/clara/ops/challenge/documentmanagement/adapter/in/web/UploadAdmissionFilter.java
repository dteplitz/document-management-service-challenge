package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admission gate for POST /document-management/upload.
 *
 * <p>Acquires a permit before calling doFilter(), which means multipart parsing and file body
 * reading do not begin until a slot is available. This bounds concurrent heap pressure to
 * (admissionSemaphore.permits × MinIO PART_SIZE) regardless of how many clients upload in parallel.
 * Requests that cannot acquire a permit within the configured timeout receive 503.
 *
 * <p>Registered exclusively via FilterRegistrationBean (no @Component) to avoid double registration
 * by Spring Boot's auto-detection. See UploadConcurrencyConfig.
 */
@Slf4j
public class UploadAdmissionFilter extends OncePerRequestFilter {

  private final Semaphore admissionSemaphore;
  private final ObjectMapper objectMapper;
  private final long acquireTimeoutSeconds;

  public UploadAdmissionFilter(
      Semaphore admissionSemaphore, ObjectMapper objectMapper, long acquireTimeoutSeconds) {
    this.admissionSemaphore = admissionSemaphore;
    this.objectMapper = objectMapper;
    this.acquireTimeoutSeconds = acquireTimeoutSeconds;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    boolean acquired = false;
    try {
      int queued = admissionSemaphore.getQueueLength();
      if (queued > 0) {
        log.info("upload queued — waiting for slot (queue length: {})", queued);
      }
      acquired = admissionSemaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
      if (!acquired) {
        log.warn(
            "upload admission rejected — queue length: {}", admissionSemaphore.getQueueLength());
        writeError(
            response,
            HttpStatus.SERVICE_UNAVAILABLE,
            "UPLOAD_CAPACITY_EXCEEDED",
            "upload capacity temporarily exhausted, retry later");
        return;
      }
      log.info("upload admitted — available permits: {}", admissionSemaphore.availablePermits());
      chain.doFilter(request, response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeError(
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          "UPLOAD_CAPACITY_EXCEEDED",
          "upload interrupted, retry later");
    } finally {
      if (acquired) {
        admissionSemaphore.release();
      }
    }
  }

  private void writeError(
      HttpServletResponse response, HttpStatus status, String code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), new ErrorResponse(code, message));
  }
}
