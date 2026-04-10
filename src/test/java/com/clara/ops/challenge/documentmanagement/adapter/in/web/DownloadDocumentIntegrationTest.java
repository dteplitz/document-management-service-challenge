package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.DocumentDownloadUrlResponse;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.ErrorResponse;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

class DownloadDocumentIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void download_existingDocument_returns200WithPresignedUrl() {
    Long id = uploadAndGetId("dl-alice", "download-me.pdf");

    ResponseEntity<DocumentDownloadUrlResponse> response = download(id);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().url()).isNotBlank();
    assertThat(response.getBody().url()).contains("download-me.pdf");
  }

  @Test
  void download_presignedUrl_isAccessible() {
    Long id = uploadAndGetId("dl-bob", "fetch-me.pdf");

    String url = download(id).getBody().url();

    // Use a plain RestTemplate — TestRestTemplate adds test-specific headers that break
    // the pre-signed URL's HMAC signature, causing MinIO to reject with 400.
    // Pass as URI (not String) to avoid RestTemplate's DefaultUriBuilderFactory
    // re-encoding '%' to '%25', which would double-encode the '%2F' separators in
    // X-Amz-Credential and cause MinIO to reject with AuthorizationQueryParametersError.
    try {
      ResponseEntity<byte[]> fileResponse =
          new RestTemplate().getForEntity(new URI(url), byte[].class);
      assertThat(fileResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(fileResponse.getBody()).isNotNull();
      assertThat(fileResponse.getBody().length).isGreaterThan(0);
    } catch (Exception e) {
      throw new AssertionError("Pre-signed URL fetch failed. URL was: " + url, e);
    }
  }

  @Test
  void download_unknownId_returns404() {
    ResponseEntity<ErrorResponse> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/document-management/download/999999",
            ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("DOCUMENT_NOT_FOUND");
  }

  @Test
  void download_serviceDoesNotProxyBytes() {
    Long id = uploadAndGetId("dl-carol", "no-proxy.pdf");

    ResponseEntity<DocumentDownloadUrlResponse> response = download(id);

    // The endpoint returns a URL string, not file bytes — Content-Type is JSON
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString())
        .contains(MediaType.APPLICATION_JSON_VALUE);
    assertThat(response.getBody().url()).startsWith("http");
  }

  // --- helpers ---

  private ResponseEntity<DocumentDownloadUrlResponse> download(Long documentId) {
    return restTemplate.getForEntity(
        "http://localhost:" + port + "/document-management/download/" + documentId,
        DocumentDownloadUrlResponse.class);
  }

  private Long uploadAndGetId(String user, String name) {
    byte[] pdfBytes = syntheticPdf();
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

    HttpHeaders metadataHeaders = new HttpHeaders();
    metadataHeaders.setContentType(MediaType.APPLICATION_JSON);
    String metadataJson =
        String.format("{\"user\":\"%s\",\"name\":\"%s\",\"tags\":[]}", user, name);
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
    ResponseEntity<Void> uploadResponse =
        restTemplate.postForEntity(
            "http://localhost:" + port + "/document-management/upload",
            new HttpEntity<>(body, requestHeaders),
            Void.class);

    // Extract ID from Location header: /document-management/download/{id}
    String location = uploadResponse.getHeaders().getFirst(HttpHeaders.LOCATION);
    assertThat(location).isNotNull();
    String idStr = location.substring(location.lastIndexOf('/') + 1);
    return Long.parseLong(idStr);
  }

  private byte[] syntheticPdf() {
    byte[] header = "%PDF-1.4\n".getBytes();
    byte[] content = new byte[64 * 1024];
    System.arraycopy(header, 0, content, 0, header.length);
    return content;
  }
}
