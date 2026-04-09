package com.clara.ops.challenge.documentmanagement.application.port.in;

import java.io.InputStream;
import java.util.List;

public record UploadDocumentCommand(
    String user,
    String name,
    List<String> tags,
    InputStream content,
    long contentLength,
    String contentType) {}
