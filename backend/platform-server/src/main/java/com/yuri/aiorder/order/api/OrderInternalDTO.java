package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 客服 / 生产 / 管理端看到的订单。
 *
 * <p>{@code delivery_*} 六个字段来自 TASK-034 F 批次的交期引擎：
 * {@code delivery_alert} 非空即客户要求的「时间异常提示」；
 * {@code delivery_estimate_status = PLACEHOLDER} 表示交期用了客户尚未确认的占位周期，界面须标「待确认」。
 * 明细（各项天数构成、过程确认、试戴、计价项）走 {@code GET /orders/{id}/delivery-plan}。
 */
public record OrderInternalDTO(
        @JsonProperty("order_id") long orderId,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("clinic_id") long clinicId,
        @JsonProperty("clinic_name") String clinicName,
        @JsonProperty("doctor_user_id") Long doctorUserId,
        @JsonProperty("doctor_name") String doctorName,
        @JsonProperty("patient_id") Long patientId,
        @JsonProperty("patient_name") String patientName,
        @JsonProperty("cs_user_id") Long csUserId,
        @JsonProperty("product_type") String productType,
        @JsonProperty("internal_status") String internalStatus,
        @JsonProperty("external_status") String externalStatus,
        @JsonProperty("production_note") String productionNote,
        @JsonProperty("reject_reason") String rejectReason,
        @JsonProperty("form_schema_snapshot") JsonNode formSchemaSnapshot,
        @JsonProperty("form_data") JsonNode formData,
        @JsonProperty("promised_delivery_date") LocalDate promisedDeliveryDate,
        @JsonProperty("doctor_requested_delivery_date") LocalDate doctorRequestedDeliveryDate,
        @JsonProperty("delivery_variance_days") Integer deliveryVarianceDays,
        @JsonProperty("delivery_alert") String deliveryAlert,
        @JsonProperty("delivery_alert_message") String deliveryAlertMessage,
        @JsonProperty("delivery_estimate_status") String deliveryEstimateStatus,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {
}
