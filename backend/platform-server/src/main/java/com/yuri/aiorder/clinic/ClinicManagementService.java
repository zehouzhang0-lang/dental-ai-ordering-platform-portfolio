package com.yuri.aiorder.clinic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.clinic.ClinicManagementResponse.BlacklistStatus;
import com.yuri.aiorder.clinic.ClinicManagementResponse.BusinessDocument;
import com.yuri.aiorder.clinic.ClinicManagementResponse.ChangeLog;
import com.yuri.aiorder.clinic.ClinicManagementResponse.DoctorContact;
import com.yuri.aiorder.clinic.ClinicManagementResponse.InvoiceProfile;
import com.yuri.aiorder.clinic.ClinicManagementResponse.PrintTemplate;
import com.yuri.aiorder.clinic.ClinicManagementResponse.ProductPrice;
import com.yuri.aiorder.clinic.ClinicManagementResponse.ShippingAddress;
import com.yuri.aiorder.clinic.ClinicManagementResponse.TemplateBinding;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClinicManagementService {

    private static final Set<String> ALLOWED_PREFERENCE_KEYS = Set.of(
            "color", "contact", "occlusion", "margin", "shape", "material", "note");
    private static final Set<String> ALLOWED_CLINIC_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> ALLOWED_DOCUMENT_CATEGORIES = Set.of("QUALIFICATION", "CONTRACT");
    private static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "ORDER_SHEET", "PRODUCTION_WORK_ORDER", "DELIVERY_NOTE", "STATEMENT");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final ClinicService clinicService;

    public ClinicManagementService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            ClinicService clinicService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.clinicService = clinicService;
    }

    public ClinicManagementResponse getManagement(long clinicId, BootstrapIdentity identity) {
        requireManage(identity);
        ClinicResponse clinic = clinicService.getClinic(clinicId, identity);
        return new ClinicManagementResponse(
                clinic,
                loadInvoiceProfile(clinicId),
                loadAddresses(clinicId),
                loadDoctors(clinicId),
                loadDocuments(clinicId),
                loadPrices(clinicId),
                loadPreferences(clinicId),
                loadTemplates(),
                loadTemplateBindings(clinicId),
                loadBlacklist(clinicId),
                loadChangeLogs(clinicId));
    }

    @Transactional
    public ClinicManagementResponse updateManagement(
            long clinicId, UpdateClinicManagementRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        clinicService.getClinic(clinicId, identity);
        validateManagementRequest(request);
        try {
            jdbcClient.sql("""
                            UPDATE clinic
                            SET clinic_code = :clinicCode,
                                clinic_name = :clinicName,
                                contact_name = :contactName,
                                contact_phone = :contactPhone,
                                contact_email = :contactEmail,
                                business_region = :businessRegion,
                                salesperson = :salesperson,
                                customer_type = :customerType,
                                settlement_type = :settlementType,
                                organization_nature = :organizationNature,
                                business_level = :businessLevel,
                                default_shipping_method = :defaultShippingMethod,
                                status = :status
                            WHERE clinic_id = :clinicId
                            """)
                    .param("clinicCode", normalizeClinicCode(request.clinicCode()))
                    .param("clinicName", required(request.clinicName(), "clinic_name is required"))
                    .param("contactName", nullable(request.contactName()))
                    .param("contactPhone", nullable(request.contactPhone()))
                    .param("contactEmail", nullable(request.contactEmail()))
                    .param("businessRegion", nullable(request.businessRegion()))
                    .param("salesperson", nullable(request.salesperson()))
                    .param("customerType", nullable(request.customerType()))
                    .param("settlementType", nullable(request.settlementType()))
                    .param("organizationNature", nullable(request.organizationNature()))
                    .param("businessLevel", nullable(request.businessLevel()))
                    .param("defaultShippingMethod", nullable(request.defaultShippingMethod()))
                    .param("status", normalizeStatus(request.status()))
                    .param("clinicId", clinicId)
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clinic code or name already exists", ex);
        }

        replaceInvoiceProfile(clinicId, request.invoiceProfile());
        replaceAddresses(clinicId, request.addresses());
        replaceDoctors(clinicId, request.doctors());
        replaceDocuments(clinicId, request.documents());
        replacePrices(clinicId, request.prices(), identity.userId());
        replacePreferences(clinicId, request.preferences());
        replaceTemplateBindings(clinicId, request.templateBindings(), identity.userId());
        appendChangeLog(clinicId, "PROFILE_UPDATED", "客户档案、商务资料和关联设置已更新", identity.userId());
        return getManagement(clinicId, identity);
    }

    @Transactional
    public ClinicManagementResponse blacklist(
            long clinicId, BlacklistClinicRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        clinicService.getClinic(clinicId, identity);
        boolean alreadyActive = jdbcClient.sql("""
                        SELECT COUNT(*) FROM clinic_blacklist_record
                        WHERE clinic_id = :clinicId AND blacklist_status = 'ACTIVE'
                        """)
                .param("clinicId", clinicId)
                .query(Long.class)
                .single() > 0;
        if (alreadyActive) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clinic is already blacklisted");
        }
        long overdueAmount = request.overdueAmountCents() == null ? 0 : request.overdueAmountCents();
        if (overdueAmount < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "overdue_amount_cents cannot be negative");
        }
        jdbcClient.sql("""
                        INSERT INTO clinic_blacklist_record
                            (clinic_id, blacklist_status, reason, overdue_amount_cents, created_by_user_id)
                        VALUES (:clinicId, 'ACTIVE', :reason, :overdueAmount, :userId)
                        """)
                .param("clinicId", clinicId)
                .param("reason", required(request.reason(), "blacklist reason is required"))
                .param("overdueAmount", overdueAmount)
                .param("userId", identity.userId())
                .update();
        appendChangeLog(clinicId, "BLACKLISTED", "客户已加入黑名单，新建与提交订单已禁止", identity.userId());
        return getManagement(clinicId, identity);
    }

    @Transactional
    public ClinicManagementResponse releaseBlacklist(
            long clinicId, ReleaseClinicBlacklistRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        clinicService.getClinic(clinicId, identity);
        int updated = jdbcClient.sql("""
                        UPDATE clinic_blacklist_record
                        SET blacklist_status = 'RELEASED',
                            released_at = CURRENT_TIMESTAMP(3),
                            released_by_user_id = :userId,
                            release_reason = :releaseReason
                        WHERE clinic_id = :clinicId
                          AND blacklist_status = 'ACTIVE'
                        """)
                .param("clinicId", clinicId)
                .param("userId", identity.userId())
                .param("releaseReason", required(request.releaseReason(), "release_reason is required"))
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "clinic is not blacklisted");
        }
        appendChangeLog(clinicId, "BLACKLIST_RELEASED", "客户黑名单已解除，下单权限已恢复", identity.userId());
        return getManagement(clinicId, identity);
    }

    private InvoiceProfile loadInvoiceProfile(long clinicId) {
        return jdbcClient.sql("""
                        SELECT invoice_title, tax_number, bank_name, bank_account,
                               registered_address, registered_phone
                        FROM clinic_invoice_profile
                        WHERE clinic_id = :clinicId
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new InvoiceProfile(
                        rs.getString("invoice_title"),
                        rs.getString("tax_number"),
                        rs.getString("bank_name"),
                        rs.getString("bank_account"),
                        rs.getString("registered_address"),
                        rs.getString("registered_phone")))
                .optional()
                .orElse(new InvoiceProfile(null, null, null, null, null, null));
    }

    private List<ShippingAddress> loadAddresses(long clinicId) {
        return jdbcClient.sql("""
                        SELECT address_id, address_label, recipient_name, recipient_phone,
                               province, city, district, detail_address, shipping_method,
                               default_flag, status
                        FROM clinic_shipping_address
                        WHERE clinic_id = :clinicId
                        ORDER BY default_flag DESC, address_id
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new ShippingAddress(
                        rs.getLong("address_id"), rs.getString("address_label"),
                        rs.getString("recipient_name"), rs.getString("recipient_phone"),
                        rs.getString("province"), rs.getString("city"), rs.getString("district"),
                        rs.getString("detail_address"), rs.getString("shipping_method"),
                        rs.getBoolean("default_flag"), rs.getString("status")))
                .list();
    }

    private List<DoctorContact> loadDoctors(long clinicId) {
        return jdbcClient.sql("""
                        SELECT doctor_contact_id, doctor_name, phone, email, position_title,
                               primary_flag, notes, status
                        FROM clinic_doctor_contact
                        WHERE clinic_id = :clinicId
                        ORDER BY primary_flag DESC, doctor_contact_id
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new DoctorContact(
                        rs.getLong("doctor_contact_id"), rs.getString("doctor_name"),
                        rs.getString("phone"), rs.getString("email"), rs.getString("position_title"),
                        rs.getBoolean("primary_flag"), rs.getString("notes"), rs.getString("status")))
                .list();
    }

    private List<BusinessDocument> loadDocuments(long clinicId) {
        return jdbcClient.sql("""
                        SELECT document_id, document_category, document_name, document_no,
                               valid_from, valid_until, file_id, status, notes
                        FROM clinic_business_document
                        WHERE clinic_id = :clinicId
                        ORDER BY document_category, valid_until, document_id
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new BusinessDocument(
                        rs.getLong("document_id"), rs.getString("document_category"),
                        rs.getString("document_name"), rs.getString("document_no"),
                        rs.getObject("valid_from", java.time.LocalDate.class),
                        rs.getObject("valid_until", java.time.LocalDate.class),
                        rs.getObject("file_id", Long.class), rs.getString("status"), rs.getString("notes")))
                .list();
    }

    private List<ProductPrice> loadPrices(long clinicId) {
        return jdbcClient.sql("""
                        SELECT p.product_id, p.product_type, p.product_name, p.material_spec,
                               p.base_price_cents, cpp.price_cents AS custom_price_cents,
                               COALESCE(cpp.currency, p.currency) AS currency,
                               cpp.effective_from, cpp.effective_until,
                               COALESCE(cpp.status, 'INHERITED') AS price_status,
                               cpp.price_note
                        FROM product_catalog p
                        LEFT JOIN clinic_product_price cpp
                          ON cpp.product_id = p.product_id
                         AND cpp.clinic_id = :clinicId
                        WHERE p.status = 'ACTIVE'
                        ORDER BY p.product_name, p.product_id
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new ProductPrice(
                        rs.getLong("product_id"), rs.getString("product_type"), rs.getString("product_name"),
                        rs.getString("material_spec"), rs.getLong("base_price_cents"),
                        rs.getObject("custom_price_cents", Long.class), rs.getString("currency"),
                        rs.getObject("effective_from", java.time.LocalDate.class),
                        rs.getObject("effective_until", java.time.LocalDate.class),
                        rs.getString("price_status"), rs.getString("price_note")))
                .list();
    }

    private Map<String, Object> loadPreferences(long clinicId) {
        Map<String, Object> result = new LinkedHashMap<>();
        ALLOWED_PREFERENCE_KEYS.forEach(key -> result.put(key, null));
        jdbcClient.sql("""
                        SELECT preference_key, CAST(preference_value AS CHAR) AS preference_value
                        FROM customer_preference
                        WHERE clinic_id = :clinicId
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> {
                    result.put(rs.getString("preference_key"), readJson(rs.getString("preference_value")));
                    return 1;
                })
                .list();
        return result;
    }

    private List<PrintTemplate> loadTemplates() {
        return jdbcClient.sql("""
                        SELECT template_id, template_code, template_name, document_type,
                               layout_style, description, version
                        FROM customer_print_template
                        WHERE status = 'ACTIVE'
                        ORDER BY document_type, layout_style DESC, template_id
                        """)
                .query((rs, rowNum) -> new PrintTemplate(
                        rs.getLong("template_id"), rs.getString("template_code"),
                        rs.getString("template_name"), rs.getString("document_type"),
                        rs.getString("layout_style"), rs.getString("description"), rs.getInt("version")))
                .list();
    }

    private List<TemplateBinding> loadTemplateBindings(long clinicId) {
        return jdbcClient.sql("""
                        SELECT binding.document_type, template.template_id, template.template_name,
                               template.layout_style, template.version
                        FROM clinic_print_template_binding binding
                        JOIN customer_print_template template ON template.template_id = binding.template_id
                        WHERE binding.clinic_id = :clinicId
                        ORDER BY binding.document_type
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new TemplateBinding(
                        rs.getString("document_type"), rs.getLong("template_id"),
                        rs.getString("template_name"), rs.getString("layout_style"), rs.getInt("version")))
                .list();
    }

    private BlacklistStatus loadBlacklist(long clinicId) {
        return jdbcClient.sql("""
                        SELECT blacklist_status, reason, overdue_amount_cents, effective_at,
                               created_by_user_id, released_at, release_reason
                        FROM clinic_blacklist_record
                        WHERE clinic_id = :clinicId
                        ORDER BY (blacklist_status = 'ACTIVE') DESC, effective_at DESC, blacklist_record_id DESC
                        LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new BlacklistStatus(
                        "ACTIVE".equals(rs.getString("blacklist_status")), rs.getString("reason"),
                        rs.getLong("overdue_amount_cents"),
                        rs.getObject("effective_at", LocalDateTime.class),
                        rs.getObject("created_by_user_id", Long.class),
                        rs.getObject("released_at", LocalDateTime.class), rs.getString("release_reason")))
                .optional()
                .orElse(new BlacklistStatus(false, null, 0, null, null, null, null));
    }

    private List<ChangeLog> loadChangeLogs(long clinicId) {
        return jdbcClient.sql("""
                        SELECT change_log_id, change_type, change_summary, operator_user_id, created_at
                        FROM clinic_change_log
                        WHERE clinic_id = :clinicId
                        ORDER BY created_at DESC, change_log_id DESC
                        LIMIT 30
                        """)
                .param("clinicId", clinicId)
                .query((rs, rowNum) -> new ChangeLog(
                        rs.getLong("change_log_id"), rs.getString("change_type"),
                        rs.getString("change_summary"), rs.getObject("operator_user_id", Long.class),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    private void replaceInvoiceProfile(long clinicId, UpdateClinicManagementRequest.InvoiceProfileInput input) {
        if (input == null) {
            jdbcClient.sql("DELETE FROM clinic_invoice_profile WHERE clinic_id = :clinicId")
                    .param("clinicId", clinicId).update();
            return;
        }
        jdbcClient.sql("""
                        INSERT INTO clinic_invoice_profile
                            (clinic_id, invoice_title, tax_number, bank_name, bank_account,
                             registered_address, registered_phone)
                        VALUES
                            (:clinicId, :invoiceTitle, :taxNumber, :bankName, :bankAccount,
                             :registeredAddress, :registeredPhone)
                        ON DUPLICATE KEY UPDATE
                            invoice_title = VALUES(invoice_title), tax_number = VALUES(tax_number),
                            bank_name = VALUES(bank_name), bank_account = VALUES(bank_account),
                            registered_address = VALUES(registered_address),
                            registered_phone = VALUES(registered_phone)
                        """)
                .param("clinicId", clinicId)
                .param("invoiceTitle", nullable(input.invoiceTitle()))
                .param("taxNumber", nullable(input.taxNumber()))
                .param("bankName", nullable(input.bankName()))
                .param("bankAccount", nullable(input.bankAccount()))
                .param("registeredAddress", nullable(input.registeredAddress()))
                .param("registeredPhone", nullable(input.registeredPhone()))
                .update();
    }

    private void replaceAddresses(long clinicId, List<UpdateClinicManagementRequest.ShippingAddressInput> inputs) {
        jdbcClient.sql("DELETE FROM clinic_shipping_address WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId).update();
        if (inputs == null) return;
        for (var input : inputs) {
            jdbcClient.sql("""
                            INSERT INTO clinic_shipping_address
                                (clinic_id, address_label, recipient_name, recipient_phone, province, city,
                                 district, detail_address, shipping_method, default_flag, status)
                            VALUES
                                (:clinicId, :addressLabel, :recipientName, :recipientPhone, :province, :city,
                                 :district, :detailAddress, :shippingMethod, :defaultFlag, :status)
                            """)
                    .param("clinicId", clinicId).param("addressLabel", nullable(input.addressLabel()))
                    .param("recipientName", required(input.recipientName(), "address recipient_name is required"))
                    .param("recipientPhone", required(input.recipientPhone(), "address recipient_phone is required"))
                    .param("province", nullable(input.province())).param("city", nullable(input.city()))
                    .param("district", nullable(input.district()))
                    .param("detailAddress", required(input.detailAddress(), "detail_address is required"))
                    .param("shippingMethod", nullable(input.shippingMethod()))
                    .param("defaultFlag", input.defaultFlag()).param("status", normalizeChildStatus(input.status()))
                    .update();
        }
    }

    private void replaceDoctors(long clinicId, List<UpdateClinicManagementRequest.DoctorContactInput> inputs) {
        jdbcClient.sql("DELETE FROM clinic_doctor_contact WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId).update();
        if (inputs == null) return;
        for (var input : inputs) {
            jdbcClient.sql("""
                            INSERT INTO clinic_doctor_contact
                                (clinic_id, doctor_name, phone, email, position_title, primary_flag, notes, status)
                            VALUES
                                (:clinicId, :doctorName, :phone, :email, :positionTitle, :primaryFlag, :notes, :status)
                            """)
                    .param("clinicId", clinicId)
                    .param("doctorName", required(input.doctorName(), "doctor_name is required"))
                    .param("phone", nullable(input.phone())).param("email", nullable(input.email()))
                    .param("positionTitle", nullable(input.positionTitle()))
                    .param("primaryFlag", input.primaryFlag()).param("notes", nullable(input.notes()))
                    .param("status", normalizeChildStatus(input.status())).update();
        }
    }

    private void replaceDocuments(long clinicId, List<UpdateClinicManagementRequest.BusinessDocumentInput> inputs) {
        jdbcClient.sql("DELETE FROM clinic_business_document WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId).update();
        if (inputs == null) return;
        for (var input : inputs) {
            String category = required(input.documentCategory(), "document_category is required").toUpperCase(Locale.ROOT);
            if (!ALLOWED_DOCUMENT_CATEGORIES.contains(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported document_category");
            }
            validateDocumentFile(input.fileId());
            jdbcClient.sql("""
                            INSERT INTO clinic_business_document
                                (clinic_id, document_category, document_name, document_no, valid_from,
                                 valid_until, file_id, status, notes)
                            VALUES
                                (:clinicId, :category, :documentName, :documentNo, :validFrom,
                                 :validUntil, :fileId, :status, :notes)
                            """)
                    .param("clinicId", clinicId).param("category", category)
                    .param("documentName", required(input.documentName(), "document_name is required"))
                    .param("documentNo", nullable(input.documentNo())).param("validFrom", input.validFrom())
                    .param("validUntil", input.validUntil()).param("fileId", input.fileId())
                    .param("status", normalizeChildStatus(input.status())).param("notes", nullable(input.notes()))
                    .update();
        }
    }

    private void replacePrices(
            long clinicId, List<UpdateClinicManagementRequest.ProductPriceInput> inputs, Long userId) {
        jdbcClient.sql("DELETE FROM clinic_product_price WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId).update();
        if (inputs == null) return;
        for (var input : inputs) {
            if (input.customPriceCents() == null) continue;
            if (input.customPriceCents() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "custom_price_cents must be positive");
            }
            if (input.effectiveFrom() != null && input.effectiveUntil() != null
                    && input.effectiveUntil().isBefore(input.effectiveFrom())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "price effective_until cannot precede effective_from");
            }
            jdbcClient.sql("""
                            INSERT INTO clinic_product_price
                                (clinic_id, product_id, price_cents, currency, effective_from, effective_until,
                                 status, price_note, created_by_user_id)
                            VALUES
                                (:clinicId, :productId, :priceCents, :currency, :effectiveFrom, :effectiveUntil,
                                 :status, :priceNote, :userId)
                            """)
                    .param("clinicId", clinicId).param("productId", input.productId())
                    .param("priceCents", input.customPriceCents())
                    .param("currency", normalizeCurrency(input.currency()))
                    .param("effectiveFrom", input.effectiveFrom()).param("effectiveUntil", input.effectiveUntil())
                    .param("status", normalizeChildStatus(input.status())).param("priceNote", nullable(input.priceNote()))
                    .param("userId", userId).update();
        }
    }

    private void replacePreferences(long clinicId, Map<String, Object> preferences) {
        if (preferences == null) return;
        for (String key : preferences.keySet()) {
            if (!ALLOWED_PREFERENCE_KEYS.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported preference key: " + key);
            }
        }
        for (String key : ALLOWED_PREFERENCE_KEYS) {
            Object value = preferences.get(key);
            if (value == null || (value instanceof String string && string.isBlank())) {
                jdbcClient.sql("DELETE FROM customer_preference WHERE clinic_id = :clinicId AND preference_key = :key")
                        .param("clinicId", clinicId).param("key", key).update();
            } else {
                jdbcClient.sql("""
                                INSERT INTO customer_preference (clinic_id, preference_key, preference_value)
                                VALUES (:clinicId, :key, CAST(:value AS JSON))
                                ON DUPLICATE KEY UPDATE preference_value = VALUES(preference_value),
                                    updated_at = CURRENT_TIMESTAMP(3)
                                """)
                        .param("clinicId", clinicId).param("key", key).param("value", writeJson(value)).update();
            }
        }
    }

    private void replaceTemplateBindings(
            long clinicId, List<UpdateClinicManagementRequest.TemplateBindingInput> inputs, Long userId) {
        jdbcClient.sql("DELETE FROM clinic_print_template_binding WHERE clinic_id = :clinicId")
                .param("clinicId", clinicId).update();
        if (inputs == null) return;
        for (var input : inputs) {
            String documentType = required(input.documentType(), "document_type is required").toUpperCase(Locale.ROOT);
            if (!ALLOWED_DOCUMENT_TYPES.contains(documentType)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported document_type");
            }
            boolean matches = jdbcClient.sql("""
                            SELECT COUNT(*) FROM customer_print_template
                            WHERE template_id = :templateId AND document_type = :documentType AND status = 'ACTIVE'
                            """)
                    .param("templateId", input.templateId()).param("documentType", documentType)
                    .query(Long.class).single() > 0;
            if (!matches) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "template does not match document_type");
            }
            jdbcClient.sql("""
                            INSERT INTO clinic_print_template_binding
                                (clinic_id, document_type, template_id, updated_by_user_id)
                            VALUES (:clinicId, :documentType, :templateId, :userId)
                            """)
                    .param("clinicId", clinicId).param("documentType", documentType)
                    .param("templateId", input.templateId()).param("userId", userId).update();
        }
    }

    private void validateManagementRequest(UpdateClinicManagementRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        long defaultAddresses = request.addresses() == null ? 0 : request.addresses().stream()
                .filter(UpdateClinicManagementRequest.ShippingAddressInput::defaultFlag).count();
        if (defaultAddresses > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only one default shipping address is allowed");
        }
        long primaryDoctors = request.doctors() == null ? 0 : request.doctors().stream()
                .filter(UpdateClinicManagementRequest.DoctorContactInput::primaryFlag).count();
        if (primaryDoctors > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only one primary doctor is allowed");
        }
    }

    private void validateDocumentFile(Long fileId) {
        if (fileId == null) return;
        boolean valid = jdbcClient.sql("""
                        SELECT COUNT(*) FROM file_resource
                        WHERE file_id = :fileId AND status = 'ACTIVE' AND upload_status = 'COMPLETED'
                        """)
                .param("fileId", fileId).query(Long.class).single() > 0;
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "document file is not available");
        }
    }

    private void appendChangeLog(long clinicId, String type, String summary, Long userId) {
        jdbcClient.sql("""
                        INSERT INTO clinic_change_log (clinic_id, change_type, change_summary, operator_user_id)
                        VALUES (:clinicId, :type, :summary, :userId)
                        """)
                .param("clinicId", clinicId).param("type", type).param("summary", summary).param("userId", userId)
                .update();
    }

    private void requireManage(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "clinic:manage", "customer management requires clinic:manage");
    }

    private String normalizeClinicCode(String value) {
        String normalized = required(value, "clinic_code is required").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{2,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid clinic_code");
        }
        return normalized;
    }

    private String normalizeStatus(String value) {
        String normalized = value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_CLINIC_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported clinic status");
        }
        return normalized;
    }

    private String normalizeChildStatus(String value) {
        String normalized = value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "INACTIVE", "EXPIRED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported status");
        }
        return normalized;
    }

    private String normalizeCurrency(String value) {
        String normalized = value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,16}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid currency");
        }
        return normalized;
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid preference value", ex);
        }
    }

    private Object readJson(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "invalid stored preference", ex);
        }
    }
}
