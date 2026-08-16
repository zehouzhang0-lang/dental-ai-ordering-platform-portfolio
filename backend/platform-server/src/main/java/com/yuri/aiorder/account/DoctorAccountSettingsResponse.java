package com.yuri.aiorder.account;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoctorAccountSettingsResponse(
        @JsonProperty("user_id") long userId,
        String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("contact_email") String contactEmail,
        @JsonProperty("contact_phone") String contactPhone,
        @JsonProperty("shipping_address") String shippingAddress,
        @JsonProperty("notification_push_enabled") boolean notificationPushEnabled) {
}
