package com.clara.ops.challenge.documentmanagement.application.port.out;

import java.io.InputStream;

public interface DocumentStorage {

  void store(String storagePath, InputStream content, long contentLength, String contentType);

  void delete(String storagePath);

  String generatePresignedUrl(String storagePath);
}
