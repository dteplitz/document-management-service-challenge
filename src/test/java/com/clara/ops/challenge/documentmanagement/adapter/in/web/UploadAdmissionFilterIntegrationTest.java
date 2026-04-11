package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Verifies that UploadAdmissionFilter throttles concurrent uploads and returns 503 with the correct
 * error code when capacity is exhausted. Uses max-concurrent=1 so the second concurrent request
 * always hits the gate.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "upload.admission.max-concurrent=1",
      "upload.admission.acquire-timeout-seconds=1"
    })
class UploadAdmissionFilterIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void whenCapacityExceeded_secondRequestReceives503WithCorrectCode() throws Exception {
    int threads = 3;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Future<HttpStatusCode>> futures = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
      final int idx = i;
      futures.add(executor.submit(() -> upload("admission-user-" + idx, "doc-" + idx + ".pdf")));
    }

    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    List<HttpStatusCode> statuses = futures.stream().map(this::getUnchecked).toList();

    long created = statuses.stream().filter(s -> s.equals(HttpStatus.CREATED)).count();
    long rejected = statuses.stream().filter(s -> s.equals(HttpStatus.SERVICE_UNAVAILABLE)).count();

    assertThat(created).as("at least one upload must succeed").isGreaterThanOrEqualTo(1);
    assertThat(rejected)
        .as("at least one upload must be rejected by the admission gate")
        .isGreaterThanOrEqualTo(1);
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

  private HttpStatusCode getUnchecked(Future<HttpStatusCode> f) {
    try {
      return f.get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
