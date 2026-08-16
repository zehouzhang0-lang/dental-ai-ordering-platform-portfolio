package com.yuri.aiorder.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.notification.NotificationPushService;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.ExternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CollaborationService {

    private static final Set<String> ALLOWED_PAYMENT_STATUSES = Set.of(
            "PENDING_PAYMENT",
            "PARTIALLY_PAID",
            "PAID",
            "NOT_REQUIRED");
    private static final Set<String> ALLOWED_LOGISTICS_FOLLOW_UP_STATUSES = Set.of(
            "PENDING",
            "SHIPPED",
            "EXCEPTION",
            "FOLLOWING",
            "RESOLVED");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final OrderStatusService orderStatusService;
    private final AccessControlService accessControlService;
    private final NotificationPushService notificationPushService;

    public CollaborationService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OrderStatusService orderStatusService,
            AccessControlService accessControlService,
            NotificationPushService notificationPushService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.orderStatusService = orderStatusService;
        this.accessControlService = accessControlService;
        this.notificationPushService = notificationPushService;
    }

    public List<MessageResponse> listMessages(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        if (identity.isDoctor()) {
            identity.requireDoctorScope(order.doctorUserId(), order.clinicId());
            return queryMessages(orderId, """
                    AND (
                        (visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL') AND review_status IN ('DIRECT', 'APPROVED'))
                        OR (sender_user_id = :viewerUserId AND sender_role = 'DOCTOR'
                            AND visibility = 'CS_ONLY' AND review_status = 'DIRECT')
                    )
                    """, identity, identity.userId());
        }
        if (identity.role() == UserRole.WORKER) {
            return queryMessages(orderId, "AND visibility IN ('CS_WORKER', 'ALL') AND review_status <> 'REJECTED'", identity);
        }
        return queryMessages(orderId, "", identity);
    }

    @Transactional
    public MessageResponse sendMessage(long orderId, MessageRequest request, BootstrapIdentity identity) {
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        List<MentionableUserResponse> mentionableUsers = loadMentionableUsers(order, identity);
        List<Long> mentionedUserIds = validateMentionedUserIds(mentionableUsers, request.mentionUserIds(), identity.userId());
        String reviewStatus;
        String visibility;
        if (identity.role() == UserRole.DOCTOR) {
            identity.requireDoctorScope(order.doctorUserId(), order.clinicId());
            reviewStatus = "DIRECT";
            visibility = "CS_ONLY";
        } else if (identity.role() == UserRole.WORKER) {
            reviewStatus = "PENDING_REVIEW";
            visibility = "ALL";
        } else {
            reviewStatus = "DIRECT";
            visibility = resolveInternalMessageVisibility(request.visibleTo(), mentionedUserIds, mentionableUsers);
        }
        jdbcClient.sql("""
                        INSERT INTO order_message
                            (order_id, sender_user_id, sender_role, content, visibility, review_status)
                        VALUES
                            (:orderId, :senderUserId, :senderRole, :content, :visibility, :reviewStatus)
                        """)
                .param("orderId", orderId)
                .param("senderUserId", identity.userId())
                .param("senderRole", identity.role().name())
                .param("content", request.content())
                .param("visibility", visibility)
                .param("reviewStatus", reviewStatus)
                .update();
        long messageId = lastInsertId();
        persistMentions(messageId, mentionedUserIds);
        if ("PENDING_REVIEW".equals(reviewStatus)) {
            emit(order, "MESSAGE_PENDING_REVIEW", "CS", order.csUserId(), "生产端消息待审核");
        } else if (doctorVisible(visibility)) {
            emit(order, "MESSAGE_RECEIVED", "DOCTOR", order.doctorUserId(), request.content());
        }
        if (!"PENDING_REVIEW".equals(reviewStatus)) {
            emitMentions(order, mentionedUserIds, request.content());
        }
        return loadMessage(messageId, identity);
    }

    @Transactional
    public MessageResponse reviewMessage(long messageId, MessageReviewRequest request, BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        MessageRow message = loadMessageRow(messageId);
        OrderRow order = loadOrder(message.orderId(), identity, "identity cannot access this order");
        String action = normalizeOrDefault(request.action(), "");
        String fromStatus = message.reviewStatus();
        String toStatus;
        String content = message.content();
        if ("APPROVE".equals(action)) {
            toStatus = "APPROVED";
        } else if ("EDIT_AND_APPROVE".equals(action)) {
            if (request.editedContent() == null || request.editedContent().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "edited_content is required");
            }
            toStatus = "APPROVED";
            content = request.editedContent();
        } else if ("REJECT".equals(action)) {
            toStatus = "REJECTED";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported review action");
        }
        jdbcClient.sql("""
                        UPDATE order_message
                        SET content = :content,
                            review_status = :toStatus
                        WHERE message_id = :messageId
                        """)
                .param("content", content)
                .param("toStatus", toStatus)
                .param("messageId", messageId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO message_review_log
                            (message_id, reviewer_user_id, from_status, to_status, reason)
                        VALUES
                            (:messageId, :reviewerUserId, :fromStatus, :toStatus, :reason)
                        """)
                .param("messageId", messageId)
                .param("reviewerUserId", identity.userId())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("reason", request.reviewNote())
                .update();
        if ("APPROVED".equals(toStatus) && doctorVisible(message.visibility())) {
            emit(order, "MESSAGE_RECEIVED", "DOCTOR", order.doctorUserId(), content);
        }
        if ("APPROVED".equals(toStatus)) {
            emitMentions(order, loadMentionUserIds(messageId), content);
        }
        if ("REJECTED".equals(toStatus)) {
            emit(order, "MESSAGE_REVIEW_REJECTED", message.senderRole(), message.senderUserId(), "消息审核未通过");
        }
        return loadMessage(messageId, identity);
    }

    public List<MessageResponse> pendingMessages(BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        return queryMessages(null, "AND review_status = 'PENDING_REVIEW'", identity);
    }

    public List<MentionableUserResponse> listMentionableUsers(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        return loadMentionableUsers(order, identity);
    }

    private List<MentionableUserResponse> loadMentionableUsers(OrderRow order, BootstrapIdentity identity) {
        String doctorFilter = identity.isDoctor() ? "AND u.user_id = :csUserId" : "";
        return jdbcClient.sql("""
                        SELECT DISTINCT u.user_id, u.display_name, u.user_type
                        FROM system_user u
                        WHERE u.status = 'ACTIVE'
                          AND (
                              u.user_id = :doctorUserId
                              OR u.user_id = :csUserId
                              OR EXISTS (
                                  SELECT 1
                                  FROM order_process_instance i
                                  JOIN order_process_node n ON n.instance_id = i.instance_id
                                  WHERE i.order_id = :orderId
                                    AND n.assigned_user_id = u.user_id
                              )
                          )
                          %s
                        ORDER BY u.display_name, u.user_id
                        """.formatted(doctorFilter))
                .param("orderId", order.orderId())
                .param("doctorUserId", order.doctorUserId())
                .param("csUserId", order.csUserId())
                .query((rs, rowNum) -> new MentionableUserResponse(
                        rs.getLong("user_id"),
                        rs.getString("display_name"),
                        rs.getString("user_type")))
                .list();
    }

    public List<MessageAttentionItemResponse> listAttentionItems(BootstrapIdentity identity) {
        return queryAttentionItems(identity, null);
    }

    @Transactional
    public MessageAttentionItemResponse resolveAttentionItem(long messageId, BootstrapIdentity identity) {
        MessageAttentionItemResponse item = queryAttentionItems(identity, messageId).stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unresolved attention item not found"));
        jdbcClient.sql("""
                        UPDATE order_message_mention
                        SET resolved_at = CURRENT_TIMESTAMP(3)
                        WHERE message_id = :messageId
                          AND mentioned_user_id = :userId
                          AND resolved_at IS NULL
                        """)
                .param("messageId", messageId)
                .param("userId", identity.userId())
                .update();
        return loadAttentionItem(messageId, identity.userId());
    }

    public BillResponse getBill(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        requireDoctorScopeIfNeeded(order, identity);
        return jdbcClient.sql("""
                        SELECT bill_id, order_id, bill_status, payment_status, amount_cent, currency, file_id
                        FROM order_bill
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new BillResponse(
                        rs.getObject("bill_id", Long.class),
                        rs.getLong("order_id"),
                        rs.getString("bill_status"),
                        rs.getString("payment_status"),
                        rs.getObject("amount_cent", Long.class),
                        rs.getString("currency"),
                        rs.getObject("file_id", Long.class)))
                .optional()
                .orElse(new BillResponse(null, orderId, "PENDING", "PENDING_PAYMENT", null, "CNY", null));
    }

    public List<PaymentRecordResponse> listPaymentRecords(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        requireDoctorOwnerIfNeeded(order, identity);
        return jdbcClient.sql("""
                        SELECT payment_id, order_id, amount_cents, currency, payment_method,
                               received_at, payment_note, created_by_user_id, created_at
                        FROM order_payment_record
                        WHERE order_id = :orderId
                        ORDER BY received_at DESC, payment_id DESC
                        """)
                .param("orderId", orderId)
                .query(this::mapPaymentRecord)
                .list();
    }

    @Transactional
    public PaymentRecordResponse createPaymentRecord(
            long orderId, PaymentRecordRequest request, BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        long amountCents = request.amountCents() == null ? 0L : request.amountCents();
        if (amountCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_cents must be positive");
        }
        String paymentMethod = normalizeOrDefault(request.paymentMethod(), "");
        if (paymentMethod.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payment_method is required");
        }
        String currency = normalizeOrDefault(request.currency(), "CNY");
        LocalDateTime receivedAt = request.receivedAt() == null ? LocalDateTime.now() : request.receivedAt();
        BillLedgerRow bill = jdbcClient.sql("""
                        SELECT amount_cent, currency
                        FROM order_bill
                        WHERE order_id = :orderId
                          AND bill_status = 'UPLOADED'
                          AND file_id IS NOT NULL
                        FOR UPDATE
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new BillLedgerRow(
                        rs.getObject("amount_cent", Long.class),
                        rs.getString("currency")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "an uploaded bill is required before recording payment"));
        if (bill.amountCents() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "bill amount is required before recording payment");
        }
        if (!bill.currency().equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment currency must match bill currency");
        }
        long receivedBefore = jdbcClient.sql("""
                        SELECT COALESCE(SUM(amount_cents), 0)
                        FROM order_payment_record
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (receivedBefore + amountCents > bill.amountCents()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "payment amount exceeds the bill outstanding amount");
        }

        jdbcClient.sql("""
                        INSERT INTO order_payment_record
                            (order_id, amount_cents, currency, payment_method, received_at, payment_note, created_by_user_id)
                        VALUES
                            (:orderId, :amountCents, :currency, :paymentMethod, :receivedAt, :paymentNote, :createdByUserId)
                        """)
                .param("orderId", orderId)
                .param("amountCents", amountCents)
                .param("currency", currency)
                .param("paymentMethod", paymentMethod)
                .param("receivedAt", receivedAt)
                .param("paymentNote", normalizeNullable(request.paymentNote()))
                .param("createdByUserId", identity.userId())
                .update();
        long paymentId = lastInsertId();
        String paymentStatus = receivedBefore + amountCents == bill.amountCents() ? "PAID" : "PARTIALLY_PAID";
        jdbcClient.sql("""
                        UPDATE order_bill
                        SET payment_status = :paymentStatus,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE order_id = :orderId
                        """)
                .param("paymentStatus", paymentStatus)
                .param("orderId", orderId)
                .update();
        emit(order, "PAYMENT_RECORD_CREATED", "DOCTOR", order.doctorUserId(), "收款记录已更新");
        return loadPaymentRecord(paymentId);
    }

    @Transactional
    public BillResponse uploadBill(long orderId, BillRequest request, BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        if (request.fileId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file_id is required");
        }
        if (request.amountCents() != null && request.amountCents() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_cents must be positive");
        }
        String currency = normalizeOrDefault(request.currency(), "CNY").toUpperCase(Locale.ROOT);
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        long eligibleBillFileCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_resource
                        WHERE file_id = :fileId
                          AND order_id = :orderId
                          AND source_type = 'BILL'
                          AND visibility = 'DOCTOR_CS'
                          AND upload_status = 'COMPLETED'
                          AND status = 'ACTIVE'
                          AND (
                              LOWER(COALESCE(content_type, '')) = 'application/pdf'
                              OR LOWER(original_filename) LIKE '%.pdf'
                          )
                        """)
                .param("fileId", request.fileId())
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (eligibleBillFileCount == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "bill file must be a completed doctor-visible PDF for this order");
        }
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, amount_cent, currency, bill_status, file_id)
                        VALUES (:orderId, :amountCents, :currency, 'UPLOADED', :fileId)
                        ON DUPLICATE KEY UPDATE
                            amount_cent = COALESCE(VALUES(amount_cent), amount_cent),
                            currency = CASE WHEN VALUES(amount_cent) IS NULL THEN currency ELSE VALUES(currency) END,
                            bill_status = 'UPLOADED',
                            file_id = VALUES(file_id),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("amountCents", request.amountCents(), Types.BIGINT)
                .param("currency", currency)
                .param("fileId", request.fileId())
                .update();
        emit(order, "BILL_UPLOADED", "DOCTOR", order.doctorUserId(), "账单已上传");
        return getBill(orderId, identity);
    }

    private PaymentRecordResponse loadPaymentRecord(long paymentId) {
        return jdbcClient.sql("""
                        SELECT payment_id, order_id, amount_cents, currency, payment_method,
                               received_at, payment_note, created_by_user_id, created_at
                        FROM order_payment_record
                        WHERE payment_id = :paymentId
                        """)
                .param("paymentId", paymentId)
                .query(this::mapPaymentRecord)
                .single();
    }

    private PaymentRecordResponse mapPaymentRecord(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PaymentRecordResponse(
                rs.getLong("payment_id"),
                rs.getLong("order_id"),
                rs.getLong("amount_cents"),
                rs.getString("currency"),
                rs.getString("payment_method"),
                rs.getObject("received_at", LocalDateTime.class),
                rs.getString("payment_note"),
                rs.getObject("created_by_user_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class));
    }

    @Transactional
    public BillResponse updatePaymentStatus(long orderId, PaymentStatusRequest request, BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        String paymentStatus = normalizeOrDefault(request.paymentStatus(), "");
        if (!ALLOWED_PAYMENT_STATUSES.contains(paymentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported payment_status");
        }
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        jdbcClient.sql("""
                        INSERT INTO order_bill (order_id, bill_status, payment_status)
                        VALUES (:orderId, 'PENDING', :paymentStatus)
                        ON DUPLICATE KEY UPDATE
                            payment_status = VALUES(payment_status),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("paymentStatus", paymentStatus)
                .update();
        emit(order, "PAYMENT_STATUS_UPDATED", "DOCTOR", order.doctorUserId(), "付款状态已更新");
        return getBill(orderId, identity);
    }

    public LogisticsResponse getLogistics(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        requireDoctorScopeIfNeeded(order, identity);
        return jdbcClient.sql("""
                        SELECT logistics_id, order_id, carrier_name, tracking_no, logistics_status
                        FROM order_logistics
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new LogisticsResponse(
                        rs.getObject("logistics_id", Long.class),
                        rs.getLong("order_id"),
                        rs.getString("carrier_name"),
                        rs.getString("tracking_no"),
                        doctorSafeLogisticsStatus(
                                rs.getString("logistics_status"),
                                rs.getString("tracking_no"),
                                identity)))
                .optional()
                .orElse(new LogisticsResponse(null, orderId, null, null, "PENDING"));
    }

    @Transactional
    public ExternalOrderStatus confirmReceipt(long orderId, BootstrapIdentity identity) {
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        requireDoctorScopeIfNeeded(order, identity);
        String logisticsStatus;
        try {
            logisticsStatus = jdbcClient.sql("""
                            SELECT logistics_status
                            FROM order_logistics
                            WHERE order_id = :orderId
                            FOR UPDATE
                            """)
                    .param("orderId", orderId)
                    .query(String.class)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "receipt confirmation requires shipped logistics", ex);
        }
        if (!Set.of("SHIPPED", "DELIVERED_PENDING_CONFIRMATION").contains(logisticsStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "receipt confirmation requires shipped logistics");
        }
        jdbcClient.sql("""
                        UPDATE order_logistics
                        SET logistics_status = 'DELIVERED',
                            delivered_at = CURRENT_TIMESTAMP(3),
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        ExternalOrderStatus status = orderStatusService.updateOrderState(
                orderId,
                InternalOrderStatus.COMPLETED,
                "DOCTOR_CONFIRM_RECEIPT",
                identity.userId(),
                "doctor confirmed receipt");
        emit(order, "ORDER_RECEIVED", "CS", order.csUserId(), "医生已确认收货");
        return status;
    }

    public List<DeliveryOrderResponse> listDeliveryOrders(
            String logisticsStatus,
            int limit,
            BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        String statusFilter = normalizeOrDefault(logisticsStatus, "");
        if (!statusFilter.isBlank() && !ALLOWED_LOGISTICS_FOLLOW_UP_STATUSES.contains(statusFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported logistics_status");
        }
        int normalizedLimit = Math.max(1, Math.min(limit, 200));
        String statusWhere = statusFilter.isBlank()
                ? ""
                : "AND COALESCE(l.logistics_status, 'PENDING') = :logisticsStatus";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT o.order_id, o.order_no, o.product_type, o.external_status,
                               COALESCE(b.bill_status, 'PENDING') AS bill_status,
                               COALESCE(b.payment_status, 'PENDING_PAYMENT') AS payment_status,
                               l.carrier_name, l.tracking_no,
                               COALESCE(l.logistics_status, 'PENDING') AS logistics_status,
                               (
                                   SELECT m.content
                                   FROM order_message m
                                   WHERE m.order_id = o.order_id
                                     AND m.visibility = 'CS_ONLY'
                                     AND m.content LIKE '[物流跟进]%%'
                                   ORDER BY m.created_at DESC, m.message_id DESC
                                   LIMIT 1
                               ) AS last_follow_up_note
                        FROM orders o
                        LEFT JOIN order_bill b ON b.order_id = o.order_id
                        LEFT JOIN order_logistics l ON l.order_id = o.order_id
                        WHERE 1 = 1
                        %s
                        ORDER BY
                            CASE COALESCE(l.logistics_status, 'PENDING')
                                WHEN 'EXCEPTION' THEN 0
                                WHEN 'FOLLOWING' THEN 1
                                WHEN 'PENDING' THEN 2
                                WHEN 'SHIPPED' THEN 3
                                ELSE 4
                            END,
                            COALESCE(l.updated_at, o.updated_at) DESC,
                            o.order_id DESC
                        LIMIT :limit
                        """.formatted(statusWhere))
                .param("limit", normalizedLimit);
        if (!statusFilter.isBlank()) {
            spec = spec.param("logisticsStatus", statusFilter);
        }
        return spec.query((rs, rowNum) -> new DeliveryOrderResponse(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("product_type"),
                        rs.getString("external_status"),
                        rs.getString("bill_status"),
                        rs.getString("payment_status"),
                        rs.getString("carrier_name"),
                        rs.getString("tracking_no"),
                        rs.getString("logistics_status"),
                        rs.getString("last_follow_up_note")))
                .list();
    }

    @Transactional
    public DeliveryOrderResponse updateLogisticsException(
            long orderId,
            LogisticsExceptionRequest request,
            BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        String logisticsStatus = normalizeOrDefault(request.logisticsStatus(), "");
        if (!ALLOWED_LOGISTICS_FOLLOW_UP_STATUSES.contains(logisticsStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported logistics_status");
        }
        String followUpNote = normalizeNullable(request.followUpNote());
        if (followUpNote == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "follow_up_note is required");
        }
        jdbcClient.sql("""
                        INSERT INTO order_logistics (order_id, logistics_status)
                        VALUES (:orderId, :logisticsStatus)
                        ON DUPLICATE KEY UPDATE
                            logistics_status = VALUES(logistics_status),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("logisticsStatus", logisticsStatus)
                .update();
        jdbcClient.sql("""
                        INSERT INTO order_message
                            (order_id, sender_user_id, sender_role, content, visibility, review_status)
                        VALUES
                            (:orderId, :senderUserId, :senderRole, :content, 'CS_ONLY', 'DIRECT')
                        """)
                .param("orderId", order.orderId())
                .param("senderUserId", identity.userId())
                .param("senderRole", identity.role().name())
                .param("content", "[物流跟进][" + logisticsStatus + "] " + followUpNote)
                .update();
        return getDeliveryOrder(orderId);
    }

    @Transactional
    public LogisticsResponse shipOrder(long orderId, LogisticsRequest request, BootstrapIdentity identity) {
        requireCsOrAdmin(identity);
        if (request.carrier() == null || request.carrier().isBlank() || request.trackingNo() == null || request.trackingNo().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "carrier and tracking_no are required");
        }
        OrderRow order = loadOrder(orderId, identity, "identity cannot access this order");
        requireFinalOutCheckPass(orderId);
        requirePaymentReady(orderId);
        jdbcClient.sql("""
                        INSERT INTO order_logistics
                            (order_id, carrier_name, tracking_no, logistics_status, shipped_at)
                        VALUES
                            (:orderId, :carrier, :trackingNo, 'SHIPPED', CURRENT_TIMESTAMP(3))
                        ON DUPLICATE KEY UPDATE
                            carrier_name = VALUES(carrier_name),
                            tracking_no = VALUES(tracking_no),
                            logistics_status = 'SHIPPED',
                            shipped_at = COALESCE(shipped_at, CURRENT_TIMESTAMP(3)),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("carrier", request.carrier())
                .param("trackingNo", request.trackingNo())
                .update();
        orderStatusService.updateOrderState(orderId, InternalOrderStatus.SHIPPED, "ORDER_SHIPPED", identity.userId(), request.trackingNo());
        emit(order, "ORDER_SHIPPED", "DOCTOR", order.doctorUserId(), "订单已发货");
        return getLogistics(orderId, identity);
    }

    private DeliveryOrderResponse getDeliveryOrder(long orderId) {
        return jdbcClient.sql("""
                        SELECT o.order_id, o.order_no, o.product_type, o.external_status,
                               COALESCE(b.bill_status, 'PENDING') AS bill_status,
                               COALESCE(b.payment_status, 'PENDING_PAYMENT') AS payment_status,
                               l.carrier_name, l.tracking_no,
                               COALESCE(l.logistics_status, 'PENDING') AS logistics_status,
                               (
                                   SELECT m.content
                                   FROM order_message m
                                   WHERE m.order_id = o.order_id
                                     AND m.visibility = 'CS_ONLY'
                                     AND m.content LIKE '[物流跟进]%%'
                                   ORDER BY m.created_at DESC, m.message_id DESC
                                   LIMIT 1
                               ) AS last_follow_up_note
                        FROM orders o
                        LEFT JOIN order_bill b ON b.order_id = o.order_id
                        LEFT JOIN order_logistics l ON l.order_id = o.order_id
                        WHERE o.order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new DeliveryOrderResponse(
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("product_type"),
                        rs.getString("external_status"),
                        rs.getString("bill_status"),
                        rs.getString("payment_status"),
                        rs.getString("carrier_name"),
                        rs.getString("tracking_no"),
                        rs.getString("logistics_status"),
                        rs.getString("last_follow_up_note")))
                .single();
    }

    private void requireFinalOutCheckPass(long orderId) {
        long finalNodeCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_node n
                        JOIN order_process_instance i ON i.instance_id = n.instance_id
                        WHERE i.order_id = :orderId
                          AND n.step_order = (
                              SELECT MAX(last_node.step_order)
                              FROM order_process_node last_node
                              JOIN order_process_instance last_i ON last_i.instance_id = last_node.instance_id
                              WHERE last_i.order_id = :orderId
                          )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (finalNodeCount == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "final out-check pass is required before shipment");
        }
        long missingPassCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_node n
                        JOIN order_process_instance i ON i.instance_id = n.instance_id
                        WHERE i.order_id = :orderId
                          AND n.step_order = (
                              SELECT MAX(last_node.step_order)
                              FROM order_process_node last_node
                              JOIN order_process_instance last_i ON last_i.instance_id = last_node.instance_id
                              WHERE last_i.order_id = :orderId
                          )
                          AND NOT EXISTS (
                              SELECT 1
                              FROM check_record c
                              WHERE c.order_id = i.order_id
                                AND c.node_instance_id = n.node_instance_id
                                AND c.check_type = 'OUT'
                                AND c.result = 'PASS'
                          )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (missingPassCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "final out-check pass is required before shipment");
        }
    }

    private void requirePaymentReady(long orderId) {
        long readyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_bill
                        WHERE order_id = :orderId
                          AND payment_status IN ('PAID', 'NOT_REQUIRED')
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (readyCount == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "payment must be paid or marked not required before shipment");
        }
    }

    private List<MessageResponse> queryMessages(Long orderId, String extraWhere, BootstrapIdentity identity) {
        return queryMessages(orderId, extraWhere, identity, null);
    }

    private List<MessageResponse> queryMessages(
            Long orderId,
            String extraWhere,
            BootstrapIdentity identity,
            Long viewerUserId) {
        String orderFilter = orderId == null ? "" : "AND m.order_id = :orderId";
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT m.message_id, m.order_id, o.order_no, o.product_type, o.external_status,
                               m.sender_user_id, m.sender_role, m.content, m.visibility, m.review_status,
                               m.created_at
                        FROM order_message m
                        JOIN orders o ON o.order_id = m.order_id
                        WHERE 1 = 1
                        %s
                        %s
                        ORDER BY m.created_at, m.message_id
                        """.formatted(orderFilter, extraWhere));
        if (orderId != null) {
            spec = spec.param("orderId", orderId);
        }
        if (viewerUserId != null) {
            spec = spec.param("viewerUserId", viewerUserId);
        }
        return spec.query((rs, rowNum) -> new MessageResponse(
                        rs.getLong("message_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getString("product_type"),
                        rs.getString("external_status"),
                        rs.getObject("sender_user_id", Long.class),
                        rs.getString("sender_role"),
                        rs.getString("content"),
                        rs.getString("visibility"),
                        rs.getString("review_status"),
                        visibleMentionUserIds(rs.getLong("message_id"), identity),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
    }

    private MessageResponse loadMessage(long messageId, BootstrapIdentity identity) {
        MessageRow row = loadMessageRow(messageId);
        return new MessageResponse(
                row.messageId(), row.orderId(), row.orderNo(), row.productType(), row.externalStatus(),
                row.senderUserId(), row.senderRole(), row.content(), row.visibility(), row.reviewStatus(),
                visibleMentionUserIds(messageId, identity), row.createdAt());
    }

    private List<Long> validateMentionedUserIds(
            List<MentionableUserResponse> mentionableUsers,
            List<Long> requestedUserIds,
            Long senderUserId) {
        List<Long> normalizedUserIds = normalizeFileIds(requestedUserIds == null ? List.of() : requestedUserIds);
        if (senderUserId != null) {
            normalizedUserIds.removeIf(senderUserId::equals);
        }
        Set<Long> mentionableUserIds = new LinkedHashSet<>();
        for (MentionableUserResponse user : mentionableUsers) {
            mentionableUserIds.add(user.userId());
        }
        for (Long userId : normalizedUserIds) {
            if (!mentionableUserIds.contains(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mentioned user is not an order participant");
            }
        }
        return normalizedUserIds;
    }

    private String resolveInternalMessageVisibility(
            String requestedVisibility,
            List<Long> mentionedUserIds,
            List<MentionableUserResponse> mentionableUsers) {
        if (requestedVisibility != null && !requestedVisibility.isBlank()) {
            return normalizeOrDefault(requestedVisibility, "DOCTOR_CS");
        }
        Set<Long> mentionedUserIdSet = new LinkedHashSet<>(mentionedUserIds);
        boolean mentionsDoctor = mentionableUsers.stream()
                .anyMatch(user -> mentionedUserIdSet.contains(user.userId()) && "DOCTOR".equals(user.userRole()));
        boolean mentionsWorker = mentionableUsers.stream()
                .anyMatch(user -> mentionedUserIdSet.contains(user.userId()) && "WORKER".equals(user.userRole()));
        if (mentionsDoctor && mentionsWorker) {
            return "ALL";
        }
        if (mentionsWorker) {
            return "CS_WORKER";
        }
        return "DOCTOR_CS";
    }

    private void persistMentions(long messageId, List<Long> mentionedUserIds) {
        for (Long mentionedUserId : mentionedUserIds) {
            jdbcClient.sql("""
                            INSERT INTO order_message_mention (message_id, mentioned_user_id)
                            VALUES (:messageId, :mentionedUserId)
                            """)
                    .param("messageId", messageId)
                    .param("mentionedUserId", mentionedUserId)
                    .update();
        }
    }

    private List<Long> loadMentionUserIds(long messageId) {
        return jdbcClient.sql("""
                        SELECT mentioned_user_id
                        FROM order_message_mention
                        WHERE message_id = :messageId
                        ORDER BY mentioned_user_id
                        """)
                .param("messageId", messageId)
                .query(Long.class)
                .list();
    }

    private List<Long> visibleMentionUserIds(long messageId, BootstrapIdentity identity) {
        List<Long> mentionUserIds = loadMentionUserIds(messageId);
        if (!identity.isDoctor()) {
            return mentionUserIds;
        }
        return mentionUserIds.stream()
                .filter(userId -> userId.equals(identity.userId()))
                .toList();
    }

    private void emitMentions(OrderRow order, List<Long> mentionedUserIds, String content) {
        for (Long mentionedUserId : mentionedUserIds) {
            String audienceRole = jdbcClient.sql("""
                            SELECT user_type
                            FROM system_user
                            WHERE user_id = :userId
                            """)
                    .param("userId", mentionedUserId)
                    .query(String.class)
                    .single();
            emit(order, "MESSAGE_MENTIONED", audienceRole, mentionedUserId, content);
        }
    }

    private List<MessageAttentionItemResponse> queryAttentionItems(BootstrapIdentity identity, Long messageId) {
        String messageFilter = messageId == null ? "" : "AND mm.message_id = :messageId";
        String visibilityFilter = attentionVisibilityFilter(identity);
        JdbcClient.StatementSpec spec = jdbcClient.sql("""
                        SELECT mm.message_id, m.order_id, o.order_no, m.sender_user_id, m.sender_role, m.content,
                               mm.mentioned_user_id, m.created_at, mm.resolved_at
                        FROM order_message_mention mm
                        JOIN order_message m ON m.message_id = mm.message_id
                        JOIN orders o ON o.order_id = m.order_id
                        WHERE mm.mentioned_user_id = :userId
                          AND mm.resolved_at IS NULL
                          %s
                          %s
                        ORDER BY m.created_at DESC, mm.message_id DESC
                        """.formatted(messageFilter, visibilityFilter))
                .param("userId", identity.userId());
        if (messageId != null) {
            spec = spec.param("messageId", messageId);
        }
        if (identity.isDoctor()) {
            spec = spec.param("clinicId", identity.clinicId());
        }
        return spec.query((rs, rowNum) -> new MessageAttentionItemResponse(
                        rs.getLong("message_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getObject("sender_user_id", Long.class),
                        rs.getString("sender_role"),
                        rs.getString("content"),
                        rs.getLong("mentioned_user_id"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class)))
                .list();
    }

    private String attentionVisibilityFilter(BootstrapIdentity identity) {
        if (identity.isDoctor()) {
            return "AND o.doctor_user_id = :userId AND o.clinic_id = :clinicId "
                    + "AND m.visibility IN ('DOCTOR', 'DOCTOR_CS', 'ALL') "
                    + "AND m.review_status IN ('DIRECT', 'APPROVED')";
        }
        if (identity.role() == UserRole.WORKER) {
            return "AND m.visibility IN ('CS_WORKER', 'ALL') AND m.review_status <> 'REJECTED'";
        }
        return "";
    }

    private MessageAttentionItemResponse loadAttentionItem(long messageId, Long userId) {
        return jdbcClient.sql("""
                        SELECT mm.message_id, m.order_id, o.order_no, m.sender_user_id, m.sender_role, m.content,
                               mm.mentioned_user_id, m.created_at, mm.resolved_at
                        FROM order_message_mention mm
                        JOIN order_message m ON m.message_id = mm.message_id
                        JOIN orders o ON o.order_id = m.order_id
                        WHERE mm.message_id = :messageId
                          AND mm.mentioned_user_id = :userId
                        """)
                .param("messageId", messageId)
                .param("userId", userId)
                .query((rs, rowNum) -> new MessageAttentionItemResponse(
                        rs.getLong("message_id"),
                        rs.getLong("order_id"),
                        rs.getString("order_no"),
                        rs.getObject("sender_user_id", Long.class),
                        rs.getString("sender_role"),
                        rs.getString("content"),
                        rs.getLong("mentioned_user_id"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class)))
                .single();
    }

    private MessageRow loadMessageRow(long messageId) {
        try {
            return jdbcClient.sql("""
                            SELECT m.message_id, m.order_id, o.order_no, o.product_type, o.external_status,
                                   m.sender_user_id, m.sender_role, m.content, m.visibility, m.review_status,
                                   m.created_at
                            FROM order_message m
                            JOIN orders o ON o.order_id = m.order_id
                            WHERE m.message_id = :messageId
                            """)
                    .param("messageId", messageId)
                    .query((rs, rowNum) -> new MessageRow(
                            rs.getLong("message_id"),
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getString("product_type"),
                            rs.getString("external_status"),
                            rs.getObject("sender_user_id", Long.class),
                            rs.getString("sender_role"),
                            rs.getString("content"),
                            rs.getString("visibility"),
                            rs.getString("review_status"),
                            rs.getObject("created_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found", ex);
        }
    }

    private List<Long> normalizeFileIds(List<Long> rawFileIds) {
        Set<Long> seen = new LinkedHashSet<>();
        for (Long fileId : rawFileIds) {
            if (fileId != null && fileId > 0) {
                seen.add(fileId);
            }
        }
        return new ArrayList<>(seen);
    }

    private OrderRow loadOrder(long orderId, BootstrapIdentity identity, String forbiddenMessage) {
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        boolean canReviewProduction = accessControlService.canReviewProduction(identity);
        try {
            return jdbcClient.sql("""
                            SELECT order_id, order_no, clinic_id, doctor_user_id, cs_user_id
                            FROM orders
                            WHERE order_id = :orderId
                              AND (
                                  :dataScope = 'ALL'
                                  OR (:dataScope = 'CLINIC'
                                      AND (clinic_id = :clinicId OR doctor_user_id = :userId))
                                  OR (:dataScope = 'SELF'
                                      AND (
                                          doctor_user_id = :userId
                                          OR cs_user_id = :userId
                                          OR EXISTS (
                                              SELECT 1
                                              FROM order_process_instance scoped_i
                                              JOIN order_process_node scoped_n
                                                ON scoped_n.instance_id = scoped_i.instance_id
                                              WHERE scoped_i.order_id = orders.order_id
                                                AND scoped_n.assigned_user_id = :userId
                                          )
                                          OR EXISTS (
                                              SELECT 1
                                              FROM design_task scoped_design
                                              WHERE scoped_design.order_id = orders.order_id
                                                AND scoped_design.assigned_user_id = :userId
                                                AND scoped_design.task_status <> 'CANCELLED'
                                          )
                                      ))
                                  OR (
                                      :canReviewProduction = TRUE
                                      AND orders.internal_status = 'PENDING_PRODUCTION_REVIEW'
                                  )
                              )
                            """)
                    .param("orderId", orderId)
                    .param("dataScope", dataScope)
                    .param("userId", identity.userId())
                    .param("clinicId", identity.clinicId())
                    .param("canReviewProduction", canReviewProduction)
                    .query((rs, rowNum) -> new OrderRow(
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getLong("clinic_id"),
                            rs.getObject("doctor_user_id", Long.class),
                            rs.getObject("cs_user_id", Long.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, forbiddenMessage, ex);
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

    private void emit(OrderRow order, String eventType, String audienceRole, Long userId, String message) {
        String payload = payload(order, eventType, message);
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, :audienceRole, CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", order.orderId())
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .param("payload", payload)
                .update();
        long eventId = lastInsertId();
        if (userId != null) {
            jdbcClient.sql("""
                            INSERT IGNORE INTO user_notification (event_id, user_id)
                            VALUES (:eventId, :userId)
                            """)
                    .param("eventId", eventId)
                    .param("userId", userId)
                    .update();
            notificationPushService.pushToUser(userId, eventId, payload);
        }
    }

    private String payload(OrderRow order, String eventType, String message) {
        try {
            return objectMapper.writeValueAsString(new NotificationPayload(
                    eventType, order.orderId(), order.orderNo(), message));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to build notification payload", ex);
        }
    }

    private void requireDoctorScopeIfNeeded(OrderRow order, BootstrapIdentity identity) {
        if (identity.isDoctor()) {
            identity.requireDoctorScope(order.doctorUserId(), order.clinicId());
        }
    }

    private void requireDoctorOwnerIfNeeded(OrderRow order, BootstrapIdentity identity) {
        if (identity.isDoctor() && (identity.userId() == null || !identity.userId().equals(order.doctorUserId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this order");
        }
    }

    private String doctorSafeLogisticsStatus(String rawStatus, String trackingNo, BootstrapIdentity identity) {
        if (!identity.isDoctor()) {
            return rawStatus;
        }
        if (List.of("EXCEPTION", "FOLLOWING", "RESOLVED").contains(rawStatus)) {
            return trackingNo == null || trackingNo.isBlank() ? "PENDING" : "SHIPPED";
        }
        return rawStatus;
    }

    private void requireCsOrAdmin(BootstrapIdentity identity) {
        if (identity.role() != UserRole.CS && identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CS or ADMIN role is required");
        }
    }

    private boolean doctorVisible(String visibility) {
        return List.of("DOCTOR", "DOCTOR_CS", "ALL").contains(visibility);
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
    }

    private record OrderRow(long orderId, String orderNo, long clinicId, Long doctorUserId, Long csUserId) {
    }

    private record BillLedgerRow(Long amountCents, String currency) {
    }

    private record MessageRow(
            long messageId,
            long orderId,
            String orderNo,
            String productType,
            String externalStatus,
            Long senderUserId,
            String senderRole,
            String content,
            String visibility,
            String reviewStatus,
            LocalDateTime createdAt) {
    }

    private record NotificationPayload(String event, long orderId, String orderNo, String message) {
    }
}
