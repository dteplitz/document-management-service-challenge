package com.clara.ops.challenge.documentmanagement.adapter.in.web.dto;

import java.util.List;

public record DocumentResponse(
    String id,
    String user,
    String name,
    List<String> tags,
    int size,
    String type,
    String createdAt) {}
