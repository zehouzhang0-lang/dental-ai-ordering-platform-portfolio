package com.yuri.aiorder.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuri.aiorder.file.api.FileStorageConfiguration;
import com.yuri.aiorder.file.api.FileStorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FileStorageConfigurationTests {

    @Test
    void presignClientUsesBrowserReachableEndpointInsteadOfInternalDockerHostname() throws Exception {
        FileStorageProperties properties = new FileStorageProperties(
                "http://minio:9000",
                "https://files.phase-one.example.test",
                "us-east-1",
                "minio-test-user",
                "minio-test-password",
                "phase-one-test-bucket",
                900,
                900,
                7200,
                1024L,
                List.of("application/pdf"),
                3);
        FileStorageConfiguration configuration = new FileStorageConfiguration();
        MinioClient presignClient = configuration.presignMinioClient(properties);

        String signedUrl = presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(properties.bucket())
                .object("orders/1/result.pdf")
                .expiry(60, TimeUnit.SECONDS)
                .build());

        assertThat(URI.create(signedUrl).getHost()).isEqualTo("files.phase-one.example.test");
        assertThat(signedUrl).doesNotContain("minio:9000");
    }
}
