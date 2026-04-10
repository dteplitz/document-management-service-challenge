package com.clara.ops.challenge.documentmanagement.application.port.in;

import java.util.List;

public record SearchDocumentQuery(String user, String name, List<String> tags) {}
