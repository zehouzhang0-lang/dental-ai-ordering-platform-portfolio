package com.yuri.aiorder.testing;

import com.yuri.aiorder.file.api.FileStorageProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Fails closed when an integration test is accidentally pointed at a non-test database.
 */
@Component
public class TestDatabaseSafetyGuard {

    private static final int TEST_REDIS_DATABASE = 15;

    public TestDatabaseSafetyGuard(
            DataSource dataSource,
            RedisConnectionFactory redisConnectionFactory,
            FileStorageProperties fileStorageProperties) {
        verifyDatabase(dataSource);
        verifyRedisDatabase(redisConnectionFactory);
        verifyFileBucket(fileStorageProperties);
    }

    private void verifyDatabase(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            String databaseName = connection.getCatalog();
            if (databaseName == null || !databaseName.toLowerCase(Locale.ROOT).endsWith("_test")) {
                throw new IllegalStateException(
                        "integration tests require a dedicated *_test database; current database is " + databaseName);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("cannot verify the integration-test database", ex);
        }
    }

    private void verifyRedisDatabase(RedisConnectionFactory redisConnectionFactory) {
        if (!(redisConnectionFactory instanceof LettuceConnectionFactory lettuceConnectionFactory)) {
            throw new IllegalStateException(
                    "integration tests require the managed Lettuce Redis connection factory to verify the database");
        }

        int redisDatabase = lettuceConnectionFactory.getDatabase();
        if (redisDatabase != TEST_REDIS_DATABASE) {
            throw new IllegalStateException(
                    "integration tests require Redis database "
                            + TEST_REDIS_DATABASE
                            + "; current database is "
                            + redisDatabase);
        }
    }

    private void verifyFileBucket(FileStorageProperties fileStorageProperties) {
        String bucket = fileStorageProperties.bucket();
        if (!isTestBucket(bucket)) {
            throw new IllegalStateException(
                    "integration tests require a MinIO bucket with a standalone test segment; current bucket is " + bucket);
        }
    }

    private boolean isTestBucket(String bucket) {
        return bucket != null && bucket.toLowerCase(Locale.ROOT).matches(".*(^|[.-])test([.-]|$).*");
    }
}
