package com.clara.ops.challenge.documentmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.clara.ops.challenge.documentmanagement.domain.exception.InvalidDocumentException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTest {

  @Test
  void newUpload_happyPath_setsStoragePath() {
    Document doc =
        Document.newUpload("alice", "report.pdf", List.of("finance"), 1024L, "application/pdf");

    assertThat(doc.id()).isNull();
    assertThat(doc.createdAt()).isNull();
    assertThat(doc.storagePath()).isEqualTo("alice/report.pdf");
    assertThat(doc.tags()).containsExactly("finance");
  }

  @Test
  void newUpload_emptyTagsList_isAllowed() {
    Document doc = Document.newUpload("alice", "report.pdf", List.of(), 1024L, "application/pdf");
    assertThat(doc.tags()).isEmpty();
  }

  @Test
  void newUpload_tagsAreImmutable() {
    Document doc =
        Document.newUpload("alice", "report.pdf", List.of("a"), 1024L, "application/pdf");
    assertThatThrownBy(() -> doc.tags().add("b")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void newUpload_nullUser_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload(null, "report.pdf", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("user is required");
  }

  @Test
  void newUpload_blankUser_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("  ", "report.pdf", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("user is required");
  }

  @Test
  void newUpload_nameWithoutPdfExtension_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("alice", "report", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("name must end with .pdf");
  }

  @Test
  void newUpload_nameWithUpperCasePdfExtension_isAccepted() {
    Document doc = Document.newUpload("alice", "REPORT.PDF", List.of(), 1024L, "application/pdf");
    assertThat(doc.name()).isEqualTo("REPORT.PDF");
  }

  @Test
  void newUpload_nullTags_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("alice", "report.pdf", null, 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("tags must not be null");
  }

  @Test
  void newUpload_blankTagValue_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () ->
                Document.newUpload(
                    "alice", "report.pdf", List.of("ok", ""), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("tags must not contain blank values");
  }

  @Test
  void newUpload_zeroFileSize_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("alice", "report.pdf", List.of(), 0L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("file size must be positive");
  }

  @Test
  void newUpload_nameWithSlash_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () ->
                Document.newUpload(
                    "alice", "../other-user/evil.pdf", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("path separators");
  }

  @Test
  void newUpload_nameWithBackslash_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("alice", "sub\\evil.pdf", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("path separators");
  }

  @Test
  void newUpload_userWithSlash_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () ->
                Document.newUpload("alice/bob", "report.pdf", List.of(), 1024L, "application/pdf"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("path separators");
  }

  @Test
  void newUpload_wrongContentType_throwsInvalidDocumentException() {
    assertThatThrownBy(
            () -> Document.newUpload("alice", "report.pdf", List.of(), 1024L, "text/plain"))
        .isInstanceOf(InvalidDocumentException.class)
        .hasMessageContaining("file type must be application/pdf");
  }

  @Test
  void newUpload_contentTypeCaseInsensitive_isAccepted() {
    Document doc = Document.newUpload("alice", "report.pdf", List.of(), 1024L, "Application/PDF");
    assertThat(doc.fileType()).isEqualTo("Application/PDF");
  }
}
