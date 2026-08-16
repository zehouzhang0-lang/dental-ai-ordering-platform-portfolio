package com.yuri.aiorder.file.api;

import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfiguration {

    @Bean
    @Primary
    public MinioClient minioClient(FileStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .region(properties.region())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean("presignMinioClient")
    public MinioClient presignMinioClient(FileStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.publicEndpoint())
                .region(properties.region())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    public MinioAsyncClient minioAsyncClient(FileStorageProperties properties) {
        return MinioAsyncClient.builder()
                .endpoint(properties.endpoint())
                .region(properties.region())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
