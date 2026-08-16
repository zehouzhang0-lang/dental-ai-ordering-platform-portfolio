package com.yuri.aiorder.workflow.execution;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FinalInspectionReportRequest(
        @JsonProperty("order_id") Long orderId,
        String summary,
        @JsonProperty("pdf_file_id") Long pdfFileId,
        @JsonProperty("attachment_file_ids") List<Long> attachmentFileIds) {
}
