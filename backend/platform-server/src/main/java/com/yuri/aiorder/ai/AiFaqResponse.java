package com.yuri.aiorder.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * AI-6 牙科 FAQ 应答。
 *
 * <p>回答只允许基于 {@code matched_entries} 中给出的知识条目，命中不到时返回引导联系客服的兜底话术。
 * {@code requires_customer_confirmation} 为 true 表示引用的条目仍是项目方拟定的示例语料（CP-013），
 * 界面必须显式标注「示例内容，待甲方确认」。
 */
public record AiFaqResponse(
        String answer,
        @JsonProperty("matched_entries") List<MatchedEntry> matchedEntries,
        @JsonProperty("result_status") String resultStatus,
        @JsonProperty("requires_customer_confirmation") boolean requiresCustomerConfirmation,
        @JsonProperty("source_note") String sourceNote) {

    public record MatchedEntry(
            @JsonProperty("faq_id") long faqId,
            String category,
            String question,
            @JsonProperty("source_note") String sourceNote) {
    }
}
