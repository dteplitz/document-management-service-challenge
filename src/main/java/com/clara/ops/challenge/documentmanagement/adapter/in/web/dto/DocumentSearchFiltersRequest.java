package com.clara.ops.challenge.documentmanagement.adapter.in.web.dto;

import java.util.List;

public record DocumentSearchFiltersRequest(String user, String name, List<String> tags) {}
