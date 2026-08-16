package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ClinicManagementResponse(
        ClinicResponse clinic,
        @JsonProperty("invoice_profile") InvoiceProfile invoiceProfile,
        List<ShippingAddress> addresses,
        List<DoctorContact> doctors,
        List<BusinessDocument> documents,
        List<ProductPrice> prices,
        Map<String, Object> preferences,
        @JsonProperty("available_templates") List<PrintTemplate> availableTemplates,
        @JsonProperty("template_bindings") List<TemplateBinding> templateBindings,
        BlacklistStatus blacklist,
        @JsonProperty("change_logs") List<ChangeLog> changeLogs) {

    public record InvoiceProfile(
            @JsonProperty("invoice_title") String invoiceTitle,
            @JsonProperty("tax_number") String taxNumber,
            @JsonProperty("bank_name") String bankName,
            @JsonProperty("bank_account") String bankAccount,
            @JsonProperty("registered_address") String registeredAddress,
            @JsonProperty("registered_phone") String registeredPhone) {}

    public record ShippingAddress(
            @JsonProperty("address_id") Long addressId,
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

    public record DoctorContact(
            @JsonProperty("doctor_contact_id") Long doctorContactId,
            @JsonProperty("doctor_name") String doctorName,
            String phone,
            String email,
            @JsonProperty("position_title") String positionTitle,
            @JsonProperty("primary_flag") boolean primaryFlag,
            String notes,
            String status) {}

    public record BusinessDocument(
            @JsonProperty("document_id") Long documentId,
            @JsonProperty("document_category") String documentCategory,
            @JsonProperty("document_name") String documentName,
            @JsonProperty("document_no") String documentNo,
            @JsonProperty("valid_from") LocalDate validFrom,
            @JsonProperty("valid_until") LocalDate validUntil,
            @JsonProperty("file_id") Long fileId,
            String status,
            String notes) {}

    public record ProductPrice(
            @JsonProperty("product_id") long productId,
            @JsonProperty("product_type") String productType,
            @JsonProperty("product_name") String productName,
            @JsonProperty("material_spec") String materialSpec,
            @JsonProperty("base_price_cents") long basePriceCents,
            @JsonProperty("custom_price_cents") Long customPriceCents,
            String currency,
            @JsonProperty("effective_from") LocalDate effectiveFrom,
            @JsonProperty("effective_until") LocalDate effectiveUntil,
            String status,
            @JsonProperty("price_note") String priceNote) {}

    public record PrintTemplate(
            @JsonProperty("template_id") long templateId,
            @JsonProperty("template_code") String templateCode,
            @JsonProperty("template_name") String templateName,
            @JsonProperty("document_type") String documentType,
            @JsonProperty("layout_style") String layoutStyle,
            String description,
            int version) {}

    public record TemplateBinding(
            @JsonProperty("document_type") String documentType,
            @JsonProperty("template_id") long templateId,
            @JsonProperty("template_name") String templateName,
            @JsonProperty("layout_style") String layoutStyle,
            int version) {}

    public record BlacklistStatus(
            boolean active,
            String reason,
            @JsonProperty("overdue_amount_cents") long overdueAmountCents,
            @JsonProperty("effective_at") LocalDateTime effectiveAt,
            @JsonProperty("created_by_user_id") Long createdByUserId,
            @JsonProperty("released_at") LocalDateTime releasedAt,
            @JsonProperty("release_reason") String releaseReason) {}

    public record ChangeLog(
            @JsonProperty("change_log_id") long changeLogId,
            @JsonProperty("change_type") String changeType,
            @JsonProperty("change_summary") String changeSummary,
            @JsonProperty("operator_user_id") Long operatorUserId,
            @JsonProperty("created_at") LocalDateTime createdAt) {}
}
