package com.yuri.aiorder.file.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListPartsResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Part;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileResourceService {

    private static final Set<String> DOCTOR_VISIBLE_FILE_VISIBILITIES = Set.of("DOCTOR", "DOCTOR_CS", "ALL");
    private static final long MIN_MULTIPART_PART_SIZE = 5L * 1024L * 1024L;

    private final JdbcClient jdbcClient;
    private final MinioClient minioClient;
    private final MinioClient presignMinioClient;
    private final MinioAsyncClient minioAsyncClient;
    private final FileStorageProperties properties;
    private final AccessControlService accessControlService;

    public FileResourceService(
            JdbcClient jdbcClient,
            MinioClient minioClient,
            @Qualifier("presignMinioClient") MinioClient presignMinioClient,
            MinioAsyncClient minioAsyncClient,
            FileStorageProperties properties,
            AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.minioClient = minioClient;
        this.presignMinioClient = presignMinioClient;
        this.minioAsyncClient = minioAsyncClient;
        this.properties = properties;
        this.accessControlService = accessControlService;
    }

    public UploadTokenResponse createUploadToken(UploadTokenRequest request, BootstrapIdentity identity) {
        validateUploadLimits(request.orderId(), request.originalFilename(), request.contentType(), request.fileSize());
        OrderScope orderScope = loadOrderScope(
                request.orderId(), identity, "identity cannot upload to this order", false);
        String sourceType = normalizeCode(request.sourceType());
        String visibility = normalizeVisibility(request.visibility());
        requireUploadScope(orderScope, sourceType, visibility, identity);
        ensureBucket();

        String objectKey = buildObjectKey(request);
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, :ownerUserId, :sourceType, :visibility, :bucketName, :objectKey,
                             :originalFilename, :contentType, :fileSize, 'PENDING', 'ACTIVE')
                        """)
                .param("orderId", request.orderId())
                .param("ownerUserId", identity.userId())
                .param("sourceType", sourceType)
                .param("visibility", visibility)
                .param("bucketName", properties.bucket())
                .param("objectKey", objectKey)
                .param("originalFilename", request.originalFilename())
                .param("contentType", request.contentType())
                .param("fileSize", request.fileSize())
                .update();
        long fileId = jdbcClient.sql("""
                        SELECT file_id
                        FROM file_resource
                        WHERE bucket_name = :bucketName
                          AND object_key = :objectKey
                        """)
                .param("bucketName", properties.bucket())
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        audit(fileId, request.orderId(), identity.userId(), "UPLOAD_TOKEN", "ALLOWED", null);

        return new UploadTokenResponse(
                fileId,
                presignedUrl(Method.PUT, objectKey, properties.uploadUrlTtlSeconds()),
                properties.uploadUrlTtlSeconds());
    }

    public List<OrderFileResponse> listOrderFiles(long orderId, BootstrapIdentity identity) {
        OrderScope orderScope = loadOrderScope(
                orderId, identity, "identity cannot access this order's files", true);
        String dataScope = accessControlService.effectiveDataScope(identity);
        boolean clinicScoped = "CLINIC".equals(dataScope);
        boolean selfScoped = "SELF".equals(dataScope);
        boolean csScoped = identity.role() == UserRole.CS;
        boolean designReviewer = identity.hasPermission("design-draft:internal-review");
        return jdbcClient.sql("""
                        SELECT file_id, source_type, visibility, original_filename, content_type,
                               file_size, upload_status, created_at
                        FROM file_resource f
                        WHERE (
                              f.order_id = :orderId
                              OR (
                                  f.case_group_id = :groupId
                                  AND f.attachment_scope = 'SHARED'
                              )
                          )
                          AND f.status = 'ACTIVE'
                          AND f.upload_status = 'COMPLETED'
                          AND (
                              :clinicScoped = 0
                              OR (
                                  f.visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL')
                                  AND (
                                      EXISTS (
                                          SELECT 1
                                          FROM design_draft_file visible_file
                                          JOIN design_draft visible_draft
                                            ON visible_draft.design_draft_id = visible_file.design_draft_id
                                          WHERE visible_file.file_id = f.file_id
                                            AND visible_draft.doctor_visible_at IS NOT NULL
                                      )
                                      OR (
                                          f.source_type <> 'DESIGN_DRAFT'
                                          AND NOT EXISTS (
                                              SELECT 1
                                              FROM design_draft_file internal_file
                                              WHERE internal_file.file_id = f.file_id
                                          )
                                      )
                                  )
                              )
                          )
                          AND (
                              :selfScoped = 0
                              OR EXISTS (
                                  SELECT 1
                                          FROM orders self_order
                                          WHERE (
                                                self_order.order_id = f.order_id
                                                OR (
                                                    f.attachment_scope = 'SHARED'
                                                    AND self_order.group_id = f.case_group_id
                                                )
                                            )
                                    AND (
                                        self_order.doctor_user_id = :userId
                                        OR self_order.cs_user_id = :userId
                                        OR EXISTS (
                                            SELECT 1
                                            FROM order_process_instance self_instance
                                            JOIN order_process_node self_node
                                              ON self_node.instance_id = self_instance.instance_id
                                            WHERE self_instance.order_id = self_order.order_id
                                              AND self_node.assigned_user_id = :userId
                                        )
                                        OR EXISTS (
                                            SELECT 1
                                            FROM design_task self_design
                                            WHERE self_design.order_id = self_order.order_id
                                              AND self_design.assigned_user_id = :userId
                                        )
                                    )
                              )
                              OR (
                                  :designReviewer = 1
                                  AND EXISTS (
                                      SELECT 1
                                      FROM design_draft_file review_file
                                      JOIN design_draft review_draft
                                        ON review_draft.design_draft_id = review_file.design_draft_id
                                      WHERE review_file.file_id = f.file_id
                                        AND review_draft.order_id = f.order_id
                                        AND review_draft.submitted_at IS NOT NULL
                                  )
                              )
                          )
                          AND (
                              :csScoped = 0
                              OR EXISTS (
                                  SELECT 1
                                  FROM design_draft_file visible_file
                                  JOIN design_draft visible_draft
                                    ON visible_draft.design_draft_id = visible_file.design_draft_id
                                  WHERE visible_file.file_id = f.file_id
                                    AND visible_draft.doctor_visible_at IS NOT NULL
                              )
                              OR (
                                  f.source_type <> 'DESIGN_DRAFT'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM design_draft_file internal_file
                                      WHERE internal_file.file_id = f.file_id
                                  )
                              )
                          )
                        ORDER BY created_at DESC, file_id DESC
                        """)
                .param("orderId", orderId)
                .param("groupId", orderScope.groupId())
                .param("clinicScoped", clinicScoped ? 1 : 0)
                .param("selfScoped", selfScoped ? 1 : 0)
                .param("csScoped", csScoped ? 1 : 0)
                .param("designReviewer", designReviewer ? 1 : 0)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new OrderFileResponse(
                        rs.getLong("file_id"),
                        rs.getString("source_type"),
                        rs.getString("visibility"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getObject("file_size", Long.class),
                        rs.getString("upload_status"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    public MultipartInitiateResponse initiateMultipartUpload(MultipartInitiateRequest request, BootstrapIdentity identity) {
        validateUploadLimits(request.orderId(), request.originalFilename(), request.contentType(), request.fileSize());
        OrderScope orderScope = loadOrderScope(
                request.orderId(), identity, "identity cannot upload to this order", false);
        String sourceType = normalizeCode(request.sourceType());
        String visibility = normalizeVisibility(request.visibility());
        requireUploadScope(orderScope, sourceType, visibility, identity);
        ensureBucket();

        long partSize = Math.max(request.partSize() == null ? MIN_MULTIPART_PART_SIZE : request.partSize(), MIN_MULTIPART_PART_SIZE);
        int partCount = Math.toIntExact((request.fileSize() + partSize - 1) / partSize);
        String objectKey = buildObjectKey(request.orderId(), request.sourceType(), request.originalFilename());
        String uploadId = createMultipartUpload(objectKey, request.contentType());

        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, upload_mode,
                             multipart_upload_id, multipart_part_size, multipart_part_count, status)
                        VALUES
                            (:orderId, :ownerUserId, :sourceType, :visibility, :bucketName, :objectKey,
                             :originalFilename, :contentType, :fileSize, 'PENDING', 'MULTIPART',
                             :uploadId, :partSize, :partCount, 'ACTIVE')
                        """)
                .param("orderId", request.orderId())
                .param("ownerUserId", identity.userId())
                .param("sourceType", sourceType)
                .param("visibility", visibility)
                .param("bucketName", properties.bucket())
                .param("objectKey", objectKey)
                .param("originalFilename", request.originalFilename())
                .param("contentType", request.contentType())
                .param("fileSize", request.fileSize())
                .param("uploadId", uploadId)
                .param("partSize", partSize)
                .param("partCount", partCount)
                .update();
        long fileId = jdbcClient.sql("""
                        SELECT file_id
                        FROM file_resource
                        WHERE multipart_upload_id = :uploadId
                        """)
                .param("uploadId", uploadId)
                .query(Long.class)
                .single();
        audit(fileId, request.orderId(), identity.userId(), "MULTIPART_INITIATE", "ALLOWED", null);
        return new MultipartInitiateResponse(fileId, uploadId, partSize, partCount, properties.uploadUrlTtlSeconds());
    }

    public MultipartPendingUploadsResponse listPendingMultipartUploads(long orderId, BootstrapIdentity identity) {
        loadOrderScope(orderId, identity, "identity cannot list uploads for this order", false);
        List<MultipartPendingUploadsResponse.Item> items = jdbcClient.sql("""
                        SELECT
                            f.file_id,
                            f.multipart_upload_id,
                            f.order_id,
                            f.source_type,
                            f.visibility,
                            f.original_filename,
                            f.content_type,
                            f.file_size,
                            f.multipart_part_size,
                            f.multipart_part_count
                        FROM file_resource f
                        WHERE f.order_id = :orderId
                          AND f.status = 'ACTIVE'
                          AND f.upload_status = 'PENDING'
                          AND f.upload_mode = 'MULTIPART'
                          AND f.multipart_upload_id IS NOT NULL
                          AND (:ownerScoped = 0 OR f.owner_user_id = :userId)
                        ORDER BY f.updated_at DESC, f.file_id DESC
                        """)
                .param("orderId", orderId)
                .param("ownerScoped", identity.role() == UserRole.ADMIN ? 0 : 1)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> new MultipartPendingUploadsResponse.Item(
                        rs.getLong("file_id"),
                        rs.getString("multipart_upload_id"),
                        rs.getLong("order_id"),
                        rs.getString("source_type"),
                        rs.getString("visibility"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getLong("file_size"),
                        rs.getLong("multipart_part_size"),
                        rs.getInt("multipart_part_count")))
                .list();
        for (MultipartPendingUploadsResponse.Item item : items) {
            audit(item.fileId(), item.orderId(), identity.userId(), "MULTIPART_PENDING_LIST", "ALLOWED", null);
        }
        return new MultipartPendingUploadsResponse(items);
    }

    public MultipartPartUrlResponse createMultipartPartUrl(
            long fileId,
            MultipartPartUrlRequest request,
            BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "MULTIPART_PART_URL");
        requireFileActorScope(file, identity, "MULTIPART_PART_URL");
        requireMultipartOwner(file, identity, "MULTIPART_PART_URL");
        requireMultipartPending(file, request.uploadId(), "MULTIPART_PART_URL", identity);
        if (file.multipartPartCount() != null && request.partNumber() > file.multipartPartCount()) {
            audit(file.fileId(), file.orderId(), identity.userId(), "MULTIPART_PART_URL", "DENIED", "part number out of range");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "part_number out of range");
        }
        String url = presignedMultipartPartUrl(file.objectKey(), request.uploadId(), request.partNumber());
        audit(file.fileId(), file.orderId(), identity.userId(), "MULTIPART_PART_URL", "ALLOWED", null);
        return new MultipartPartUrlResponse(
                file.fileId(),
                request.uploadId(),
                request.partNumber(),
                url,
                properties.uploadUrlTtlSeconds());
    }

    public MultipartStatusResponse getMultipartStatus(long fileId, String uploadId, BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "MULTIPART_STATUS");
        requireFileActorScope(file, identity, "MULTIPART_STATUS");
        requireMultipartOwner(file, identity, "MULTIPART_STATUS");
        requireMultipartPending(file, uploadId, "MULTIPART_STATUS", identity);
        List<MultipartStatusResponse.PartStatus> completedParts = listMultipartParts(file, uploadId);
        audit(file.fileId(), file.orderId(), identity.userId(), "MULTIPART_STATUS", "ALLOWED", null);
        return new MultipartStatusResponse(
                file.fileId(),
                uploadId,
                file.uploadStatus(),
                file.multipartPartSize(),
                file.multipartPartCount(),
                completedParts);
    }

    public FileCompleteResponse completeMultipartUpload(
            long fileId,
            MultipartCompleteRequest request,
            BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "MULTIPART_COMPLETE");
        requireFileActorScope(file, identity, "MULTIPART_COMPLETE");
        requireMultipartOwner(file, identity, "MULTIPART_COMPLETE");
        requireMultipartPending(file, request.uploadId(), "MULTIPART_COMPLETE", identity);
        Part[] parts = request.parts().stream()
                .sorted(Comparator.comparing(MultipartCompleteRequest.Part::partNumber))
                .map(part -> new Part(part.partNumber(), normalizeEtag(part.etag())))
                .toArray(Part[]::new);
        completeMultipart(file, request.uploadId(), parts);
        StatObjectResponse stat = statObject(file);
        String contentType;
        try {
            contentType = validateCompletedObject(file, stat, "MULTIPART_COMPLETE", identity);
        } catch (ResponseStatusException validationFailure) {
            cleanupRejectedMultipartObject(file, identity);
            throw validationFailure;
        }
        jdbcClient.sql("""
                        UPDATE file_resource
                        SET upload_status = 'COMPLETED',
                            file_size = :fileSize,
                            content_type = :contentType,
                            checksum = :checksum
                        WHERE file_id = :fileId
                        """)
                .param("fileSize", stat.size())
                .param("contentType", contentType)
                .param("checksum", stat.etag())
                .param("fileId", file.fileId())
                .update();
        audit(file.fileId(), file.orderId(), identity.userId(), "MULTIPART_COMPLETE", "ALLOWED", null);
        return new FileCompleteResponse(file.fileId(), "COMPLETED", stat.size(), contentType, stat.etag());
    }

    private void cleanupRejectedMultipartObject(FileRow file, BootstrapIdentity identity) {
        String statusFailureDetail = null;
        try {
            int updatedRows = jdbcClient.sql("""
                            UPDATE file_resource
                            SET upload_status = 'REJECTED'
                            WHERE file_id = :fileId
                              AND upload_status = 'PENDING'
                            """)
                    .param("fileId", file.fileId())
                    .update();
            if (updatedRows != 1) {
                statusFailureDetail = "rejected status update affected " + updatedRows + " rows";
            }
        } catch (RuntimeException statusFailure) {
            statusFailureDetail = "rejected status update failed: "
                    + statusFailure.getClass().getSimpleName();
        }

        String cleanupOutcome = "ALLOWED";
        String cleanupDetail = "removed rejected completed object";
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(file.bucketName())
                    .object(file.objectKey())
                    .build());
        } catch (Exception cleanupFailure) {
            cleanupOutcome = "FAILED";
            cleanupDetail = "rejected object cleanup failed: "
                    + cleanupFailure.getClass().getSimpleName();
        }
        if (statusFailureDetail != null) {
            cleanupOutcome = "FAILED";
            cleanupDetail = cleanupDetail + "; " + statusFailureDetail;
        }
        try {
            audit(
                    file.fileId(),
                    file.orderId(),
                    identity.userId(),
                    "MULTIPART_REJECT_CLEANUP",
                    cleanupOutcome,
                    cleanupDetail);
        } catch (RuntimeException ignored) {
            // Cleanup and its audit are best-effort; never mask the original validation failure.
        }
    }

    public FileCompleteResponse abortMultipartUpload(
            long fileId,
            MultipartAbortRequest request,
            BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "MULTIPART_ABORT");
        requireFileActorScope(file, identity, "MULTIPART_ABORT");
        requireMultipartOwner(file, identity, "MULTIPART_ABORT");
        requireMultipartPending(file, request.uploadId(), "MULTIPART_ABORT", identity);
        abortMultipart(file, request.uploadId());
        jdbcClient.sql("""
                        UPDATE file_resource
                        SET upload_status = 'ABORTED'
                        WHERE file_id = :fileId
                        """)
                .param("fileId", file.fileId())
                .update();
        audit(file.fileId(), file.orderId(), identity.userId(), "MULTIPART_ABORT", "ALLOWED", null);
        return new FileCompleteResponse(
                file.fileId(),
                "ABORTED",
                file.fileSize() == null ? 0 : file.fileSize(),
                file.contentType(),
                null);
    }

    public FileCompleteResponse completeUpload(long fileId, BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "COMPLETE");
        requireFileActorScope(file, identity, "COMPLETE");
        requireOwnedUploadMutation(file, identity, "COMPLETE");
        StatObjectResponse stat = statObject(file);
        String contentType = validateCompletedObject(file, stat, "COMPLETE", identity);
        jdbcClient.sql("""
                        UPDATE file_resource
                        SET upload_status = 'COMPLETED',
                            file_size = :fileSize,
                            content_type = :contentType,
                            checksum = :checksum
                        WHERE file_id = :fileId
                        """)
                .param("fileSize", stat.size())
                .param("contentType", contentType)
                .param("checksum", stat.etag())
                .param("fileId", file.fileId())
                .update();
        audit(file.fileId(), file.orderId(), identity.userId(), "COMPLETE", "ALLOWED", null);
        return new FileCompleteResponse(file.fileId(), "COMPLETED", stat.size(), contentType, stat.etag());
    }

    private String validateCompletedObject(
            FileRow file,
            StatObjectResponse stat,
            String action,
            BootstrapIdentity identity) {
        if (stat.size() <= 0) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "empty object");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object is empty");
        }
        if (properties.maxFileSizeBytes() > 0 && stat.size() > properties.maxFileSizeBytes()) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "actual file exceeds size limit");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object exceeds current size limit");
        }
        if (file.fileSize() != null && stat.size() != file.fileSize()) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "file size mismatch");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object size does not match token");
        }
        String actualContentType = normalizeContentType(stat.contentType());
        if (actualContentType.isBlank()) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "missing actual content type");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object content type is missing");
        }
        if (!isAllowedContentType(actualContentType)) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "actual content type is not allowed");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object content type is not allowed");
        }
        String declaredContentType = normalizeContentType(file.contentType());
        if (!declaredContentType.isBlank() && !declaredContentType.equals(actualContentType)) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "content type mismatch");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object content type does not match token");
        }
        return actualContentType;
    }

    public FileSignedUrlResponse createPreviewUrl(long fileId, BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "PREVIEW");
        requireCompleted(file, "PREVIEW", identity);
        requireFileActorScope(file, identity, "PREVIEW");
        String url = presignedUrl(Method.GET, file.objectKey(), properties.previewUrlTtlSeconds());
        audit(file.fileId(), file.orderId(), identity.userId(), "PREVIEW", "ALLOWED", null);
        return new FileSignedUrlResponse(file.fileId(), url, null, properties.previewUrlTtlSeconds());
    }

    public FileSignedUrlResponse createDownloadUrl(long fileId, BootstrapIdentity identity) {
        FileRow file = loadFile(fileId, identity, "DOWNLOAD");
        requireCompleted(file, "DOWNLOAD", identity);
        requireFileActorScope(file, identity, "DOWNLOAD");
        String url = presignedUrl(Method.GET, file.objectKey(), properties.downloadUrlTtlSeconds());
        audit(file.fileId(), file.orderId(), identity.userId(), "DOWNLOAD", "ALLOWED", null);
        return new FileSignedUrlResponse(file.fileId(), null, url, properties.downloadUrlTtlSeconds());
    }

    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.bucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.bucket())
                        .build());
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "file storage bucket unavailable", ex);
        }
    }

    private StatObjectResponse statObject(FileRow file) {
        try {
            return minioClient.statObject(StatObjectArgs.builder()
                    .bucket(file.bucketName())
                    .object(file.objectKey())
                    .build());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "uploaded object not found", ex);
        }
    }

    private String presignedUrl(Method method, String objectKey, int ttlSeconds) {
        try {
            return presignMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry(ttlSeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cannot create signed url", ex);
        }
    }

    private String presignedMultipartPartUrl(String objectKey, String uploadId, int partNumber) {
        try {
            return presignMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .extraQueryParams(Map.of(
                            "partNumber", String.valueOf(partNumber),
                            "uploadId", uploadId))
                    .expiry(properties.uploadUrlTtlSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cannot create multipart signed url", ex);
        }
    }

    private String createMultipartUpload(String objectKey, String contentType) {
        try {
            Multimap<String, String> headers = HashMultimap.create();
            headers.put("Content-Type", contentType);
            return minioAsyncClient.createMultipartUploadAsync(
                            properties.bucket(),
                            null,
                            objectKey,
                            headers,
                            HashMultimap.create())
                    .get()
                    .result()
                    .uploadId();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "multipart upload interrupted", ex);
        } catch (ExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cannot initiate multipart upload", ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "cannot initiate multipart upload", ex);
        }
    }

    private void completeMultipart(FileRow file, String uploadId, Part[] parts) {
        try {
            minioAsyncClient.completeMultipartUploadAsync(
                            properties.bucket(),
                            null,
                            file.objectKey(),
                            uploadId,
                            parts,
                            HashMultimap.create(),
                            HashMultimap.create())
                    .get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "multipart complete interrupted", ex);
        } catch (ExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot complete multipart upload", ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot complete multipart upload", ex);
        }
    }

    private void abortMultipart(FileRow file, String uploadId) {
        try {
            minioAsyncClient.abortMultipartUploadAsync(
                            properties.bucket(),
                            null,
                            file.objectKey(),
                            uploadId,
                            HashMultimap.create(),
                            HashMultimap.create())
                    .get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "multipart abort interrupted", ex);
        } catch (ExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot abort multipart upload", ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot abort multipart upload", ex);
        }
    }

    private List<MultipartStatusResponse.PartStatus> listMultipartParts(FileRow file, String uploadId) {
        try {
            List<MultipartStatusResponse.PartStatus> parts = new ArrayList<>();
            int marker = 0;
            boolean truncated;
            do {
                ListPartsResponse response = minioAsyncClient.listPartsAsync(
                                properties.bucket(),
                                null,
                                file.objectKey(),
                                1000,
                                marker,
                                uploadId,
                                HashMultimap.create(),
                                HashMultimap.create())
                        .get();
                for (Part part : response.result().partList()) {
                    parts.add(new MultipartStatusResponse.PartStatus(
                            part.partNumber(),
                            normalizeEtag(part.etag()),
                            part.partSize()));
                }
                truncated = response.result().isTruncated();
                marker = response.result().nextPartNumberMarker();
            } while (truncated);
            parts.sort(Comparator.comparing(MultipartStatusResponse.PartStatus::partNumber));
            return parts;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "multipart status interrupted", ex);
        } catch (ExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot list multipart parts", ex);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot list multipart parts", ex);
        }
    }

    private void requireUploadScope(
            OrderScope orderScope,
            String sourceType,
            String visibility,
            BootstrapIdentity identity) {
        if ("DESIGN_DRAFT".equals(sourceType)) {
            if (!"INTERNAL".equals(visibility)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "design draft files must remain internal before review");
            }
            if (identity.role() != UserRole.WORKER
                    || identity.userId() == null
                    || !isAssignedDesignWorker(orderScope.orderId(), identity.userId())) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "only the assigned design worker can upload design draft files");
            }
            return;
        }
        if (identity.role() == UserRole.WORKER) {
            if (!"INTERNAL".equals(visibility)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "worker uploads must remain internal");
            }
            return;
        }
        if (!identity.isDoctor()) {
            return;
        }
        if (!DOCTOR_VISIBLE_FILE_VISIBILITIES.contains(visibility)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot upload internal files");
        }
        if (!accessControlService.doctorCanAccessOrder(identity, orderScope.doctorUserId(), orderScope.clinicId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot upload to this order");
        }
    }

    private boolean isAssignedDesignWorker(long orderId, long userId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_task
                        WHERE order_id = :orderId
                          AND assigned_user_id = :userId
                          AND task_status IN ('CLAIMED', 'INTERNAL_REJECTED', 'DOCTOR_REJECTED')
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Long.class)
                .single() > 0;
    }

    private void validateUploadLimits(long orderId, String originalFilename, String contentType, long fileSize) {
        if (fileSize > properties.maxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file exceeds current size limit");
        }
        if (!isAllowedFilenameExtension(originalFilename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file name extension is not allowed");
        }
        if (!isAllowedContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file content type is not allowed");
        }
        if (properties.maxFilesPerOrder() > 0 && activeFileCount(orderId) >= properties.maxFilesPerOrder()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order file count exceeds current limit");
        }
    }

    private boolean isAllowedFilenameExtension(String originalFilename) {
        if (properties.allowedFilenameExtensions() == null || properties.allowedFilenameExtensions().isEmpty()) {
            return true;
        }
        if (originalFilename == null) {
            return false;
        }
        int separator = originalFilename.lastIndexOf('.');
        if (separator <= 0 || separator == originalFilename.length() - 1) {
            return false;
        }
        String extension = originalFilename.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
        return properties.allowedFilenameExtensions().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(extension::equals);
    }

    private boolean isAllowedContentType(String contentType) {
        if (properties.allowedContentTypes() == null || properties.allowedContentTypes().isEmpty()) {
            return true;
        }
        String normalized = normalizeContentType(contentType);
        return properties.allowedContentTypes().stream()
                .map(this::normalizeContentType)
                .anyMatch(normalized::equals);
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parametersStart = contentType.indexOf(';');
        String mediaType = parametersStart >= 0 ? contentType.substring(0, parametersStart) : contentType;
        return mediaType.trim().toLowerCase(Locale.ROOT);
    }

    private long activeFileCount(long orderId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE order_id = :orderId
                          AND status = 'ACTIVE'
                          AND upload_status NOT IN ('ABORTED', 'REJECTED')
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private void requireCompleted(FileRow file, String action, BootstrapIdentity identity) {
        if ("COMPLETED".equals(file.uploadStatus())) {
            return;
        }
        audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "file upload is not completed");
        throw new ResponseStatusException(HttpStatus.CONFLICT, "file upload is not completed");
    }

    private void requireMultipartPending(FileRow file, String uploadId, String action, BootstrapIdentity identity) {
        if (!"MULTIPART".equals(file.uploadMode())
                || !"PENDING".equals(file.uploadStatus())
                || file.multipartUploadId() == null
                || !file.multipartUploadId().equals(uploadId)) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "invalid multipart upload");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "invalid multipart upload");
        }
    }

    private void requireMultipartOwner(FileRow file, BootstrapIdentity identity, String action) {
        requireOwnedUploadMutation(file, identity, action);
    }

    private void requireOwnedUploadMutation(FileRow file, BootstrapIdentity identity, String action) {
        boolean ownerOnly = identity.role() != UserRole.ADMIN;
        if (!ownerOnly || (file.ownerUserId() != null && file.ownerUserId().equals(identity.userId()))) {
            return;
        }
        audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "actor cannot mutate another uploader's file");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "actor cannot mutate another uploader's file");
    }

    private void requireFileActorScope(FileRow file, BootstrapIdentity identity, String action) {
        if (!identity.isDoctor()) {
            return;
        }
        if ((file.orderId() == null && file.caseGroupId() == null) || file.doctorUserId() == null
                || !DOCTOR_VISIBLE_FILE_VISIBILITIES.contains(file.visibility())
                || !accessControlService.doctorCanAccessOrder(identity, file.doctorUserId(), file.clinicId())) {
            audit(file.fileId(), file.orderId(), identity.userId(), action, "DENIED", "doctor cannot access this file");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this file");
        }
    }

    private OrderScope loadOrderScope(
            long orderId,
            BootstrapIdentity identity,
            String forbiddenMessage,
            boolean allowDesignReviewScope) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return jdbcClient.sql("""
                            SELECT order_id, group_id, clinic_id, doctor_user_id
                            FROM orders
                            WHERE order_id = :orderId
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'CLINIC'
                                      AND (clinic_id = :clinicId OR doctor_user_id = :userId))
                                  OR (:dataScope = 'SELF'
                                      AND (
                                          doctor_user_id = :userId
                                          OR cs_user_id = :userId
                                          OR EXISTS (
                                              SELECT 1
                                              FROM order_process_instance scoped_i
                                              JOIN order_process_node scoped_n
                                                ON scoped_n.instance_id = scoped_i.instance_id
                                              WHERE scoped_i.order_id = orders.order_id
                                                AND scoped_n.assigned_user_id = :userId
                                          )
                                          OR EXISTS (
                                              SELECT 1
                                              FROM design_task scoped_design
                                              WHERE scoped_design.order_id = orders.order_id
                                                AND scoped_design.assigned_user_id = :userId
                                          )
                                          OR (
                                              :designReviewer = 1
                                              AND EXISTS (
                                                  SELECT 1
                                                  FROM design_draft review_draft
                                                  WHERE review_draft.order_id = orders.order_id
                                                    AND review_draft.submitted_at IS NOT NULL
                                              )
                                          )
                                      ))
                              )
                            """)
                    .param("orderId", orderId)
                    .param("dataScope", dataScope)
                    .param(
                            "designReviewer",
                            allowDesignReviewScope && identity.hasPermission("design-draft:internal-review") ? 1 : 0)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .query((rs, rowNum) -> new OrderScope(
                            rs.getLong("order_id"),
                            rs.getObject("group_id", Long.class),
                            rs.getLong("clinic_id"),
                            rs.getObject("doctor_user_id", Long.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage, ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private FileRow loadFile(long fileId, BootstrapIdentity identity, String action) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        try {
            return jdbcClient.sql("""
                            SELECT
                                f.file_id,
                                f.order_id,
                                f.case_group_id,
                                f.owner_user_id,
                                f.source_type,
                                f.visibility,
                                f.bucket_name,
                                f.object_key,
                                f.content_type,
                                f.file_size,
                                f.upload_status,
                                f.upload_mode,
                                f.multipart_upload_id,
                                f.multipart_part_size,
                                f.multipart_part_count,
                                f.status,
                                COALESCE(o.clinic_id, case_group.clinic_id) AS clinic_id,
                                COALESCE(o.doctor_user_id, case_group.doctor_user_id) AS doctor_user_id
                            FROM file_resource f
                            LEFT JOIN orders o ON o.order_id = f.order_id
                            LEFT JOIN order_case_group case_group
                              ON case_group.group_id = f.case_group_id
                            WHERE f.file_id = :fileId
                              AND f.status = 'ACTIVE'
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'CLINIC'
                                      AND (f.order_id IS NOT NULL OR f.case_group_id IS NOT NULL)
                                      AND (
                                          COALESCE(o.clinic_id, case_group.clinic_id) = :clinicId
                                          OR COALESCE(o.doctor_user_id, case_group.doctor_user_id) = :userId
                                      )
                                      AND f.visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL')
                                      AND (
                                          EXISTS (
                                              SELECT 1
                                              FROM design_draft_file visible_file
                                              JOIN design_draft visible_draft
                                                ON visible_draft.design_draft_id = visible_file.design_draft_id
                                              WHERE visible_file.file_id = f.file_id
                                                AND visible_draft.doctor_visible_at IS NOT NULL
                                          )
                                          OR (
                                              f.source_type <> 'DESIGN_DRAFT'
                                              AND NOT EXISTS (
                                                  SELECT 1
                                                  FROM design_draft_file internal_file
                                                  WHERE internal_file.file_id = f.file_id
                                              )
                                          )
                                      ))
                                  OR (:dataScope = 'SELF'
                                      AND (
                                          f.owner_user_id = :userId
                                          OR (
                                              (f.order_id IS NOT NULL OR f.case_group_id IS NOT NULL)
                                              AND EXISTS (
                                                  SELECT 1
                                                  FROM order_process_instance scoped_i
                                                  JOIN order_process_node scoped_n
                                                    ON scoped_n.instance_id = scoped_i.instance_id
                                                  JOIN orders scoped_order
                                                    ON scoped_order.order_id = scoped_i.order_id
                                                  WHERE (
                                                        scoped_i.order_id = f.order_id
                                                        OR (
                                                            f.attachment_scope = 'SHARED'
                                                            AND scoped_order.group_id = f.case_group_id
                                                        )
                                                    )
                                                    AND scoped_n.assigned_user_id = :userId
                                              )
                                          )
                                          OR (
                                              (f.order_id IS NOT NULL OR f.case_group_id IS NOT NULL)
                                              AND EXISTS (
                                                  SELECT 1
                                                  FROM design_task scoped_design
                                                  JOIN orders scoped_order
                                                    ON scoped_order.order_id = scoped_design.order_id
                                                  WHERE (
                                                        scoped_design.order_id = f.order_id
                                                        OR (
                                                            f.attachment_scope = 'SHARED'
                                                            AND scoped_order.group_id = f.case_group_id
                                                        )
                                                    )
                                                    AND scoped_design.assigned_user_id = :userId
                                              )
                                          )
                                          OR (
                                              :designReviewer = 1
                                              AND f.order_id IS NOT NULL
                                              AND EXISTS (
                                                  SELECT 1
                                                  FROM design_draft_file review_file
                                                  JOIN design_draft review_draft
                                                    ON review_draft.design_draft_id = review_file.design_draft_id
                                                  WHERE review_file.file_id = f.file_id
                                                    AND review_draft.order_id = f.order_id
                                                    AND review_draft.submitted_at IS NOT NULL
                                              )
                                          )
                                      ))
                              )
                              AND (
                                  :csActor = 0
                                  OR (
                                      :csDesignRead = 1
                                      AND EXISTS (
                                          SELECT 1
                                          FROM design_draft_file visible_file
                                          JOIN design_draft visible_draft
                                            ON visible_draft.design_draft_id = visible_file.design_draft_id
                                          WHERE visible_file.file_id = f.file_id
                                            AND visible_draft.doctor_visible_at IS NOT NULL
                                      )
                                  )
                                  OR (
                                      f.source_type <> 'DESIGN_DRAFT'
                                      AND NOT EXISTS (
                                          SELECT 1
                                          FROM design_draft_file internal_file
                                          WHERE internal_file.file_id = f.file_id
                                      )
                                  )
                              )
                            """)
                    .param("fileId", fileId)
                    .param("dataScope", dataScope)
                    .param(
                            "designReviewer",
                            ("PREVIEW".equals(action) || "DOWNLOAD".equals(action))
                                            && identity.hasPermission("design-draft:internal-review")
                                    ? 1
                                    : 0)
                    .param("csActor", identity.role() == UserRole.CS ? 1 : 0)
                    .param(
                            "csDesignRead",
                            identity.role() == UserRole.CS
                                            && ("PREVIEW".equals(action) || "DOWNLOAD".equals(action))
                                    ? 1
                                    : 0)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .query((rs, rowNum) -> new FileRow(
                            rs.getLong("file_id"),
                            rs.getObject("order_id", Long.class),
                            rs.getObject("case_group_id", Long.class),
                            rs.getObject("owner_user_id", Long.class),
                            rs.getString("source_type"),
                            rs.getString("visibility"),
                            rs.getString("bucket_name"),
                            rs.getString("object_key"),
                            rs.getString("content_type"),
                            rs.getObject("file_size", Long.class),
                            rs.getString("upload_status"),
                            rs.getString("upload_mode"),
                            rs.getString("multipart_upload_id"),
                            rs.getObject("multipart_part_size", Long.class),
                            rs.getObject("multipart_part_count", Integer.class),
                            rs.getString("status"),
                            rs.getObject("clinic_id", Long.class),
                            rs.getObject("doctor_user_id", Long.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            Optional<FileAuditTarget> auditTarget = loadFileAuditTarget(fileId);
            if (auditTarget.isPresent()) {
                FileAuditTarget target = auditTarget.get();
                audit(target.fileId(), target.orderId(), identity.userId(), action, "DENIED", "identity cannot access this file");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identity cannot access this file", ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found", ex);
        }
    }

    private boolean orderExists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private Optional<FileAuditTarget> loadFileAuditTarget(long fileId) {
        return jdbcClient.sql("""
                        SELECT file_id, order_id
                        FROM file_resource
                        WHERE file_id = :fileId
                        """)
                .param("fileId", fileId)
                .query((rs, rowNum) -> new FileAuditTarget(
                        rs.getLong("file_id"),
                        rs.getObject("order_id", Long.class)))
                .optional();
    }

    private void audit(Long fileId, Long orderId, Long actorUserId, String action, String result, String reason) {
        jdbcClient.sql("""
                        INSERT INTO file_access_audit
                            (file_id, order_id, actor_user_id, action, access_result, reason)
                        VALUES
                            (:fileId, :orderId, :actorUserId, :action, :accessResult, :reason)
                        """)
                .param("fileId", fileId)
                .param("orderId", orderId)
                .param("actorUserId", actorUserId)
                .param("action", action)
                .param("accessResult", result)
                .param("reason", reason)
                .update();
    }

    private String buildObjectKey(UploadTokenRequest request) {
        return buildObjectKey(request.orderId(), request.sourceType(), request.originalFilename());
    }

    private String buildObjectKey(long orderId, String sourceType, String originalFilename) {
        String source = normalizeCode(sourceType).toLowerCase(Locale.ROOT);
        String filename = sanitizeFilename(originalFilename);
        return source + "/" + orderId + "/" + LocalDate.now() + "/" + UUID.randomUUID() + "/" + filename;
    }

    private String normalizeEtag(String etag) {
        return etag.replace("\"", "").trim();
    }

    private String sanitizeFilename(String filename) {
        String clean = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (clean.isBlank()) {
            return "upload.bin";
        }
        return clean.length() > 120 ? clean.substring(clean.length() - 120) : clean;
    }

    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeVisibility(String value) {
        return normalizeCode(value);
    }

    private record OrderScope(long orderId, Long groupId, long clinicId, Long doctorUserId) {
    }

    private record FileRow(
            long fileId,
            Long orderId,
            Long caseGroupId,
            Long ownerUserId,
            String sourceType,
            String visibility,
            String bucketName,
            String objectKey,
            String contentType,
            Long fileSize,
            String uploadStatus,
            String uploadMode,
            String multipartUploadId,
            Long multipartPartSize,
            Integer multipartPartCount,
            String status,
            Long clinicId,
            Long doctorUserId) {
    }

    private record FileAuditTarget(long fileId, Long orderId) {
    }
}
