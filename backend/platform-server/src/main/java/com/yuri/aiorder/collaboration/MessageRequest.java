package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record MessageRequest(
        String content,
        @JsonProperty("attachment_file_ids") List<Long> attachmentFileIds,
        @JsonProperty("visible_to") String visibleTo,
        @JsonProperty("mention_user_ids") List<Long> mentionUserIds) {
}
