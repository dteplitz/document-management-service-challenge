package com.clara.ops.challenge.documentmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {}
