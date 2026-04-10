package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clara.ops.challenge.documentmanagement.AbstractIntegrationTest;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.ErrorResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class UploadDocumentIntegrationTest extends AbstractIntegrationTest {

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private MinioClient minioClient;

  @Test
  void upload_happyPath_returns201AndPersistsToMinioAndDb() throws Exception {
    byte[] pdfBytes = syntheticPdf();

    ResponseEntity<Void> response = upload("alice", "invoice.pdf", List.of("finance"), pdfBytes);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    // verify object exists in MinIO
    var stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET).object("alice/invoice.pdf").build());
    assertThat(stat.size()).isEqualTo(pdfBytes.length);
  }

  @Test
  void upload_duplicateName_returns409() {
    byte[] pdfBytes = syntheticPdf();
    upload("bob", "dup.pdf", List.of(), pdfBytes);

    ResponseEntity<ErrorResponse> response =
        uploadForError("bob", "dup.pdf", List.of(), pdfBytes, ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("DUPLICATE_DOCUMENT");
  }

  @Test
  void upload_missingUser_returns400() {
    ResponseEntity<ErrorResponse> response =
        uploadForError("", "doc.pdf", List.of(), syntheticPdf(), ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void upload_nameWithoutPdfExtension_returns400() {
    ResponseEntity<ErrorResponse> response =
        uploadForError("carol", "nodotpdf", List.of(), syntheticPdf(), ErrorResponse.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INVALID_DOCUMENT");
  }

  // --- helpers ---

  private ResponseEntity<Void> upload(
      String user, String name, List<String> tags, byte[] pdfBytes) {
    return restTemplate.postForEntity(
        "http://localhost:" + port + "/document-management/upload",
        buildMultipart(user, name, tags, pdfBytes),
        Void.class);
  }

  private <T> ResponseEntity<T> uploadForError(
      String user, String name, List<String> tags, byte[] pdfBytes, Class<T> responseType) {
    return restTemplate.postForEntity(
        "http://localhost:" + port + "/document-management/upload",
        buildMultipart(user, name, tags, pdfBytes),
        responseType);
  }

  private HttpEntity<MultiValueMap<String, Object>> buildMultipart(
      String user, String name, List<String> tags, byte[] pdfBytes) {
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
    return new HttpEntity<>(body, requestHeaders);
  }

  private byte[] syntheticPdf() {
    // Minimal valid PDF header — enough to pass content-type sniffing if any
    byte[] header = "%PDF-1.4\n".getBytes();
    byte[] content = new byte[10 * 1024 * 1024]; // 10 MB
    System.arraycopy(header, 0, content, 0, header.length);
    return content;
  }
}
