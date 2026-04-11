package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.DocumentDownloadUrlResponse;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.DocumentResponse;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.DocumentSearchFiltersRequest;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.PaginatedDocumentSearchResponse;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.PaginationMetadata;
import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.UploadMetadataRequest;
import com.clara.ops.challenge.documentmanagement.application.port.in.DownloadDocumentService;
import com.clara.ops.challenge.documentmanagement.application.port.in.SearchDocumentQuery;
import com.clara.ops.challenge.documentmanagement.application.port.in.SearchDocumentService;
import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentCommand;
import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentService;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.InvalidDocumentException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/document-management")
@RequiredArgsConstructor
@Slf4j
public class DocumentController {

  private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F'};

  private final UploadDocumentService uploadDocumentService;
  private final SearchDocumentService searchDocumentService;
  private final DownloadDocumentService downloadDocumentService;

  @Operation(summary = "Upload a PDF document (multipart/form-data)")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(implementation = UploadForm.class),
              encoding =
                  @Encoding(name = "metadata", contentType = MediaType.APPLICATION_JSON_VALUE)))
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> upload(
      @RequestPart("metadata") @Valid UploadMetadataRequest metadata,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    log.info(
        "upload request — user: {}, name: {}, size: {} bytes, contentType: {}",
        metadata.user(),
        metadata.name(),
        file.getSize(),
        file.getContentType());
    if (file.isEmpty()) {
      throw new InvalidDocumentException("file part is required and must not be empty");
    }
    try (InputStream raw = file.getInputStream()) {
      byte[] header = raw.readNBytes(4);
      // Validate magic bytes instead of trusting Content-Type, which clients can spoof
      if (!Arrays.equals(header, PDF_MAGIC)) {
        throw new InvalidDocumentException("uploaded file is not a valid PDF");
      }
      // Prepend the consumed header bytes so the full stream reaches storage
      InputStream in = new SequenceInputStream(new ByteArrayInputStream(header), raw);
      Document created =
          uploadDocumentService.upload(
              new UploadDocumentCommand(
                  metadata.user(),
                  metadata.name(),
                  metadata.tags(),
                  in,
                  file.getSize(),
                  file.getContentType()));
      URI location = URI.create("/document-management/download/" + created.id());
      log.info(
          "upload complete — user: {}, name: {}, documentId: {}",
          metadata.user(),
          metadata.name(),
          created.id());
      return ResponseEntity.created(location).build();
    }
  }

  @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<PaginatedDocumentSearchResponse> search(
      @RequestBody DocumentSearchFiltersRequest filters,
      @ParameterObject
          @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    log.info(
        "search request — user: {}, name: {}, tags: {}, page: {}, size: {}",
        filters.user(),
        filters.name(),
        filters.tags(),
        pageable.getPageNumber(),
        pageable.getPageSize());
    SearchDocumentQuery query =
        new SearchDocumentQuery(filters.user(), filters.name(), filters.tags());
    Page<Document> result = searchDocumentService.search(query, pageable);

    PaginationMetadata metadata =
        new PaginationMetadata(
            result.getNumber(),
            result.getSize(),
            result.getNumberOfElements(),
            result.getTotalPages(),
            (int) result.getTotalElements());

    log.info(
        "search result — total: {}, page: {}/{}",
        result.getTotalElements(),
        result.getNumber(),
        result.getTotalPages());
    List<DocumentResponse> documents =
        result.getContent().stream().map(DocumentController::toResponse).toList();

    return ResponseEntity.ok(new PaginatedDocumentSearchResponse(metadata, documents));
  }

  @GetMapping("/download/{documentId}")
  public ResponseEntity<DocumentDownloadUrlResponse> download(@PathVariable Long documentId) {
    log.info("download request — documentId: {}", documentId);
    String url = downloadDocumentService.getDownloadUrl(documentId);
    return ResponseEntity.ok(new DocumentDownloadUrlResponse(url));
  }

  // Swagger UI schema for the multipart upload request. Not used at runtime —
  // Spring reads the actual @RequestPart parameters. The @RequestBody annotation
  // above references this class to generate the correct OpenAPI multipart schema,
  // with the metadata part explicitly encoded as application/json.
  @SuppressWarnings("unused")
  private static final class UploadForm {
    @Schema(description = "Document metadata", required = true)
    public UploadMetadataRequest metadata;

    @Schema(type = "string", format = "binary", description = "PDF file (max 500 MB)")
    public MultipartFile file;
  }

  private static DocumentResponse toResponse(Document doc) {
    return new DocumentResponse(
        String.valueOf(doc.id()),
        doc.user(),
        doc.name(),
        doc.tags(),
        (int) doc.fileSize(),
        doc.fileType(),
        doc.createdAt() != null ? doc.createdAt().toString() : null);
  }
}
