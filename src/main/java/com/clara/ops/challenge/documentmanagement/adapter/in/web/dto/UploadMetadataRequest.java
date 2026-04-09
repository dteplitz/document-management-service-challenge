package com.clara.ops.challenge.documentmanagement.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UploadMetadataRequest(
    @NotBlank String user,
    @NotBlank String name,
    @NotNull List<@NotBlank String> tags) {}
