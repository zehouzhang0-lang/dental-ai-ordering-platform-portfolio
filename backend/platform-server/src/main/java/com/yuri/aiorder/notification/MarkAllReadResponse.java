package com.yuri.aiorder.notification;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MarkAllReadResponse(@JsonProperty("updated_count") int updatedCount) {
}
