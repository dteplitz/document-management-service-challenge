package com.clara.ops.challenge.documentmanagement.application.port.in;

public interface DownloadDocumentService {

  String getDownloadUrl(Long documentId);
}
