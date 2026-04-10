package com.clara.ops.challenge.documentmanagement.adapter.in.web.dto;

public record PaginationMetadata(
    int currentPage, int itemsPerPage, int currentItems, int totalPages, int totalItems) {}
