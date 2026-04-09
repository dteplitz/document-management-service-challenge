package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ConcurrentUploadIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private MinioClient minioClient;

  @Test
  void tenConcurrentUploads_allSucceed_noOrphanObjects() throws Exception {
    int threads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<Future<HttpStatusCode>> futures = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
      final String user = "concurrent-user-" + i;
      final String name = "concurrent-doc-" + i + ".pdf";
      futures.add(executor.submit(() -> upload(user, name)));
    }

    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.MINUTES)).isTrue();

    for (Future<HttpStatusCode> f : futures) {
      assertThat(f.get()).isEqualTo(HttpStatus.CREATED);
    }

    // verify all 10 objects are in MinIO under their respective user paths
    int objectCount = 0;
    for (Result<Item> result :
        minioClient.listObjects(
            ListObjectsArgs.builder().bucket(BUCKET).prefix("concurrent-user-").recursive(true).build())) {
      result.get(); // throws if any object is corrupted
      objectCount++;
    }
    assertThat(objectCount).isEqualTo(threads);
  }

  private HttpStatusCode upload(String user, String name) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

    HttpHeaders metadataHeaders = new HttpHeaders();
    metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
    String metadataJson =
        String.format("{\"user\":\"%s\",\"name\":\"%s\",\"tags\":[]}", user, name);
    body.add("metadata", new HttpEntity<>(metadataJson, metadataHeaders));

    HttpHeaders fileHeaders = new HttpHeaders();
    fileHeaders.setContentType(MediaType.APPLICATION_PDF);
    byte[] pdfBytes = new byte[10 * 1024 * 1024]; // 10 MB per upload
    pdfBytes[0] = '%';
    pdfBytes[1] = 'P';
    pdfBytes[2] = 'D';
    pdfBytes[3] = 'F';
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
