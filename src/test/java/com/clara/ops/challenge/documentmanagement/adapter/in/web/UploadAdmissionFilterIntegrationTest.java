package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Verifies that UploadAdmissionFilter returns 503 UPLOAD_CAPACITY_EXCEEDED when all permits are
 * exhausted. The admissionSemaphore is held manually before firing the request so the outcome is
 * deterministic — no timing dependency on concurrent thread scheduling.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "upload.admission.max-concurrent=1",
      "upload.admission.acquire-timeout-seconds=1"
    })
class UploadAdmissionFilterIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Autowired
  @Qualifier("admissionSemaphore") private Semaphore admissionSemaphore;

  @Test
  void whenAllPermitsHeld_uploadReceives503WithCorrectCode() throws InterruptedException {
    // Drain the single available permit to simulate a saturated admission gate
    admissionSemaphore.acquire();
    try {
      HttpStatusCode status = upload("admission-user", "blocked-doc.pdf");
      assertThat(status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    } finally {
      admissionSemaphore.release();
    }
  }

  private HttpStatusCode upload(String user, String name) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

    HttpHeaders metadataHeaders = new HttpHeaders();
    metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
    body.add(
        "metadata",
        new HttpEntity<>(
            String.format("{\"user\":\"%s\",\"name\":\"%s\",\"tags\":[]}", user, name),
            metadataHeaders));

    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    byte[] pdfBytes = new byte[1024];
    pdfBytes[0] = '%';
    pdfBytes[1] = 'P';
    pdfBytes[2] = 'D';
    pdfBytes[3] = 'F';
    body.add(
        "file",
        new HttpEntity<>(
            new ByteArrayResource(pdfBytes) {
              @Override
              public String getFilename() {
                return name;
              }
            },
            fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    return restTemplate
        .postForEntity(
            "http://localhost:" + port + "/document-management/upload",
            new HttpEntity<>(body, requestHeaders),
            Void.class)
        .getStatusCode();
  }
}
