package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI-7 智能推荐产品。
 *
 * <p>推荐只从当前生效的产品目录版本中选取，并附带可核对的依据（诊所历史下单分布）。
 * 结果是下单向导中的建议项，医生必须显式选择才生效，系统不会自动填表。
 */
public record AiProductRecommendationResponse(
        List<Recommendation> recommendations,
        @JsonProperty("clinic_history") List<ClinicHistoryItem> clinicHistory,
        @JsonProperty("catalog_version_id") Long catalogVersionId,
        String note,
        @JsonProperty("source_note") String sourceNote) {

    public record Recommendation(
            @JsonProperty("product_id") long productId,
            @JsonProperty("product_code") String productCode,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("category_name") String categoryName,
            @JsonProperty("workflow_product_type") String workflowProductType,
            @JsonProperty("pricing_status") String pricingStatus,
            String reason) {
    }

    public record ClinicHistoryItem(
            @JsonProperty("product_type") String productType,
            @JsonProperty("order_count") long orderCount) {
    }
}
