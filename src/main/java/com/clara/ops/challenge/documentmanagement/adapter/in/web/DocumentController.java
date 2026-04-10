package com.clara.ops.challenge.documentmanagement.adapter.in.web;

import com.clara.ops.challenge.documentmanagement.adapter.in.web.dto.UploadMetadataRequest;
import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentCommand;
import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentService;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.InvalidDocumentException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/document-management")
@RequiredArgsConstructor
public class DocumentController {

  private final UploadDocumentService uploadDocumentService;

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> upload(
      @RequestPart("metadata") @Valid UploadMetadataRequest metadata,
      @RequestPart("file") MultipartFile file)
      throws IOException {
    if (file.isEmpty()) {
      throw new InvalidDocumentException("file part is required and must not be empty");
    }
    try (InputStream in = file.getInputStream()) {
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
      return ResponseEntity.created(location).build();
    }
  }
}
