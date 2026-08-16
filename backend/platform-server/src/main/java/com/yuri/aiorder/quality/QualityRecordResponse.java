package com.yuri.aiorder.quality;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record QualityRecordResponse(
        @JsonProperty("quality_record_id") long qualityRecordId,
        @JsonProperty("quality_record_type") String qualityRecordType,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("product_type") String productType,
        @JsonProperty("clinic_name") String clinicName,
        @JsonProperty("check_id") long checkId,
        @JsonProperty("check_result") String checkResult,
        @JsonProperty("rework_id") Long reworkId,
        @JsonProperty("reason_category") String reasonCategory,
        @JsonProperty("reason_detail") String reasonDetail,
        @JsonProperty("responsibility_type") String responsibilityType,
        String status,
        @JsonProperty("status_note") String statusNote,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("status_updated_at") LocalDateTime statusUpdatedAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {}
