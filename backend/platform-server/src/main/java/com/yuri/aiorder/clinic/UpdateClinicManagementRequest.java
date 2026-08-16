package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record UpdateClinicManagementRequest(
        @JsonProperty("clinic_code") String clinicCode,
        @JsonProperty("clinic_name") String clinicName,
        @JsonProperty("contact_name") String contactName,
        @JsonProperty("contact_phone") String contactPhone,
        @JsonProperty("contact_email") String contactEmail,
        @JsonProperty("business_region") String businessRegion,
        String salesperson,
        @JsonProperty("customer_type") String customerType,
        @JsonProperty("settlement_type") String settlementType,
        @JsonProperty("organization_nature") String organizationNature,
        @JsonProperty("business_level") String businessLevel,
        @JsonProperty("default_shipping_method") String defaultShippingMethod,
        String status,
        @JsonProperty("invoice_profile") InvoiceProfileInput invoiceProfile,
        List<ShippingAddressInput> addresses,
        List<DoctorContactInput> doctors,
        List<BusinessDocumentInput> documents,
        List<ProductPriceInput> prices,
        Map<String, Object> preferences,
        @JsonProperty("template_bindings") List<TemplateBindingInput> templateBindings) {

    public record InvoiceProfileInput(
            @JsonProperty("invoice_title") String invoiceTitle,
            @JsonProperty("tax_number") String taxNumber,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("bank_account") String bankAccount,
            @JsonProperty("registered_address") String registeredAddress,
            @JsonProperty("registered_phone") String registeredPhone) {}

    public record ShippingAddressInput(
            @JsonProperty("address_label") String addressLabel,
            @JsonProperty("recipient_name") String recipientName,
            @JsonProperty("recipient_phone") String recipientPhone,
            String province,
            String city,
            String district,
            @JsonProperty("detail_address") String detailAddress,
            @JsonProperty("shipping_method") String shippingMethod,
            @JsonProperty("default_flag") boolean defaultFlag,
            String status) {}

    public record DoctorContactInput(
            @JsonProperty("doctor_name") String doctorName,
            String phone,
            String email,
            @JsonProperty("position_title") String positionTitle,
            @JsonProperty("primary_flag") boolean primaryFlag,
            String notes,
            String status) {}

    public record BusinessDocumentInput(
            @JsonProperty("document_category") String documentCategory,
            @JsonProperty("document_name") String documentName,
            @JsonProperty("document_no") String documentNo,
            @JsonProperty("valid_from") LocalDate validFrom,
            @JsonProperty("valid_until") LocalDate validUntil,
            @JsonProperty("file_id") Long fileId,
            String status,
            String notes) {}

    public record ProductPriceInput(
            @JsonProperty("product_id") long productId,
            @JsonProperty("custom_price_cents") Long customPriceCents,
            String currency,
            @JsonProperty("effective_from") LocalDate effectiveFrom,
            @JsonProperty("effective_until") LocalDate effectiveUntil,
            String status,
            @JsonProperty("price_note") String priceNote) {}

    public record TemplateBindingInput(
            @JsonProperty("document_type") String documentType,
            @JsonProperty("template_id") long templateId) {}
}
