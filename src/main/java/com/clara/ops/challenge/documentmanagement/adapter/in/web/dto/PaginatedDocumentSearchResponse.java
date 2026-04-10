package com.clara.ops.challenge.documentmanagement.adapter.in.web.dto;

import java.util.List;

public record PaginatedDocumentSearchResponse(
    PaginationMetadata metadata, List<DocumentResponse> documents) {}
