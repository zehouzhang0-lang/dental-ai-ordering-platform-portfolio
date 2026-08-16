package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record CreateClinicRequest(
        @JsonProperty("clinic_code") String clinicCode,
        @JsonProperty("clinic_name") @NotBlank String clinicName,
        @JsonProperty("contact_name") String contactName,
        @JsonProperty("contact_phone") String contactPhone,
        @JsonProperty("contact_email") String contactEmail,
        @JsonProperty("business_region") String businessRegion,
        String salesperson,
        @JsonProperty("customer_type") String customerType,
        @JsonProperty("settlement_type") String settlementType,
        @JsonProperty("organization_nature") String organizationNature,
        @JsonProperty("business_level") String businessLevel,
        @JsonProperty("default_shipping_method") String defaultShippingMethod) {}
