package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ProductionQualitySummaryResponse(
        @JsonProperty("product_type") String productType,
        @JsonProperty("inspected_order_count") long inspectedOrderCount,
        @JsonProperty("total_rework_count") long totalReworkCount,
        @JsonProperty("internal_rework_count") long internalReworkCount,
        @JsonProperty("external_rework_count") long externalReworkCount,
        @JsonProperty("unclassified_rework_count") long unclassifiedReworkCount,
        @JsonProperty("total_rework_rate") double totalReworkRate,
        @JsonProperty("internal_rework_rate") double internalReworkRate,
        @JsonProperty("external_rework_rate") double externalReworkRate,
        @JsonProperty("first_pass_rate") double firstPassRate,
        @JsonProperty("final_pass_rate") double finalPassRate,
        @JsonProperty("complaint_count") long complaintCount,
        // 客诉率口径：客服登记的外返（quality_record.record_type = 'EXTERNAL_RETURN'）÷ 出检订单数。
        // 退货率所依赖的「退货订单」类型尚未建模，返回 null 表示口径未启用，不得用 0 冒充真实值。
        @JsonProperty("complaint_rate") Double complaintRate,
        @JsonProperty("return_rate") Double returnRate,
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        List<TrendPoint> trends,
        @JsonProperty("generated_at") LocalDateTime generatedAt) {

    public record TrendPoint(
            LocalDate date,
            @JsonProperty("inspected_order_count") long inspectedOrderCount,
            @JsonProperty("rework_count") long reworkCount,
            @JsonProperty("first_pass_rate") double firstPassRate,
            @JsonProperty("final_pass_rate") double finalPassRate) {
    }
}
