package com.clara.ops.challenge.documentmanagement.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

  // Primary client — used for all storage operations (put, delete).
  // Connects via the internal endpoint (e.g. "http://minio:9000" inside Docker).
  @Bean
  @Primary
  public MinioClient minioClient(MinioProperties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .credentials(props.accessKey(), props.secretKey())
        .region(props.region())
        .build();
  }

  // Presigned-URL client — used only for signing download URLs.
  // Configured with the public endpoint so the signed Host header matches what
  // external clients will hit. Pre-signed URL generation is purely computational
  // (HMAC-SHA256) — this client never opens a real connection to MinIO.
  @Bean
  @Qualifier("presignedMinioClient") public MinioClient presignedMinioClient(MinioProperties props) {
    String publicBase =
        (props.publicEndpoint() != null && !props.publicEndpoint().isBlank())
            ? props.publicEndpoint()
            : props.endpoint();
    return MinioClient.builder()
        .endpoint(publicBase)
        .credentials(props.accessKey(), props.secretKey())
        .region(props.region())
        .build();
  }
}
