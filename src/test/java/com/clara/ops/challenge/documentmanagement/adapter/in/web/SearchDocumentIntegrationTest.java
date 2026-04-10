package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.DocumentResponse;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.PaginatedDocumentSearchResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class SearchDocumentIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  // --- filter tests ---

  @Test
  void search_noFilters_returns200WithPaginationStructure() {
    upload("srch-nofilter", "any.pdf", List.of("x"));

    ResponseEntity<PaginatedDocumentSearchResponse> response = search(Map.of(), "");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().metadata()).isNotNull();
    assertThat(response.getBody().documents()).isNotNull();
  }

  @Test
  void search_byUser_returnsOnlyThatUsersDocuments() {
    upload("srch-user-alice", "doc1.pdf", List.of());
    upload("srch-user-alice", "doc2.pdf", List.of());
    upload("srch-user-bob", "other.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-user-alice"), "");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().documents()).hasSize(2);
    assertThat(response.getBody().documents()).allMatch(d -> d.user().equals("srch-user-alice"));
    assertThat(response.getBody().metadata().totalItems()).isEqualTo(2);
  }

  @Test
  void search_byName_returnsExactMatch() {
    upload("srch-name", "target.pdf", List.of());
    upload("srch-name", "other.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-name", "name", "target.pdf"), "");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().documents()).hasSize(1);
    assertThat(response.getBody().documents().get(0).name()).isEqualTo("target.pdf");
  }

  @Test
  void search_byTags_requiresAllTagsPresent() {
    // doc1 has both tags — should match
    upload("srch-tags", "both.pdf", List.of("finance", "2026"));
    // doc2 has only one — should NOT match
    upload("srch-tags", "one.pdf", List.of("finance"));

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-tags", "tags", List.of("finance", "2026")), "");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().documents()).hasSize(1);
    assertThat(response.getBody().documents().get(0).name()).isEqualTo("both.pdf");
  }

  @Test
  void search_byTagsSingleTag_returnsAllDocsContainingThatTag() {
    upload("srch-onetag", "has-it.pdf", List.of("legal", "extra"));
    upload("srch-onetag", "also-has-it.pdf", List.of("legal"));
    upload("srch-onetag", "no-tag.pdf", List.of("other"));

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-onetag", "tags", List.of("legal")), "");

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().documents()).hasSize(2);
    assertThat(response.getBody().documents()).allMatch(d -> d.tags().contains("legal"));
  }

  @Test
  void search_emptyTagsFilter_treatedAsNoTagFilter() {
    upload("srch-emptytags", "a.pdf", List.of("t1"));
    upload("srch-emptytags", "b.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-emptytags", "tags", List.of()), "");

    assertThat(response.getBody()).isNotNull();
    // empty tags list = no tag filter → both documents returned
    assertThat(response.getBody().documents()).hasSize(2);
  }

  @Test
  void search_emptyBody_treatedAsNoFilters() {
    upload("srch-emptybody", "c.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response = search(Map.of(), "");

    // empty body {} is valid — all documents returned (at least the one we just seeded)
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().metadata().totalItems()).isGreaterThanOrEqualTo(1);
  }

  // --- pagination tests ---

  @Test
  void search_pagination_metadataIsAccurate() {
    upload("srch-page", "p1.pdf", List.of());
    upload("srch-page", "p2.pdf", List.of());
    upload("srch-page", "p3.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-page"), "?size=2&page=0");

    assertThat(response.getBody()).isNotNull();
    var meta = response.getBody().metadata();
    assertThat(meta.totalItems()).isEqualTo(3);
    assertThat(meta.totalPages()).isEqualTo(2);
    assertThat(meta.currentItems()).isEqualTo(2);
    assertThat(meta.itemsPerPage()).isEqualTo(2);
    assertThat(meta.currentPage()).isEqualTo(0);
  }

  @Test
  void search_pagination_secondPageHasRemainingItem() {
    upload("srch-page2", "q1.pdf", List.of());
    upload("srch-page2", "q2.pdf", List.of());
    upload("srch-page2", "q3.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-page2"), "?size=2&page=1");

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().documents()).hasSize(1);
    assertThat(response.getBody().metadata().currentItems()).isEqualTo(1);
  }

  // --- ordering test ---

  @Test
  void search_defaultSort_mostRecentDocumentIsFirst() throws InterruptedException {
    upload("srch-sort", "older.pdf", List.of());
    // Small pause to guarantee distinct created_at timestamps
    Thread.sleep(50);
    upload("srch-sort", "newer.pdf", List.of());

    ResponseEntity<PaginatedDocumentSearchResponse> response =
        search(Map.of("user", "srch-sort"), "");

    assertThat(response.getBody()).isNotNull();
    List<DocumentResponse> docs = response.getBody().documents();
    assertThat(docs).hasSize(2);
    Instant first = Instant.parse(docs.get(0).createdAt());
    Instant second = Instant.parse(docs.get(1).createdAt());
    assertThat(first).isAfterOrEqualTo(second);
  }

  // --- helpers ---

  private ResponseEntity<PaginatedDocumentSearchResponse> search(
      Map<String, Object> filters, String queryParams) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<Map<String, Object>> request = new HttpEntity<>(filters, headers);
    return restTemplate.postForEntity(
        "http://localhost:" + port + "/document-management/search" + queryParams,
        request,
        PaginatedDocumentSearchResponse.class);
  }

  private void upload(String user, String name, List<String> tags) {
    byte[] pdfBytes = syntheticPdf();
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

    HttpHeaders metadataHeaders = new HttpHeaders();
    metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
    String tagsJson = tags.isEmpty() ? "[]" : "[\"" + String.join("\",\"", tags) + "\"]";
    String metadataJson =
        String.format("{\"user\":\"%s\",\"name\":\"%s\",\"tags\":%s}", user, name, tagsJson);
    body.add("metadata", new HttpEntity<>(metadataJson, metadataHeaders));

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
    restTemplate.postForEntity(
        "http://localhost:" + port + "/document-management/upload",
        new HttpEntity<>(body, requestHeaders),
        Void.class);
  }

  private byte[] syntheticPdf() {
    byte[] header = "%PDF-1.4\n".getBytes();
    byte[] content = new byte[64 * 1024]; // 64 KB — enough to be a real upload, fast to seed
    System.arraycopy(header, 0, content, 0, header.length);
    return content;
  }
}
