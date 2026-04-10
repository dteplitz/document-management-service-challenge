package com.clara.ops.challenge.documentmanagement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("minio")
public record MinioProperties(
    String endpoint,
    // Optional: overrides the host in pre-signed URLs. Needed when the MinIO
    // client connects via an internal hostname (e.g. "minio" in Docker) but
    // the generated URLs must be reachable from outside (e.g. "localhost").
    // Defaults to endpoint when not set.
    String publicEndpoint,
    String accessKey,
    String secretKey,
    String bucket,
    String region,
    int presignedUrlExpirySeconds) {}
