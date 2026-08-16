package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderCreationService {

    private static final Set<String> DOCTOR_VISIBLE_FILE_VISIBILITIES = Set.of("DOCTOR", "DOCTOR_CS", "ALL");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final OrderStatusService statusService;

    public OrderCreationService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            OrderStatusService statusService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.statusService = statusService;
    }

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "order:write-doctor", "only doctors can create orders");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        ensureClinicCanOrder(identity.clinicId());

        String productType = normalizeProductType(request.productType());
        PriceSnapshot priceSnapshot = resolvePriceSnapshot(identity.clinicId(), productType);
        boolean draft = Boolean.TRUE.equals(request.draft());
        validateFormData(productType, request.formData(), !draft);
        validateOwnedPatient(request.patientId(), identity);
        List<Long> fileIds = normalizedFileIds(request.fileIds());
        List<BindableFile> files = fileIds.stream()
                .map((fileId) -> validateBindableDoctorFile(fileId, identity, null))
                .toList();
        if (!draft) {
            requireSubmittedStl(files);
        }

        String orderNo = nextOrderNo();
        long groupId = createLegacySingleProductGroup(
                orderNo, identity, request.patientId(), draft ? "DRAFT" : "SUBMITTED");
        jdbcClient.sql("""
                        INSERT INTO orders
                            (group_id, line_no, relationship_type, order_no,
                             clinic_id, doctor_user_id, patient_id, product_type,
                             quoted_price_cents, quoted_price_currency, pricing_source, form_data,
                             internal_status, external_status)
                        VALUES
                            (:groupId, 1, 'PRIMARY', :orderNo,
                             :clinicId, :doctorUserId, :patientId, :productType,
                             :quotedPriceCents, :quotedPriceCurrency, :pricingSource, CAST(:formData AS JSON),
                             'DRAFT', 'DRAFT')
                        """)
                .param("groupId", groupId)
                .param("orderNo", orderNo)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .param("patientId", request.patientId())
                .param("productType", productType)
                .param("quotedPriceCents", priceSnapshot.priceCents())
                .param("quotedPriceCurrency", priceSnapshot.currency())
                .param("pricingSource", priceSnapshot.source())
                .param("formData", writeJson(request.formData()))
                .update();
        long orderId = jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();

        bindFilesToOrder(orderId, fileIds, identity.userId());

        if (draft) {
            return new CreateOrderResponse(orderId, orderNo, productType, "DRAFT", request.formData());
        }

        String externalStatus = statusService.updateOrderState(
                        orderId,
                        InternalOrderStatus.PENDING_CS_REVIEW,
                        "DOCTOR_SUBMIT_ORDER",
                        identity.userId(),
                        "doctor submitted order")
                .name();
        return new CreateOrderResponse(orderId, orderNo, productType, externalStatus, request.formData());
    }

    @Transactional
    public CreateOrderResponse updateDoctorOrder(long orderId, UpdateOrderRequest request, BootstrapIdentity identity) {
        accessControlService.requireDoctorPortalAction(
                identity, "order:write-doctor", "only doctors can update orders");
        accessControlService.requireScopedIdentity(identity, "CLINIC");
        ensureClinicCanOrder(identity.clinicId());

        DoctorEditableOrder order = loadDoctorEditableOrder(orderId, identity);
        boolean submit = Boolean.TRUE.equals(request.submit());
        String productType = normalizeProductType(request.productType());
        PriceSnapshot priceSnapshot = resolvePriceSnapshot(identity.clinicId(), productType);
        validateEditableStatus(order.internalStatus(), submit);
        validateFormData(productType, request.formData(), submit);
        validateOwnedPatient(request.patientId(), identity);
        List<Long> fileIds = normalizedFileIds(request.fileIds());
        List<BindableFile> files = fileIds.stream()
                .map((fileId) -> validateBindableDoctorFile(fileId, identity, orderId))
                .toList();
        if (submit) {
            requireSubmittedStl(files);
        }

        jdbcClient.sql("""
                        UPDATE orders
                        SET product_type = :productType,
                            quoted_price_cents = :quotedPriceCents,
                            quoted_price_currency = :quotedPriceCurrency,
                            pricing_source = :pricingSource,
                            patient_id = :patientId,
                            form_data = CAST(:formData AS JSON),
                            reject_reason = CASE WHEN :submit THEN NULL ELSE reject_reason END
                        WHERE order_id = :orderId
                        """)
                .param("productType", productType)
                .param("quotedPriceCents", priceSnapshot.priceCents())
                .param("quotedPriceCurrency", priceSnapshot.currency())
                .param("pricingSource", priceSnapshot.source())
                .param("patientId", request.patientId())
                .param("formData", writeJson(request.formData()))
                .param("submit", submit)
                .param("orderId", orderId)
                .update();
        synchronizeDoctorOrderFiles(orderId, fileIds, identity.userId());

        String externalStatus = order.externalStatus();
        if (submit) {
            String eventType = "DRAFT".equals(order.internalStatus())
                    ? "DOCTOR_SUBMIT_ORDER"
                    : "DOCTOR_RESUBMIT_ORDER";
            externalStatus = statusService.updateOrderState(
                            orderId,
                            InternalOrderStatus.PENDING_CS_REVIEW,
                            eventType,
                            identity.userId(),
                            "doctor submitted order")
                    .name();
            markGroupSubmitted(order.groupId());
        }
        return new CreateOrderResponse(orderId, order.orderNo(), productType, externalStatus, request.formData());
    }

    private void validateFormData(String productType, JsonNode formData, boolean requireRequiredFields) {
        if (formData == null || !formData.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "form_data must be an object");
        }
        List<FormFieldRequirement> fields = jdbcClient.sql("""
                        SELECT field_key, required_flag
                        FROM form_field_config
                        WHERE product_type = :productType
                          AND status = 'ACTIVE'
                        ORDER BY sort_order, field_id
                        """)
                .param("productType", productType)
                .query((rs, rowNum) -> new FormFieldRequirement(
                        rs.getString("field_key"),
                        rs.getInt("required_flag") == 1))
                .list();
        if (fields.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "active form config not found");
        }
        for (FormFieldRequirement field : fields) {
            if (requireRequiredFields && field.required() && isMissing(formData.get(field.fieldKey()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing required field: " + field.fieldKey());
            }
        }
    }

    private void ensureClinicCanOrder(Long clinicId) {
        if (clinicId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor clinic scope is required");
        }
        ClinicOrderGate gate;
        try {
            gate = jdbcClient.sql("""
                            SELECT c.status,
                                   EXISTS (
                                       SELECT 1 FROM clinic_blacklist_record blacklist
                                       WHERE blacklist.clinic_id = c.clinic_id
                                         AND blacklist.blacklist_status = 'ACTIVE'
                                   ) AS blacklisted
                            FROM clinic c
                            WHERE c.clinic_id = :clinicId
                              AND c.status <> 'DELETED'
                            """)
                    .param("clinicId", clinicId)
                    .query((rs, rowNum) -> new ClinicOrderGate(
                            rs.getString("status"), rs.getBoolean("blacklisted")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "clinic is not available", ex);
        }
        if (gate.blacklisted()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "CLINIC_BLACKLISTED: 当前客户已被限制下单，请联系平台客服");
        }
        if (!"ACTIVE".equals(gate.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CLINIC_INACTIVE: 当前客户已停用");
        }
    }

    private PriceSnapshot resolvePriceSnapshot(Long clinicId, String productType) {
        return jdbcClient.sql("""
                        SELECT COALESCE(cpp.price_cents, product.base_price_cents) AS price_cents,
                               COALESCE(cpp.currency, product.currency) AS currency,
                               CASE WHEN cpp.clinic_product_price_id IS NULL
                                    THEN 'BASE_PRICE' ELSE 'CUSTOMER_PRICE' END AS pricing_source
                        FROM product_catalog product
                        LEFT JOIN clinic_product_price cpp
                          ON cpp.product_id = product.product_id
                         AND cpp.clinic_id = :clinicId
                         AND cpp.status = 'ACTIVE'
                         AND (cpp.effective_from IS NULL OR cpp.effective_from <= CURRENT_DATE)
                         AND (cpp.effective_until IS NULL OR cpp.effective_until >= CURRENT_DATE)
                        WHERE product.product_type = :productType
                          AND product.status = 'ACTIVE'
                        """)
                .param("clinicId", clinicId)
                .param("productType", productType)
                .query((rs, rowNum) -> new PriceSnapshot(
                        rs.getObject("price_cents", Long.class),
                        rs.getString("currency"),
                        rs.getString("pricing_source")))
                .optional()
                .orElse(new PriceSnapshot(null, null, null));
    }

    private boolean isMissing(JsonNode value) {
        return value == null
                || value.isNull()
                || (value.isTextual() && value.asText().isBlank())
                || (value.isArray() && value.isEmpty());
    }

    private List<Long> normalizedFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(fileIds).stream().sorted().toList();
    }

    private BindableFile validateBindableDoctorFile(long fileId, BootstrapIdentity identity, Long targetOrderId) {
        try {
            BindableFile file = jdbcClient.sql("""
                            SELECT file_id, order_id, owner_user_id, original_filename,
                                   visibility, upload_status, status
                            FROM file_resource
                            WHERE file_id = :fileId
                            FOR UPDATE
                            """)
                    .param("fileId", fileId)
                    .query((rs, rowNum) -> new BindableFile(
                            rs.getLong("file_id"),
                            rs.getObject("order_id", Long.class),
                            rs.getObject("owner_user_id", Long.class),
                            rs.getString("original_filename"),
                            rs.getString("visibility"),
                            rs.getString("upload_status"),
                            rs.getString("status")))
                    .single();
            if (!identity.userId().equals(file.ownerUserId())
                    || !DOCTOR_VISIBLE_FILE_VISIBILITIES.contains(file.visibility())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot bind this file");
            }
            if (!"ACTIVE".equals(file.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "file is not active");
            }
            if (file.orderId() != null && !file.orderId().equals(targetOrderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "file is already bound to an order");
            }
            if (!"COMPLETED".equals(file.uploadStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "file upload is not completed");
            }
            return file;
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found", ex);
        }
    }

    private void requireSubmittedStl(List<BindableFile> files) {
        boolean hasStl = files.stream()
                .map(BindableFile::originalFilename)
                .filter(Objects::nonNull)
                .map((filename) -> filename.trim().toLowerCase(Locale.ROOT))
                .anyMatch((filename) -> filename.endsWith(".stl"));
        if (!hasStl) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "at least one completed STL file is required");
        }
    }

    private void validateOwnedPatient(Long patientId, BootstrapIdentity identity) {
        if (patientId == null) {
            return;
        }
        boolean owned = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM patient_record
                        WHERE patient_id = :patientId
                          AND clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                          AND status = 'ACTIVE'
                        """)
                .param("patientId", patientId)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .query(Long.class)
                .single() > 0;
        if (!owned) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot bind this patient");
        }
    }

    private void bindFilesToOrder(long orderId, List<Long> fileIds, Long ownerUserId) {
        for (Long fileId : fileIds) {
            int updated = jdbcClient.sql("""
                            UPDATE file_resource
                            SET order_id = :orderId,
                                source_type = 'ORDER_ATTACHMENT'
                            WHERE file_id = :fileId
                              AND owner_user_id = :ownerUserId
                              AND visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL')
                              AND upload_status = 'COMPLETED'
                              AND status = 'ACTIVE'
                              AND (order_id IS NULL OR order_id = :orderId)
                            """)
                    .param("orderId", orderId)
                    .param("fileId", fileId)
                    .param("ownerUserId", ownerUserId)
                    .update();
            if (updated != 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "file binding changed concurrently; reload the order and try again");
            }
        }
    }

    private void synchronizeDoctorOrderFiles(long orderId, List<Long> selectedFileIds, Long actorUserId) {
        List<Long> removedFileIds;
        if (selectedFileIds.isEmpty()) {
            removedFileIds = jdbcClient.sql("""
                            SELECT file_id
                            FROM file_resource
                            WHERE order_id = :orderId
                              AND owner_user_id = :actorUserId
                              AND source_type = 'ORDER_ATTACHMENT'
                              AND status = 'ACTIVE'
                            """)
                    .param("orderId", orderId)
                    .param("actorUserId", actorUserId)
                    .query(Long.class)
                    .list();
        } else {
            removedFileIds = jdbcClient.sql("""
                            SELECT file_id
                            FROM file_resource
                            WHERE order_id = :orderId
                              AND owner_user_id = :actorUserId
                              AND source_type = 'ORDER_ATTACHMENT'
                              AND status = 'ACTIVE'
                              AND file_id NOT IN (:selectedFileIds)
                            """)
                    .param("orderId", orderId)
                    .param("actorUserId", actorUserId)
                    .param("selectedFileIds", selectedFileIds)
                    .query(Long.class)
                    .list();
        }
        for (Long fileId : removedFileIds) {
            jdbcClient.sql("""
                            UPDATE file_resource
                            SET status = 'DELETED'
                            WHERE file_id = :fileId
                              AND order_id = :orderId
                              AND owner_user_id = :actorUserId
                              AND source_type = 'ORDER_ATTACHMENT'
                              AND status = 'ACTIVE'
                            """)
                    .param("fileId", fileId)
                    .param("orderId", orderId)
                    .param("actorUserId", actorUserId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO file_access_audit
                                (file_id, order_id, actor_user_id, action, access_result, reason)
                            VALUES
                                (:fileId, :orderId, :actorUserId, 'DELETE', 'ALLOWED',
                                 'doctor removed file from editable order')
                            """)
                    .param("fileId", fileId)
                    .param("orderId", orderId)
                    .param("actorUserId", actorUserId)
                    .update();
        }
        bindFilesToOrder(orderId, selectedFileIds, actorUserId);
    }

    private DoctorEditableOrder loadDoctorEditableOrder(long orderId, BootstrapIdentity identity) {
        try {
            return jdbcClient.sql("""
                            SELECT order_id, group_id, order_no, doctor_user_id,
                                   internal_status, external_status
                            FROM orders
                            WHERE order_id = :orderId
                              AND doctor_user_id = :doctorUserId
                              AND clinic_id = :clinicId
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .param("doctorUserId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .query((rs, rowNum) -> new DoctorEditableOrder(
                            rs.getLong("order_id"),
                            rs.getObject("group_id", Long.class),
                            rs.getString("order_no"),
                            rs.getObject("doctor_user_id", Long.class),
                            rs.getString("internal_status"),
                            rs.getString("external_status")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot update this order", ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private boolean orderExists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private void validateEditableStatus(String internalStatus, boolean submit) {
        Set<String> editableStatuses = Set.of(
                InternalOrderStatus.DRAFT.name(),
                InternalOrderStatus.CS_REJECTED.name(),
                InternalOrderStatus.PRODUCTION_REJECTED.name());
        if (!editableStatuses.contains(internalStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "order is not editable by doctor");
        }
        if (!submit && !InternalOrderStatus.DRAFT.name().equals(internalStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "rejected orders must be resubmitted");
        }
    }

    private String nextOrderNo() {
        String date = BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        return "ORD" + date + "-" + suffix;
    }

    private long createLegacySingleProductGroup(
            String orderNo,
            BootstrapIdentity identity,
            Long patientId,
            String lifecycleStatus) {
        String groupNo = "CG-" + orderNo;
        jdbcClient.sql("""
                        INSERT INTO order_case_group
                            (group_no, clinic_id, doctor_user_id, patient_id,
                             lifecycle_status, submitted_at)
                        VALUES
                            (:groupNo, :clinicId, :doctorUserId, :patientId,
                             :lifecycleStatus,
                             CASE WHEN :lifecycleStatus = 'SUBMITTED'
                                  THEN CURRENT_TIMESTAMP(3) ELSE NULL END)
                        """)
                .param("groupNo", groupNo)
                .param("clinicId", identity.clinicId())
                .param("doctorUserId", identity.userId())
                .param("patientId", patientId)
                .param("lifecycleStatus", lifecycleStatus)
                .update();
        return jdbcClient.sql("""
                        SELECT group_id
                        FROM order_case_group
                        WHERE group_no = :groupNo
                        """)
                .param("groupNo", groupNo)
                .query(Long.class)
                .single();
    }

    private void markGroupSubmitted(Long groupId) {
        if (groupId == null) {
            return;
        }
        jdbcClient.sql("""
                        UPDATE order_case_group
                        SET lifecycle_status = 'SUBMITTED',
                            submitted_at = COALESCE(submitted_at, CURRENT_TIMESTAMP(3)),
                            draft_version = draft_version + 1
                        WHERE group_id = :groupId
                          AND lifecycle_status = 'DRAFT'
                        """)
                .param("groupId", groupId)
                .update();
    }

    private String normalizeProductType(String productType) {
        return productType.trim().toUpperCase(Locale.ROOT);
    }

    private String writeJson(JsonNode formData) {
        try {
            return objectMapper.writeValueAsString(formData);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid form_data", ex);
        }
    }

    private record FormFieldRequirement(String fieldKey, boolean required) {
    }

    private record BindableFile(
            long fileId,
            Long orderId,
            Long ownerUserId,
            String originalFilename,
            String visibility,
            String uploadStatus,
            String status) {
    }

    private record DoctorEditableOrder(
            long orderId,
            Long groupId,
            String orderNo,
            Long doctorUserId,
            String internalStatus,
            String externalStatus) {
    }

    private record ClinicOrderGate(String status, boolean blacklisted) {
    }

    private record PriceSnapshot(Long priceCents, String currency, String source) {
    }
}
