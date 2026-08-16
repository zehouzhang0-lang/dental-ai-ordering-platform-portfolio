package com.yuri.aiorder.file.api;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class FileController {

    private final FileResourceService fileResourceService;

    public FileController(FileResourceService fileResourceService) {
        this.fileResourceService = fileResourceService;
    }

    @PostMapping("/files/upload-token")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<UploadTokenResponse> createUploadToken(
            @Valid @RequestBody UploadTokenRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.createUploadToken(request, identity));
    }

    @PostMapping("/files/multipart/initiate")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MultipartInitiateResponse> initiateMultipartUpload(
            @Valid @RequestBody MultipartInitiateRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.initiateMultipartUpload(request, identity));
    }

    @GetMapping("/files/multipart/pending")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MultipartPendingUploadsResponse> listPendingMultipartUploads(
            @RequestParam("order_id") long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.listPendingMultipartUploads(orderId, identity));
    }

    @PostMapping("/files/{fileId}/multipart/part-url")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MultipartPartUrlResponse> createMultipartPartUrl(
            @PathVariable long fileId,
            @Valid @RequestBody MultipartPartUrlRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.createMultipartPartUrl(fileId, request, identity));
    }

    @GetMapping("/files/{fileId}/multipart/status")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MultipartStatusResponse> getMultipartStatus(
            @PathVariable long fileId,
            @RequestParam("upload_id") String uploadId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.getMultipartStatus(fileId, uploadId, identity));
    }

    @PostMapping("/files/{fileId}/multipart/complete")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<FileCompleteResponse> completeMultipartUpload(
            @PathVariable long fileId,
            @Valid @RequestBody MultipartCompleteRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.completeMultipartUpload(fileId, request, identity));
    }

    @PostMapping("/files/{fileId}/multipart/abort")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<FileCompleteResponse> abortMultipartUpload(
            @PathVariable long fileId,
            @Valid @RequestBody MultipartAbortRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.abortMultipartUpload(fileId, request, identity));
    }

    @PostMapping("/files/{fileId}/complete")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<FileCompleteResponse> completeUpload(
            @PathVariable long fileId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.completeUpload(fileId, identity));
    }

    @GetMapping("/orders/{orderId}/files")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<OrderFileResponse>> listOrderFiles(
            @PathVariable long orderId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.listOrderFiles(orderId, identity));
    }

    @GetMapping("/files/{fileId}/preview-url")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<FileSignedUrlResponse> getPreviewUrl(
            @PathVariable long fileId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.createPreviewUrl(fileId, identity));
    }

    @GetMapping("/files/{fileId}/download-url")
    @RequirePermission(value = {"file:manage-internal", "file:access-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<FileSignedUrlResponse> getDownloadUrl(
            @PathVariable long fileId,
            BootstrapIdentity identity) {
        return new DataResponse<>(fileResourceService.createDownloadUrl(fileId, identity));
    }
}
