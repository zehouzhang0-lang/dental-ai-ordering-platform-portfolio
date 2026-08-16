package com.yuri.aiorder.export;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** TASK-034 E 批次：导出管控的请求与响应模型。 */
public final class ExportModels {

    private ExportModels() {
    }

    public record DatasetResponse(
            @JsonProperty("dataset_code") String datasetCode,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("sensitivity") String sensitivity,
            @JsonProperty("permission_code") String permissionCode,
            @JsonProperty("field_list") List<String> fieldList,
            @JsonProperty("description") String description,
            @JsonProperty("requires_approval") boolean requiresApproval,
            @JsonProperty("available_to_me") boolean availableToMe) {
    }

    /**
     * 创建导出申请。
     *
     * <p>{@code acknowledged} 必须显式为 true——客户原话「导出需要反复确认」。
     * 放在后端校验而不是只在界面上弹窗，是因为界面上的确认框绕得过去，接口调用绕不过去。
     */
    public record CreateExportRequest(
            @JsonProperty("dataset_code") @NotBlank String datasetCode,
            @JsonProperty("filters") JsonNode filters,
            @JsonProperty("reason") @Size(max = 512) String reason,
            @JsonProperty("acknowledged") Boolean acknowledged) {
    }

    public record ApproveExportRequest(
            @JsonProperty("comment") @Size(max = 512) String comment) {
    }

    public record ExportRequestResponse(
            @JsonProperty("export_request_id") long exportRequestId,
            @JsonProperty("request_no") String requestNo,
            @JsonProperty("dataset_code") String datasetCode,
            @JsonProperty("dataset_name") String datasetName,
            @JsonProperty("sensitivity") String sensitivity,
            @JsonProperty("filters") JsonNode filters,
            @JsonProperty("reason") String reason,
            @JsonProperty("requested_by_user_id") Long requestedByUserId,
            @JsonProperty("requested_by_name") String requestedByName,
            @JsonProperty("requested_at") LocalDateTime requestedAt,
            @JsonProperty("approval_status") String approvalStatus,
            @JsonProperty("approved_by_user_id") Long approvedByUserId,
            @JsonProperty("approved_by_name") String approvedByName,
            @JsonProperty("approved_at") LocalDateTime approvedAt,
            @JsonProperty("approval_comment") String approvalComment,
            @JsonProperty("download_count") int downloadCount,
            @JsonProperty("last_downloaded_at") LocalDateTime lastDownloadedAt,
            @JsonProperty("downloadable") boolean downloadable) {
    }

    /** 留痕：操作人、时间、导出范围、行数、字段清单——客户点名的五项。 */
    public record ExportAuditResponse(
            @JsonProperty("export_audit_id") long exportAuditId,
            @JsonProperty("export_request_id") long exportRequestId,
            @JsonProperty("request_no") String requestNo,
            @JsonProperty("dataset_code") String datasetCode,
            @JsonProperty("dataset_name") String datasetName,
            @JsonProperty("sensitivity") String sensitivity,
            @JsonProperty("operator_user_id") long operatorUserId,
            @JsonProperty("operator_name") String operatorName,
            @JsonProperty("exported_at") LocalDateTime exportedAt,
            @JsonProperty("filters") JsonNode filters,
            @JsonProperty("row_count") int rowCount,
            @JsonProperty("field_list") List<String> fieldList,
            @JsonProperty("approved_by_user_id") Long approvedByUserId,
            @JsonProperty("approved_by_name") String approvedByName) {
    }
}
