package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Manual heavy load tests — NOT included in normal mvn verify.
 *
 * <p>Run with: mvn verify -Pheavy
 *
 * <p>These tests exercise the 50MB heap constraint under large payloads. Observe memory during
 * execution with: docker stats document-management-service (when running against a deployed
 * container)
 */
@Tag("heavy")
class LoadUpload500MBTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void singleUpload_500MB_completesWithinHeapBudget() {
    int size = 500 * 1024 * 1024; // 500 MB
    byte[] pdfBytes = new byte[size];
    pdfBytes[0] = '%';
    pdfBytes[1] = 'P';
    pdfBytes[2] = 'D';
    pdfBytes[3] = 'F';

    HttpStatusCode status = upload("load-user", "big-file.pdf", pdfBytes);

    assertThat(status).isEqualTo(HttpStatus.CREATED);
  }

  private HttpStatusCode upload(String user, String name, byte[] pdfBytes) {
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
    ByteArrayResource fileResource =
        new ByteArrayResource(pdfBytes) {
          @Override
          public String getFilename() {
            return name;
          }
        };
    body.add("file", new HttpEntity<>(fileResource, fileHeaders));

    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

    ResponseEntity<Void> response =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/document-management/upload",
            new HttpEntity<>(body, requestHeaders),
            Void.class);
    return response.getStatusCode();
  }
}
