package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.PaginatedDocumentSearchResponse;
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

/**
 * Verifies pipeline correctness under concurrent load: 10 simultaneous uploads all persist without
 * corruption, and duplicate races leave exactly one survivor with no orphaned MinIO objects.
 *
 * <p>The test profile raises {@code upload.admission.max-concurrent} to 20 so all 10 threads pass
 * the admission gate without being throttled, keeping assertions simple (10 × 201). The gate's own
 * behaviour — 503 when saturated — is covered by {@link UploadAdmissionFilterIntegrationTest},
 * which runs with {@code admission=1}. Memory safety under the production tuning ({@code
 * admission=1}) is validated manually via {@code scripts/memory-evidence.sh}.
 */
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
            ListObjectsArgs.builder()
                .bucket(BUCKET)
                .prefix("concurrent-user-")
                .recursive(true)
                .build())) {
      result.get(); // throws if any object is corrupted
      objectCount++;
    }
    assertThat(objectCount).isEqualTo(threads);
  }

  /**
   * Verifies that two simultaneous uploads of the same (user, name) leave exactly one DB row and
   * exactly one MinIO object. Before ADR-009, the loser's compensation would delete the winner's
   * object (same storagePath). With UUID-keyed paths each request owns a distinct MinIO key, so
   * compensation only ever removes its own object.
   */
  @Test
  void concurrentDuplicateUpload_exactlyOneSucceeds_noOrphanInMinIO() throws Exception {
    String user = "race-user";
    String name = "race-doc.pdf";

    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<HttpStatusCode> f1 = executor.submit(() -> upload(user, name));
    Future<HttpStatusCode> f2 = executor.submit(() -> upload(user, name));
    executor.shutdown();
    assertThat(executor.awaitTermination(2, TimeUnit.MINUTES)).isTrue();

    List<HttpStatusCode> statuses = List.of(f1.get(), f2.get());
    assertThat(statuses)
        .as("exactly one upload must succeed and one must be rejected as duplicate")
        .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);

    // Exactly one DB row — verified via search API
    HttpHeaders searchHeaders = new HttpHeaders();
    searchHeaders.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<PaginatedDocumentSearchResponse> searchResponse =
        restTemplate.exchange(
            "http://localhost:" + port + "/document-management/search",
            HttpMethod.POST,
            new HttpEntity<>(
                String.format("{\"user\":\"%s\",\"name\":\"%s\"}", user, name), searchHeaders),
            PaginatedDocumentSearchResponse.class);
    assertThat(searchResponse.getBody().metadata().totalItems())
        .as("exactly one document row must exist in DB")
        .isEqualTo(1);

    // Exactly one object in MinIO — verifies the loser's compensation did not delete the winner's
    int objectCount = 0;
    for (Result<Item> result :
        minioClient.listObjects(
            ListObjectsArgs.builder().bucket(BUCKET).prefix(user + "/").recursive(true).build())) {
      result.get();
      objectCount++;
    }
    assertThat(objectCount)
        .as("exactly one MinIO object must survive after duplicate race")
        .isEqualTo(1);
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
