package com.clara.ops.challenge.documentmanagement.application.service;

import com.clara.ops.challenge.documentmanagement.application.port.in.DownloadDocumentService;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.DocumentNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DownloadDocumentServiceImpl implements DownloadDocumentService {

  private final DocumentRepository documentRepository;
  private final DocumentStorage documentStorage;

  @Override
  public String getDownloadUrl(Long documentId) {
    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new DocumentNotFoundException(documentId));
    return documentStorage.generatePresignedUrl(document.storagePath());
  }
}
