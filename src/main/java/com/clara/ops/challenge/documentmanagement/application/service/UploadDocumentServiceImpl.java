package com.clara.ops.challenge.documentmanagement.application.service;

import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentCommand;
import com.clara.ops.challenge.documentmanagement.application.port.in.UploadDocumentService;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentRepository;
import com.clara.ops.challenge.documentmanagement.application.port.out.DocumentStorage;
import com.clara.ops.challenge.documentmanagement.domain.Document;
import com.clara.ops.challenge.documentmanagement.domain.exception.DuplicateDocumentException;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UploadDocumentServiceImpl implements UploadDocumentService {

  private final DocumentRepository documentRepository;
  private final DocumentStorage documentStorage;
  private final Semaphore storageSemaphore;

  public UploadDocumentServiceImpl(
      DocumentRepository documentRepository,
      DocumentStorage documentStorage,
      @Qualifier("storageSemaphore") Semaphore storageSemaphore) {
    this.documentRepository = documentRepository;
    this.documentStorage = documentStorage;
    this.storageSemaphore = storageSemaphore;
  }

  @Override
  public Document upload(UploadDocumentCommand cmd) {
    Document toCreate =
        Document.newUpload(cmd.user(), cmd.name(), cmd.tags(), cmd.contentLength(), cmd.contentType());

    if (documentRepository.existsByUserAndName(cmd.user(), cmd.name())) {
      throw new DuplicateDocumentException(cmd.user(), cmd.name());
    }

    storageSemaphore.acquireUninterruptibly();
    try {
      documentStorage.store(
          toCreate.storagePath(), cmd.content(), cmd.contentLength(), cmd.contentType());
    } finally {
      storageSemaphore.release();
    }

    try {
      return documentRepository.save(toCreate);
    } catch (DataIntegrityViolationException e) {
      // Race condition: another request inserted the same (user, name) between our pre-check and
      // this save. Compensate by removing the just-uploaded object from MinIO.
      safeDelete(toCreate.storagePath());
      throw new DuplicateDocumentException(cmd.user(), cmd.name());
    }
  }

  private void safeDelete(String storagePath) {
    try {
      documentStorage.delete(storagePath);
    } catch (Exception e) {
      log.error("failed to delete orphan MinIO object at '{}' after duplicate conflict", storagePath, e);
    }
  }
}
