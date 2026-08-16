package com.yuri.aiorder.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** TASK-034 D 批次：账号交接与人员转移的请求与响应模型。 */
public final class AccountHandoverModels {

    private AccountHandoverModels() {
    }

    public record HandoverRequest(
            @JsonProperty("successor_user_id") @NotNull Long successorUserId,
            @JsonProperty("reason") @Size(max = 512) String reason,
            @JsonProperty("disable_source_account") Boolean disableSourceAccount,
            @JsonProperty("acknowledged") Boolean acknowledged) {
    }

    /** 预览：执行前先看清楚会转走什么，转多少。 */
    public record HandoverPreviewResponse(
            @JsonProperty("from_user_id") long fromUserId,
            @JsonProperty("from_user_name") String fromUserName,
            @JsonProperty("to_user_id") long toUserId,
            @JsonProperty("to_user_name") String toUserName,
            @JsonProperty("portal_role") String portalRole,
            @JsonProperty("total_object_count") int totalObjectCount,
            @JsonProperty("items") List<HandoverItemResponse> items,
            @JsonProperty("historical_records_kept") List<String> historicalRecordsKept) {
    }

    public record HandoverItemResponse(
            @JsonProperty("object_type") String objectType,
            @JsonProperty("object_label") String objectLabel,
            @JsonProperty("target_table") String targetTable,
            @JsonProperty("target_column") String targetColumn,
            @JsonProperty("affected_count") int affectedCount,
            @JsonProperty("object_ids") List<Long> objectIds) {
    }

    public record HandoverResponse(
            @JsonProperty("handover_id") long handoverId,
            @JsonProperty("handover_no") String handoverNo,
            @JsonProperty("from_user_id") long fromUserId,
            @JsonProperty("from_user_name") String fromUserName,
            @JsonProperty("to_user_id") long toUserId,
            @JsonProperty("to_user_name") String toUserName,
            @JsonProperty("operator_user_id") long operatorUserId,
            @JsonProperty("operator_name") String operatorName,
            @JsonProperty("reason") String reason,
            @JsonProperty("source_disabled") boolean sourceDisabled,
            @JsonProperty("transferred_object_count") int transferredObjectCount,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("items") List<HandoverItemResponse> items) {
    }
}
