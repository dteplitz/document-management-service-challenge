package com.clara.ops.challenge.documentmanagement.application.port.in;

import com.clara.ops.challenge.documentmanagement.domain.Document;

public interface UploadDocumentService {

  Document upload(UploadDocumentCommand command);
}
