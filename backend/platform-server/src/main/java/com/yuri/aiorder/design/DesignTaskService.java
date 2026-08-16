package com.yuri.aiorder.design;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.collaboration.DesignDraftRequest;
import com.yuri.aiorder.collaboration.DesignDraftResponse;
import com.yuri.aiorder.collaboration.DesignDraftReviewEventResponse;
import com.yuri.aiorder.collaboration.DesignDraftReviewRequest;
import com.yuri.aiorder.collaboration.DoctorDraftConfirmRequest;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DesignTaskService {

    private static final Set<String> REVISION_TASK_STATUSES = Set.of(
            "CLAIMED", "INTERNAL_REJECTED", "DOCTOR_REJECTED");
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(
            "DOCTOR_CONFIRMED", "CANCELLED");

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final OrderStatusService orderStatusService;

    public DesignTaskService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            OrderStatusService orderStatusService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.orderStatusService = orderStatusService;
    }

    public List<DesignTaskResponse> listPool(String productType, BootstrapIdentity identity) {
        requireWorkerOrAdmin(identity);
        String productFilter = normalizeNullable(productType) == null ? "" : "AND o.product_type = :productType";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                               o.external_status AS order_status, o.doctor_user_id,
                               dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                               dt.claimed_at, dt.updated_at
                        FROM design_task dt
                        JOIN orders o ON o.order_id = dt.order_id
                        LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                        WHERE dt.task_status = 'OPEN'
                          AND dt.assigned_user_id IS NULL
                          %s
                        ORDER BY dt.created_at, dt.design_task_id
                        """.formatted(productFilter));
        if (!productFilter.isBlank()) {
            statement = statement.param("productType", productType.trim());
        }
        return statement
                .query((rs, rowNum) -> mapTaskRow(rs))
                .list()
                .stream()
                .map(row -> toResponse(row, identity, false))
                .toList();
    }

    public List<DesignTaskResponse> listMine(String status, BootstrapIdentity identity) {
        requireWorkerOrAdmin(identity);
        if (identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user id is required");
        }
        String normalizedStatus = normalizeNullable(status);
        String statusFilter = normalizedStatus == null ? "" : "AND dt.task_status = :taskStatus";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                               o.external_status AS order_status, o.doctor_user_id,
                               dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                               dt.claimed_at, dt.updated_at
                        FROM design_task dt
                        JOIN orders o ON o.order_id = dt.order_id
                        LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                        WHERE dt.assigned_user_id = :userId
                          %s
                        ORDER BY dt.updated_at DESC, dt.design_task_id DESC
                        """.formatted(statusFilter))
                .param("userId", identity.userId());
        if (normalizedStatus != null) {
            statement = statement.param("taskStatus", normalizedStatus);
        }
        return statement
                .query((rs, rowNum) -> mapTaskRow(rs))
                .list()
                .stream()
                .map(row -> toResponse(row, identity, true))
                .toList();
    }

    public List<DesignTaskResponse> listInternalReviewQueue(BootstrapIdentity identity) {
        requireInternalReviewer(identity);
        return jdbcClient.sql("""
                        SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                               o.external_status AS order_status, o.doctor_user_id,
                               dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                               dt.claimed_at, dt.updated_at
                        FROM design_task dt
                        JOIN orders o ON o.order_id = dt.order_id
                        LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                        WHERE dt.task_status IN ('SUBMITTED', 'INTERNAL_REVIEW')
                        ORDER BY dt.updated_at, dt.design_task_id
                        """)
                .query((rs, rowNum) -> mapTaskRow(rs))
                .list()
                .stream()
                .map(row -> toResponse(row, identity, true))
                .toList();
    }

    public List<DesignTaskResponse> listManaged(BootstrapIdentity identity) {
        if (identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "design task management requires ADMIN role");
        }
        return jdbcClient.sql("""
                        SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                               o.external_status AS order_status, o.doctor_user_id,
                               dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                               dt.claimed_at, dt.updated_at
                        FROM design_task dt
                        JOIN orders o ON o.order_id = dt.order_id
                        LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                        ORDER BY dt.updated_at DESC, dt.design_task_id DESC
                        """)
                .query((rs, rowNum) -> mapTaskRow(rs))
                .list()
                .stream()
                .map(row -> toResponse(row, identity, true))
                .toList();
    }

    public DesignTaskResponse getByOrder(long orderId, BootstrapIdentity identity) {
        TaskRow task = loadTaskByOrder(orderId, false);
        requireReadable(task, identity);
        return toResponse(task, identity, true);
    }

    @Transactional
    public long ensureTaskForOrder(long orderId, long nodeInstanceId, BootstrapIdentity actor) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO design_task
                            (order_id, node_instance_id, task_status)
                        SELECT order_id, :nodeInstanceId, 'OPEN'
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .param("nodeInstanceId", nodeInstanceId)
                .update();
        jdbcClient.sql("""
                        UPDATE design_task
                        SET node_instance_id = COALESCE(node_instance_id, :nodeInstanceId)
                        WHERE order_id = :orderId
                        """)
                .param("nodeInstanceId", nodeInstanceId)
                .param("orderId", orderId)
                .update();
        TaskRow task = loadTaskByOrder(orderId, true);
        if (inserted == 1) {
            recordEvent(task, null, "TASK_CREATED", actor, null, "OPEN", null, null, "production review approved");
        }
        return task.taskId();
    }

    @Transactional
    public DesignTaskResponse claim(long taskId, BootstrapIdentity identity) {
        if (identity.role() != UserRole.WORKER || identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a worker can claim a design task");
        }
        int updated = jdbcClient.sql("""
                        UPDATE design_task
                        SET assigned_user_id = :userId,
                            claimed_at = CURRENT_TIMESTAMP(3),
                            task_status = 'CLAIMED'
                        WHERE design_task_id = :taskId
                          AND task_status = 'OPEN'
                          AND assigned_user_id IS NULL
                        """)
                .param("userId", identity.userId())
                .param("taskId", taskId)
                .update();
        if (updated != 1) {
            if (!taskExists(taskId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "design task not found");
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design task has already been claimed");
        }
        TaskRow task = loadTaskById(taskId, true);
        recordEvent(task, null, "CLAIM", identity, "OPEN", "CLAIMED", null, identity.userId(), null);
        return toResponse(task, identity, true);
    }

    @Transactional
    public DesignTaskResponse transfer(
            long taskId, DesignTaskTransferRequest request, BootstrapIdentity identity) {
        if (identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only an administrator can transfer a design task");
        }
        if (request.newUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new_user_id is required");
        }
        String reason = normalizeNullable(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
        }
        requireActiveWorker(request.newUserId());
        TaskRow before = loadTaskById(taskId, true);
        if (TERMINAL_TASK_STATUSES.contains(before.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal design task cannot be transferred");
        }
        String targetStatus = "OPEN".equals(before.status()) ? "CLAIMED" : before.status();
        jdbcClient.sql("""
                        UPDATE design_task
                        SET assigned_user_id = :newUserId,
                            claimed_at = CURRENT_TIMESTAMP(3),
                            task_status = :targetStatus
                        WHERE design_task_id = :taskId
                        """)
                .param("newUserId", request.newUserId())
                .param("targetStatus", targetStatus)
                .param("taskId", taskId)
                .update();
        TaskRow after = loadTaskById(taskId, true);
        recordEvent(
                after,
                null,
                "TRANSFER",
                identity,
                before.status(),
                targetStatus,
                before.assignedUserId(),
                request.newUserId(),
                reason);
        emit(after, "DESIGN_TASK_TRANSFERRED", "WORKER", List.of(request.newUserId()), "设计任务已转派：" + reason);
        return toResponse(after, identity, true);
    }

    @Transactional
    public DesignDraftResponse uploadDraft(
            long orderId, DesignDraftRequest request, BootstrapIdentity identity) {
        TaskRow task = loadTaskByOrder(orderId, true);
        requireAssignedWorker(task, identity);
        List<Long> fileIds = normalizeFileIds(request.fileIds());
        if (fileIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file_ids is required");
        }
        String submissionKey = normalizeNullable(request.submissionKey());
        if (submissionKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "submission_key is required");
        }
        if (submissionKey.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "submission_key must not exceed 64 characters");
        }
        Long existingDraftId = findDraftBySubmissionKey(orderId, submissionKey);
        if (existingDraftId != null) {
            requireSameSubmission(existingDraftId, fileIds, request.uploadNote());
            return loadDraft(existingDraftId, identity);
        }
        if (!REVISION_TASK_STATUSES.contains(task.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design task is not ready for a new draft");
        }
        DraftRow latestDraft = loadLatestDraftRow(task.taskId());
        if (latestDraft != null
                && Objects.equals(latestDraft.uploaderUserId(), identity.userId())
                && "PENDING_REVIEW".equals(latestDraft.status())
                && latestDraft.submittedAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "submit the current design draft before creating another version");
        }
        validateDraftFiles(orderId, fileIds, identity);
        int nextVersion = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_no), 0) + 1
                        FROM design_draft
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        try {
            jdbcClient.sql("""
                            INSERT INTO design_draft
                                (order_id, design_task_id, file_id, version_no, draft_status,
                                 uploaded_by_user_id, submission_key, upload_note)
                            VALUES
                                (:orderId, :taskId, :fileId, :versionNo, 'PENDING_REVIEW',
                                 :uploadedByUserId, :submissionKey, :uploadNote)
                            """)
                    .param("orderId", orderId)
                    .param("taskId", task.taskId())
                    .param("fileId", fileIds.get(0))
                    .param("versionNo", nextVersion)
                    .param("uploadedByUserId", identity.userId())
                    .param("submissionKey", submissionKey)
                    .param("uploadNote", normalizeNullable(request.uploadNote()))
                    .update();
        } catch (DuplicateKeyException ex) {
            Long racedDraftId = findDraftBySubmissionKey(orderId, submissionKey);
            if (racedDraftId != null) {
                requireSameSubmission(racedDraftId, fileIds, request.uploadNote());
                return loadDraft(racedDraftId, identity);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft version conflict", ex);
        }
        long draftId = lastInsertId();
        insertDraftFiles(draftId, fileIds);
        if (!"CLAIMED".equals(task.status())) {
            jdbcClient.sql("""
                            UPDATE design_task
                            SET task_status = 'CLAIMED'
                            WHERE design_task_id = :taskId
                            """)
                    .param("taskId", task.taskId())
                    .update();
        }
        recordEvent(
                task,
                draftId,
                "DRAFT_UPLOADED",
                identity,
                task.status(),
                "CLAIMED",
                task.assignedUserId(),
                task.assignedUserId(),
                normalizeNullable(request.uploadNote()));
        return loadDraft(draftId, identity);
    }

    @Transactional
    public DesignDraftResponse submitDraft(long orderId, long draftId, BootstrapIdentity identity) {
        TaskRow task = loadTaskByOrder(orderId, true);
        requireAssignedWorker(task, identity);
        DraftRow draft = loadDraftRow(orderId, draftId, true);
        requireLatestDraft(task.taskId(), draftId);
        if (!"PENDING_REVIEW".equals(draft.status()) || draft.submittedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft has already been submitted");
        }
        if (!Objects.equals(draft.uploaderUserId(), identity.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the draft uploader can submit it");
        }
        int updated = jdbcClient.sql("""
                        UPDATE design_draft
                        SET submitted_at = CURRENT_TIMESTAMP(3)
                        WHERE design_draft_id = :draftId
                          AND submitted_at IS NULL
                          AND draft_status = 'PENDING_REVIEW'
                        """)
                .param("draftId", draftId)
                .update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft has already been submitted");
        }
        jdbcClient.sql("""
                        UPDATE design_task
                        SET task_status = 'SUBMITTED'
                        WHERE design_task_id = :taskId
                        """)
                .param("taskId", task.taskId())
                .update();
        recordEvent(
                task,
                draftId,
                "SUBMIT_INTERNAL_REVIEW",
                identity,
                task.status(),
                "SUBMITTED",
                task.assignedUserId(),
                task.assignedUserId(),
                null);
        emit(task, "DESIGN_DRAFT_SUBMITTED", "WORKER", internalReviewerUserIds(), "设计稿待组长内部审核");
        return loadDraft(draftId, identity);
    }

    @Transactional
    public DesignDraftResponse internalReview(
            long orderId,
            long draftId,
            DesignDraftReviewRequest request,
            BootstrapIdentity identity) {
        requireInternalReviewer(identity);
        TaskRow task = loadTaskByOrder(orderId, true);
        DraftRow draft = loadDraftRow(orderId, draftId, true);
        requireLatestDraft(task.taskId(), draftId);
        if (!Set.of("SUBMITTED", "INTERNAL_REVIEW").contains(task.status())
                || !"PENDING_REVIEW".equals(draft.status())
                || draft.submittedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft is not waiting for internal review");
        }
        String action = normalizeAction(request.action());
        String rejectReason = normalizeNullable(request.resolvedRejectReason());
        if ("REJECT".equals(action) && rejectReason == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "internal_reject_reason is required when rejecting a design draft");
        }
        if (!Set.of("APPROVE", "REJECT").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported internal review action");
        }
        String targetDraftStatus = "APPROVE".equals(action) ? "PENDING_DOCTOR" : "INTERNAL_REJECTED";
        Long targetAssigneeUserId = "REJECT".equals(action)
                ? revisionAssignee(draft.uploaderUserId(), task.assignedUserId())
                : task.assignedUserId();
        String targetTaskStatus = "REJECT".equals(action) && targetAssigneeUserId == null
                ? "OPEN"
                : targetDraftStatus;
        int updated = jdbcClient.sql("""
                        UPDATE design_draft
                        SET draft_status = :targetStatus,
                            cs_reject_reason = :rejectReason,
                            internal_reviewer_user_id = :reviewerUserId,
                            internal_reviewed_at = CURRENT_TIMESTAMP(3),
                            doctor_visible_at = CASE
                                WHEN :targetStatus = 'PENDING_DOCTOR'
                                THEN COALESCE(doctor_visible_at, CURRENT_TIMESTAMP(3))
                                ELSE doctor_visible_at
                            END
                        WHERE design_draft_id = :draftId
                          AND draft_status = 'PENDING_REVIEW'
                          AND submitted_at IS NOT NULL
                        """)
                .param("targetStatus", targetDraftStatus)
                .param("rejectReason", rejectReason)
                .param("reviewerUserId", identity.userId())
                .param("draftId", draftId)
                .update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft review was already completed");
        }
        jdbcClient.sql("""
                        UPDATE design_task
                        SET task_status = :targetStatus,
                            assigned_user_id = :targetAssigneeUserId
                        WHERE design_task_id = :taskId
                        """)
                .param("targetStatus", targetTaskStatus)
                .param("targetAssigneeUserId", targetAssigneeUserId)
                .param("taskId", task.taskId())
                .update();
        if ("APPROVE".equals(action)) {
            exposeDraftFilesToDoctor(draftId);
            requireDoctorReadableDraftFiles(draftId);
            emit(task, "DESIGN_DRAFT_INTERNAL_APPROVED", "DOCTOR", doctorRecipients(task), "设计稿待医生确认");
        } else {
            emit(
                    task,
                    "DESIGN_DRAFT_INTERNAL_REJECTED",
                    "WORKER",
                    singletonRecipient(targetAssigneeUserId),
                    "设计稿内部审核未通过：" + rejectReason);
        }
        recordEvent(
                task,
                draftId,
                "APPROVE".equals(action) ? "INTERNAL_APPROVE" : "INTERNAL_REJECT",
                identity,
                task.status(),
                targetTaskStatus,
                task.assignedUserId(),
                targetAssigneeUserId,
                rejectReason);
        return loadDraft(draftId, identity);
    }

    @Transactional
    public DesignDraftResponse doctorConfirm(
            long orderId,
            long draftId,
            DoctorDraftConfirmRequest request,
            BootstrapIdentity identity) {
        if (identity.role() != UserRole.DOCTOR || identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the order doctor can confirm a design draft");
        }
        TaskRow task = loadTaskByOrder(orderId, true);
        if (!Objects.equals(task.doctorUserId(), identity.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the order doctor can confirm a design draft");
        }
        DraftRow draft = loadDraftRow(orderId, draftId, true);
        requireLatestDraft(task.taskId(), draftId);
        if (!"PENDING_DOCTOR".equals(task.status()) || !"PENDING_DOCTOR".equals(draft.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "design draft is not waiting for doctor confirmation");
        }
        requireDoctorReadableDraftFiles(draftId);
        String action = normalizeAction(request.action());
        String rejectReason = normalizeNullable(request.doctorRejectReason());
        if ("REJECT".equals(action) && rejectReason == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "doctor_reject_reason is required when rejecting a design draft");
        }
        if (!Set.of("CONFIRM", "REJECT").contains(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported doctor confirmation action");
        }
        String targetStatus = "CONFIRM".equals(action) ? "DOCTOR_CONFIRMED" : "DOCTOR_REJECTED";
        Long targetAssigneeUserId = "REJECT".equals(action)
                ? revisionAssignee(draft.uploaderUserId(), task.assignedUserId())
                : task.assignedUserId();
        String targetTaskStatus = "REJECT".equals(action) && targetAssigneeUserId == null
                ? "OPEN"
                : targetStatus;
        int updated = jdbcClient.sql("""
                        UPDATE design_draft
                        SET draft_status = :targetStatus,
                            doctor_confirmed_at = CASE
                                WHEN :targetStatus = 'DOCTOR_CONFIRMED'
                                THEN CURRENT_TIMESTAMP(3)
                                ELSE doctor_confirmed_at
                            END,
                            doctor_reject_reason = :rejectReason
                        WHERE design_draft_id = :draftId
                          AND draft_status = 'PENDING_DOCTOR'
                          AND doctor_visible_at IS NOT NULL
                        """)
                .param("targetStatus", targetStatus)
                .param("rejectReason", rejectReason)
                .param("draftId", draftId)
                .update();
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "doctor confirmation was already completed");
        }
        jdbcClient.sql("""
                        UPDATE design_task
                        SET task_status = :targetStatus,
                            assigned_user_id = :targetAssigneeUserId
                        WHERE design_task_id = :taskId
                          AND task_status = 'PENDING_DOCTOR'
                        """)
                .param("targetStatus", targetTaskStatus)
                .param("targetAssigneeUserId", targetAssigneeUserId)
                .param("taskId", task.taskId())
                .update();
        if ("DOCTOR_CONFIRMED".equals(targetStatus)) {
            completeDesignGateAndActivateRoute(task.taskId());
            ensureOrderStateFrom(
                    orderId,
                    Set.of(InternalOrderStatus.IN_DESIGN.name()),
                    InternalOrderStatus.PROCESS_INSTANCE_CREATED,
                    "DESIGN_DOCTOR_CONFIRMED",
                    identity.userId());
        }
        recordEvent(
                task,
                draftId,
                "DOCTOR_CONFIRMED".equals(targetStatus) ? "DOCTOR_CONFIRM" : "DOCTOR_REJECT",
                identity,
                task.status(),
                targetTaskStatus,
                task.assignedUserId(),
                targetAssigneeUserId,
                rejectReason);
        emit(
                task,
                "DOCTOR_CONFIRMED".equals(targetStatus)
                        ? "DESIGN_DRAFT_DOCTOR_CONFIRMED"
                        : "DESIGN_DRAFT_DOCTOR_REJECTED",
                "INTERNAL",
                doctorDecisionRecipients(task, draftId),
                rejectReason == null ? "医生已确认设计稿" : "医生要求修改设计稿：" + rejectReason);
        return loadDraft(draftId, identity);
    }

    private void completeDesignGateAndActivateRoute(long taskId) {
        int completed = jdbcClient.sql("""
                        UPDATE order_process_node gate_node
                        JOIN design_task task ON task.node_instance_id = gate_node.node_instance_id
                        SET gate_node.node_status = 'COMPLETED',
                            gate_node.started_at = COALESCE(gate_node.started_at, CURRENT_TIMESTAMP(3)),
                            gate_node.completed_at = CURRENT_TIMESTAMP(3)
                        WHERE task.design_task_id = :taskId
                          AND gate_node.node_category = 'DESIGN_GATE'
                          AND gate_node.node_status IN ('PENDING', 'READY', 'IN_PROGRESS')
                        """)
                .param("taskId", taskId)
                .update();
        if (completed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "design confirmation gate is missing or already completed");
        }
        jdbcClient.sql("""
                        UPDATE order_process_node target
                        JOIN design_task task ON task.design_task_id = :taskId
                        JOIN (
                            SELECT ready_nodes.node_instance_id
                            FROM (
                                SELECT candidate.node_instance_id
                                FROM order_process_node candidate
                                JOIN design_task selected_task
                                  ON selected_task.design_task_id = :taskId
                                JOIN order_process_node selected_gate
                                  ON selected_gate.node_instance_id = selected_task.node_instance_id
                                WHERE candidate.instance_id = selected_gate.instance_id
                                  AND candidate.node_status = 'PENDING'
                                  AND NOT EXISTS (
                                      SELECT 1
                                      FROM order_process_edge incoming
                                      JOIN order_process_node predecessor
                                        ON predecessor.node_instance_id = incoming.from_node_instance_id
                                      WHERE incoming.instance_id = candidate.instance_id
                                        AND incoming.to_node_instance_id = candidate.node_instance_id
                                        AND predecessor.node_status NOT IN ('COMPLETED', 'SKIPPED')
                                  )
                            ) ready_nodes
                        ) selected ON selected.node_instance_id = target.node_instance_id
                        SET target.node_status = 'READY'
                        """)
                .param("taskId", taskId)
                .update();
    }

    public List<DesignDraftResponse> listDrafts(long orderId, BootstrapIdentity identity) {
        if (!designTaskExistsForOrder(orderId)) {
            requireReadableOrderWithoutDesignTask(orderId, identity);
            return List.of();
        }
        TaskRow task = loadTaskByOrder(orderId, false);
        requireReadable(task, identity);
        return loadDrafts(task, identity);
    }

    private DesignTaskResponse toResponse(TaskRow task, BootstrapIdentity identity, boolean includeDrafts) {
        List<DesignDraftResponse> drafts = includeDrafts ? loadDrafts(task, identity) : List.of();
        DesignDraftResponse latestDraft = drafts.isEmpty() ? null : drafts.get(drafts.size() - 1);
        boolean externalProgressView =
                identity.role() == UserRole.DOCTOR || identity.role() == UserRole.CS;
        String responseStatus = externalProgressView
                ? externalTaskStatus(task, latestDraft)
                : task.status();
        return new DesignTaskResponse(
                task.taskId(),
                task.orderId(),
                task.orderNo(),
                task.productType(),
                task.orderStatus(),
                responseStatus,
                externalProgressView ? null : task.assignedUserId(),
                externalProgressView ? null : task.assignedUserName(),
                task.claimedAt(),
                task.updatedAt(),
                latestDraft,
                drafts,
                loadEvents(task.taskId(), null, identity),
                allowedActions(task, latestDraft, identity));
    }

    private List<String> allowedActions(
            TaskRow task, DesignDraftResponse latestDraft, BootstrapIdentity identity) {
        List<String> actions = new ArrayList<>();
        boolean assignedWorker = identity.role() == UserRole.WORKER
                && identity.userId() != null
                && Objects.equals(identity.userId(), task.assignedUserId());
        if (identity.role() == UserRole.WORKER
                && "OPEN".equals(task.status())
                && task.assignedUserId() == null) {
            actions.add("CLAIM");
        }
        if (assignedWorker && REVISION_TASK_STATUSES.contains(task.status())) {
            actions.add("UPLOAD_DRAFT");
        }
        if (assignedWorker
                && latestDraft != null
                && "PENDING_REVIEW".equals(latestDraft.status())
                && latestDraft.submittedAt() == null) {
            actions.add("SUBMIT_DRAFT");
        }
        if (canInternalReview(identity)
                && Set.of("SUBMITTED", "INTERNAL_REVIEW").contains(task.status())) {
            actions.add("INTERNAL_REVIEW");
        }
        if (identity.role() == UserRole.ADMIN && !TERMINAL_TASK_STATUSES.contains(task.status())) {
            actions.add("TRANSFER_TASK");
        }
        if (identity.role() == UserRole.DOCTOR
                && Objects.equals(identity.userId(), task.doctorUserId())
                && "PENDING_DOCTOR".equals(task.status())) {
            actions.add("DOCTOR_CONFIRM");
        }
        return List.copyOf(actions);
    }

    private String externalTaskStatus(TaskRow task, DesignDraftResponse latestVisibleDraft) {
        if (latestVisibleDraft == null) {
            return task.orderStatus();
        }
        return switch (latestVisibleDraft.status()) {
            case "PENDING_DOCTOR", "DOCTOR_CONFIRMED", "DOCTOR_REJECTED" -> latestVisibleDraft.status();
            default -> task.orderStatus();
        };
    }

    private List<DesignDraftResponse> loadDrafts(TaskRow task, BootstrapIdentity identity) {
        String visibilityFilter = "";
        if (identity.role() == UserRole.DOCTOR || identity.role() == UserRole.CS) {
            visibilityFilter = "AND doctor_visible_at IS NOT NULL";
        } else if (identity.role() == UserRole.WORKER && !canInternalReview(identity)) {
            visibilityFilter = "AND uploaded_by_user_id = :viewerUserId";
        }
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        SELECT design_draft_id, order_id, version_no, uploaded_by_user_id, file_id,
                               draft_status, upload_note, submission_key, submitted_at, doctor_visible_at,
                               cs_reject_reason, doctor_reject_reason
                        FROM design_draft
                        WHERE design_task_id = :taskId
                          %s
                        ORDER BY version_no, design_draft_id
                        """.formatted(visibilityFilter))
                .param("taskId", task.taskId());
        if (visibilityFilter.contains("viewerUserId")) {
            statement = statement.param("viewerUserId", identity.userId());
        }
        return statement
                .query((rs, rowNum) -> mapDraft(
                        rs.getLong("design_draft_id"),
                        rs.getLong("order_id"),
                        rs.getInt("version_no"),
                        rs.getObject("uploaded_by_user_id", Long.class),
                        rs.getObject("file_id", Long.class),
                        rs.getString("draft_status"),
                        rs.getString("upload_note"),
                        rs.getString("submission_key"),
                        rs.getObject("submitted_at", LocalDateTime.class),
                        rs.getObject("doctor_visible_at", LocalDateTime.class),
                        rs.getString("cs_reject_reason"),
                        rs.getString("doctor_reject_reason"),
                        identity))
                .list();
    }

    private DesignDraftResponse loadDraft(long draftId, BootstrapIdentity identity) {
        try {
            return jdbcClient.sql("""
                            SELECT design_draft_id, order_id, version_no, uploaded_by_user_id, file_id,
                                   draft_status, upload_note, submission_key, submitted_at, doctor_visible_at,
                                   cs_reject_reason, doctor_reject_reason
                            FROM design_draft
                            WHERE design_draft_id = :draftId
                            """)
                    .param("draftId", draftId)
                    .query((rs, rowNum) -> mapDraft(
                            rs.getLong("design_draft_id"),
                            rs.getLong("order_id"),
                            rs.getInt("version_no"),
                            rs.getObject("uploaded_by_user_id", Long.class),
                            rs.getObject("file_id", Long.class),
                            rs.getString("draft_status"),
                            rs.getString("upload_note"),
                            rs.getString("submission_key"),
                            rs.getObject("submitted_at", LocalDateTime.class),
                            rs.getObject("doctor_visible_at", LocalDateTime.class),
                            rs.getString("cs_reject_reason"),
                            rs.getString("doctor_reject_reason"),
                            identity))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "design draft not found", ex);
        }
    }

    private DesignDraftResponse mapDraft(
            long draftId,
            long orderId,
            int version,
            Long uploaderUserId,
            Long primaryFileId,
            String status,
            String uploadNote,
            String submissionKey,
            LocalDateTime submittedAt,
            LocalDateTime doctorVisibleAt,
            String internalRejectReason,
            String doctorRejectReason,
            BootstrapIdentity identity) {
        List<Long> fileIds = loadDraftFileIds(draftId);
        if (fileIds.isEmpty() && primaryFileId != null) {
            fileIds = List.of(primaryFileId);
        }
        boolean externalProgressView =
                identity.role() == UserRole.DOCTOR || identity.role() == UserRole.CS;
        return new DesignDraftResponse(
                draftId,
                orderId,
                version,
                externalProgressView ? null : uploaderUserId,
                primaryFileId,
                fileIds,
                fileIds.size(),
                status,
                externalProgressView ? null : uploadNote,
                externalProgressView ? null : submissionKey,
                submittedAt,
                doctorVisibleAt,
                externalProgressView ? null : internalRejectReason,
                externalProgressView ? null : internalRejectReason,
                doctorRejectReason,
                loadEvents(null, draftId, identity));
    }

    private List<Long> loadDraftFileIds(long draftId) {
        return jdbcClient.sql("""
                        SELECT file_id
                        FROM design_draft_file
                        WHERE design_draft_id = :draftId
                        ORDER BY sort_order, design_draft_file_id
                        """)
                .param("draftId", draftId)
                .query(Long.class)
                .list();
    }

    private List<DesignDraftReviewEventResponse> loadEvents(
            Long taskId, Long draftId, BootstrapIdentity identity) {
        String filter = draftId == null ? "design_task_id = :taskId" : "design_draft_id = :draftId";
        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                        SELECT event_id, design_draft_id, event_type, actor_user_id, actor_role,
                               from_status, to_status, from_assignee_user_id, to_assignee_user_id,
                               reason, created_at
                        FROM design_task_event
                        WHERE %s
                        ORDER BY created_at, event_id
                        """.formatted(filter));
        statement = draftId == null
                ? statement.param("taskId", taskId)
                : statement.param("draftId", draftId);
        List<DesignDraftReviewEventResponse> events = statement
                .query((rs, rowNum) -> new DesignDraftReviewEventResponse(
                        rs.getLong("event_id"),
                        rs.getObject("design_draft_id", Long.class),
                        rs.getString("event_type"),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getString("actor_role"),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getObject("from_assignee_user_id", Long.class),
                        rs.getObject("to_assignee_user_id", Long.class),
                        rs.getString("reason"),
                        rs.getObject("created_at", LocalDateTime.class)))
                .list();
        if (identity.role() != UserRole.DOCTOR && identity.role() != UserRole.CS) {
            return events;
        }
        return events.stream()
                .filter(event -> Set.of("DOCTOR_CONFIRM", "DOCTOR_REJECT").contains(event.eventType()))
                .map(event -> new DesignDraftReviewEventResponse(
                        event.eventId(),
                        event.draftId(),
                        event.eventType(),
                        null,
                        null,
                        event.fromStatus(),
                        event.toStatus(),
                        null,
                        null,
                        event.eventType().startsWith("DOCTOR_") ? event.reason() : null,
                        event.createdAt()))
                .toList();
    }

    private TaskRow loadTaskByOrder(long orderId, boolean lock) {
        String lockClause = lock ? "FOR UPDATE" : "";
        try {
            return jdbcClient.sql("""
                            SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                                   o.external_status AS order_status, o.doctor_user_id,
                                   dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                                   dt.claimed_at, dt.updated_at
                            FROM design_task dt
                            JOIN orders o ON o.order_id = dt.order_id
                            LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                            WHERE dt.order_id = :orderId
                            %s
                            """.formatted(lockClause))
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> mapTaskRow(rs))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            if (orderExists(orderId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "design task not found", ex);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found", ex);
        }
    }

    private TaskRow loadTaskById(long taskId, boolean lock) {
        String lockClause = lock ? "FOR UPDATE" : "";
        try {
            return jdbcClient.sql("""
                            SELECT dt.design_task_id, dt.order_id, o.order_no, o.product_type,
                                   o.external_status AS order_status, o.doctor_user_id,
                                   dt.task_status, dt.assigned_user_id, u.display_name AS assigned_user_name,
                                   dt.claimed_at, dt.updated_at
                            FROM design_task dt
                            JOIN orders o ON o.order_id = dt.order_id
                            LEFT JOIN system_user u ON u.user_id = dt.assigned_user_id
                            WHERE dt.design_task_id = :taskId
                            %s
                            """.formatted(lockClause))
                    .param("taskId", taskId)
                    .query((rs, rowNum) -> mapTaskRow(rs))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "design task not found", ex);
        }
    }

    private TaskRow mapTaskRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRow(
                rs.getLong("design_task_id"),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("product_type"),
                rs.getString("order_status"),
                rs.getObject("doctor_user_id", Long.class),
                rs.getString("task_status"),
                rs.getObject("assigned_user_id", Long.class),
                rs.getString("assigned_user_name"),
                rs.getObject("claimed_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private DraftRow loadDraftRow(long orderId, long draftId, boolean lock) {
        String lockClause = lock ? "FOR UPDATE" : "";
        try {
            return jdbcClient.sql("""
                            SELECT design_draft_id, design_task_id, order_id, uploaded_by_user_id,
                                   draft_status, submitted_at
                            FROM design_draft
                            WHERE order_id = :orderId
                              AND design_draft_id = :draftId
                            %s
                            """.formatted(lockClause))
                    .param("orderId", orderId)
                    .param("draftId", draftId)
                    .query((rs, rowNum) -> new DraftRow(
                            rs.getLong("design_draft_id"),
                            rs.getObject("design_task_id", Long.class),
                            rs.getLong("order_id"),
                            rs.getObject("uploaded_by_user_id", Long.class),
                            rs.getString("draft_status"),
                            rs.getObject("submitted_at", LocalDateTime.class)))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "design draft not found", ex);
        }
    }

    private DraftRow loadLatestDraftRow(long taskId) {
        return jdbcClient.sql("""
                        SELECT design_draft_id, design_task_id, order_id, uploaded_by_user_id,
                               draft_status, submitted_at
                        FROM design_draft
                        WHERE design_task_id = :taskId
                        ORDER BY version_no DESC, design_draft_id DESC
                        LIMIT 1
                        """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new DraftRow(
                        rs.getLong("design_draft_id"),
                        rs.getObject("design_task_id", Long.class),
                        rs.getLong("order_id"),
                        rs.getObject("uploaded_by_user_id", Long.class),
                        rs.getString("draft_status"),
                        rs.getObject("submitted_at", LocalDateTime.class)))
                .optional()
                .orElse(null);
    }

    private void requireLatestDraft(long taskId, long draftId) {
        Long latestDraftId = jdbcClient.sql("""
                        SELECT design_draft_id
                        FROM design_draft
                        WHERE design_task_id = :taskId
                        ORDER BY version_no DESC, design_draft_id DESC
                        LIMIT 1
                        """)
                .param("taskId", taskId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (!Objects.equals(latestDraftId, draftId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only the latest design draft can be processed");
        }
    }

    private void validateDraftFiles(
            long orderId, List<Long> fileIds, BootstrapIdentity identity) {
        for (Long fileId : fileIds) {
            FileBindingRow file;
            try {
                file = jdbcClient.sql("""
                                SELECT file_id, order_id, owner_user_id, source_type, visibility,
                                       upload_status, status
                                FROM file_resource
                                WHERE file_id = :fileId
                                FOR UPDATE
                                """)
                        .param("fileId", fileId)
                        .query((rs, rowNum) -> new FileBindingRow(
                                rs.getLong("file_id"),
                                rs.getObject("order_id", Long.class),
                                rs.getObject("owner_user_id", Long.class),
                                rs.getString("source_type"),
                                rs.getString("visibility"),
                                rs.getString("upload_status"),
                                rs.getString("status")))
                        .single();
            } catch (EmptyResultDataAccessException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "design draft file not found", ex);
            }
            if (!Objects.equals(file.orderId(), orderId)
                    || !Objects.equals(file.ownerUserId(), identity.userId())
                    || !"DESIGN_DRAFT".equals(file.sourceType())
                    || !"INTERNAL".equals(file.visibility())
                    || !"COMPLETED".equals(file.uploadStatus())
                    || !"ACTIVE".equals(file.status())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "design draft files must be completed internal files owned by the assignee");
            }
        }
    }

    private Long findDraftBySubmissionKey(long orderId, String submissionKey) {
        return jdbcClient.sql("""
                        SELECT design_draft_id
                        FROM design_draft
                        WHERE order_id = :orderId
                          AND submission_key = :submissionKey
                        """)
                .param("orderId", orderId)
                .param("submissionKey", submissionKey)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private void requireSameSubmission(long draftId, List<Long> expectedFileIds, String uploadNote) {
        List<Long> actualFileIds = loadDraftFileIds(draftId);
        String actualUploadNote = jdbcClient.sql("""
                        SELECT upload_note
                        FROM design_draft
                        WHERE design_draft_id = :draftId
                        """)
                .param("draftId", draftId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (!actualFileIds.equals(expectedFileIds)
                || !Objects.equals(normalizeNullable(actualUploadNote), normalizeNullable(uploadNote))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "submission_key was already used with a different draft payload");
        }
    }

    private void insertDraftFiles(long draftId, List<Long> fileIds) {
        for (int index = 0; index < fileIds.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO design_draft_file (design_draft_id, file_id, sort_order)
                            VALUES (:draftId, :fileId, :sortOrder)
                            """)
                    .param("draftId", draftId)
                    .param("fileId", fileIds.get(index))
                    .param("sortOrder", index)
                    .update();
        }
    }

    private void exposeDraftFilesToDoctor(long draftId) {
        jdbcClient.sql("""
                        UPDATE file_resource f
                        JOIN design_draft_file ddf ON ddf.file_id = f.file_id
                        SET f.visibility = 'DOCTOR_CS'
                        WHERE ddf.design_draft_id = :draftId
                          AND f.visibility = 'INTERNAL'
                        """)
                .param("draftId", draftId)
                .update();
    }

    private void requireDoctorReadableDraftFiles(long draftId) {
        long totalCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_draft_file
                        WHERE design_draft_id = :draftId
                        """)
                .param("draftId", draftId)
                .query(Long.class)
                .single();
        long readableCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_draft_file ddf
                        JOIN file_resource f ON f.file_id = ddf.file_id
                        WHERE ddf.design_draft_id = :draftId
                          AND f.visibility = 'DOCTOR_CS'
                          AND f.upload_status = 'COMPLETED'
                          AND f.status = 'ACTIVE'
                        """)
                .param("draftId", draftId)
                .query(Long.class)
                .single();
        if (totalCount == 0 || readableCount != totalCount) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "all design draft files must be active and doctor-readable before confirmation");
        }
    }

    private void recordEvent(
            TaskRow task,
            Long draftId,
            String eventType,
            BootstrapIdentity actor,
            String fromStatus,
            String toStatus,
            Long fromAssigneeUserId,
            Long toAssigneeUserId,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO design_task_event
                            (design_task_id, design_draft_id, event_type, actor_user_id, actor_role,
                             from_status, to_status, from_assignee_user_id, to_assignee_user_id, reason)
                        VALUES
                            (:taskId, :draftId, :eventType, :actorUserId, :actorRole,
                             :fromStatus, :toStatus, :fromAssigneeUserId, :toAssigneeUserId, :reason)
                        """)
                .param("taskId", task.taskId())
                .param("draftId", draftId)
                .param("eventType", eventType)
                .param("actorUserId", actor == null ? null : actor.userId())
                .param("actorRole", actor == null ? "SYSTEM" : actor.role().name())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("fromAssigneeUserId", fromAssigneeUserId)
                .param("toAssigneeUserId", toAssigneeUserId)
                .param("reason", reason)
                .update();
    }

    private void emit(
            TaskRow task,
            String eventType,
            String audienceRole,
            List<Long> recipientUserIds,
            String message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new NotificationPayload(
                    eventType, task.orderId(), task.orderNo(), message));
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to build notification payload", ex);
        }
        jdbcClient.sql("""
                        INSERT INTO notification_event
                            (order_id, event_type, audience_role, payload, delivery_status)
                        VALUES
                            (:orderId, :eventType, :audienceRole, CAST(:payload AS JSON), 'PENDING')
                        """)
                .param("orderId", task.orderId())
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .param("payload", payload)
                .update();
        long eventId = lastInsertId();
        for (Long userId : recipientUserIds.stream().filter(Objects::nonNull).distinct().toList()) {
            jdbcClient.sql("""
                            INSERT IGNORE INTO user_notification (event_id, user_id)
                            VALUES (:eventId, :userId)
                            """)
                    .param("eventId", eventId)
                    .param("userId", userId)
                    .update();
        }
    }

    private List<Long> internalReviewerUserIds() {
        return jdbcClient.sql("""
                        SELECT DISTINCT u.user_id
                        FROM system_user u
                        JOIN system_user_role ur ON ur.user_id = u.user_id
                        JOIN system_role r ON r.role_id = ur.role_id
                        LEFT JOIN system_user_permission up ON up.user_id = u.user_id
                        LEFT JOIN system_permission direct_p ON direct_p.permission_id = up.permission_id
                        WHERE u.status = 'ACTIVE'
                          AND (
                              r.role_code = 'ADMIN'
                              OR direct_p.permission_code = 'design-draft:internal-review'
                          )
                        ORDER BY u.user_id
                        """)
                .query(Long.class)
                .list();
    }

    private List<Long> doctorRecipients(TaskRow task) {
        return singletonRecipient(task.doctorUserId());
    }

    private List<Long> doctorDecisionRecipients(TaskRow task, long draftId) {
        return jdbcClient.sql("""
                        SELECT recipient_user_id
                        FROM (
                            SELECT assigned_user_id AS recipient_user_id
                            FROM design_task
                            WHERE design_task_id = :taskId
                            UNION
                            SELECT internal_reviewer_user_id AS recipient_user_id
                            FROM design_draft
                            WHERE design_draft_id = :draftId
                            UNION
                            SELECT cs_user_id AS recipient_user_id
                            FROM orders
                            WHERE order_id = :orderId
                        ) recipients
                        WHERE recipient_user_id IS NOT NULL
                        ORDER BY recipient_user_id
                        """)
                .param("taskId", task.taskId())
                .param("draftId", draftId)
                .param("orderId", task.orderId())
                .query(Long.class)
                .list();
    }

    private List<Long> singletonRecipient(Long userId) {
        return userId == null ? List.of() : List.of(userId);
    }

    private void ensureOrderStateFrom(
            long orderId,
            Set<String> allowedCurrentStatuses,
            InternalOrderStatus targetStatus,
            String eventType,
            Long actorUserId) {
        String current = jdbcClient.sql("""
                        SELECT internal_status
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        if (!targetStatus.name().equals(current) && allowedCurrentStatuses.contains(current)) {
            orderStatusService.updateOrderState(orderId, targetStatus, eventType, actorUserId, null);
        }
    }

    private void requireReadable(TaskRow task, BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN || canInternalReview(identity)) {
            return;
        }
        if (identity.role() == UserRole.CS) {
            return;
        }
        if (identity.role() == UserRole.DOCTOR) {
            if (identity.userId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "doctor cannot access this design task");
            }
            if (Objects.equals(identity.userId(), task.doctorUserId())
                    || (identity.clinicId() != null && doctorClinicMatches(task.orderId(), identity.clinicId()))) {
                return;
            }
        }
        if (identity.role() == UserRole.WORKER
                && identity.userId() != null
                && Objects.equals(identity.userId(), task.assignedUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identity cannot access this design task");
    }

    private boolean doctorClinicMatches(long orderId, Long clinicId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE order_id = :orderId
                          AND clinic_id = :clinicId
                        """)
                .param("orderId", orderId)
                .param("clinicId", clinicId)
                .query(Long.class)
                .single() > 0;
    }

    private void requireAssignedWorker(TaskRow task, BootstrapIdentity identity) {
        if (identity.role() != UserRole.WORKER
                || identity.userId() == null
                || !Objects.equals(identity.userId(), task.assignedUserId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "only the assigned design worker can operate this task");
        }
    }

    private void requireWorkerOrAdmin(BootstrapIdentity identity) {
        if (identity.role() != UserRole.WORKER && identity.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "design task access requires WORKER or ADMIN");
        }
    }

    private void requireInternalReviewer(BootstrapIdentity identity) {
        if (!canInternalReview(identity)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "design internal review permission is required");
        }
    }

    private boolean canInternalReview(BootstrapIdentity identity) {
        return identity.role() == UserRole.WORKER
                && identity.hasPermission("design-draft:internal-review");
    }

    private void requireActiveWorker(long userId) {
        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_user u
                        JOIN system_user_role ur ON ur.user_id = u.user_id
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE u.user_id = :userId
                          AND u.status = 'ACTIVE'
                          AND r.status = 'ACTIVE'
                          AND r.role_code = 'WORKER'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new_user_id must reference an active worker");
        }
    }

    private Long revisionAssignee(Long preferredUserId, Long fallbackUserId) {
        if (isActiveWorker(preferredUserId)) {
            return preferredUserId;
        }
        if (isActiveWorker(fallbackUserId)) {
            return fallbackUserId;
        }
        return null;
    }

    private boolean isActiveWorker(Long userId) {
        if (userId == null) {
            return false;
        }
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_user u
                        JOIN system_user_role ur ON ur.user_id = u.user_id
                        JOIN system_role r ON r.role_id = ur.role_id
                        WHERE u.user_id = :userId
                          AND u.status = 'ACTIVE'
                          AND r.status = 'ACTIVE'
                          AND r.role_code = 'WORKER'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single() > 0;
    }

    private boolean taskExists(long taskId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM design_task WHERE design_task_id = :taskId")
                .param("taskId", taskId)
                .query(Long.class)
                .single() > 0;
    }

    private boolean designTaskExistsForOrder(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM design_task WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private void requireReadableOrderWithoutDesignTask(long orderId, BootstrapIdentity identity) {
        if (!orderExists(orderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found");
        }
        if (identity.role() == UserRole.ADMIN || identity.role() == UserRole.CS) {
            return;
        }
        if (identity.role() == UserRole.DOCTOR
                && identity.userId() != null
                && (doctorOwnsOrder(orderId, identity.userId())
                        || (identity.clinicId() != null && doctorClinicMatches(orderId, identity.clinicId())))) {
            return;
        }
        if (identity.role() == UserRole.WORKER && identity.userId() != null) {
            long assignedNodeCount = jdbcClient.sql("""
                            SELECT COUNT(*)
                            FROM order_process_instance i
                            JOIN order_process_node n ON n.instance_id = i.instance_id
                            WHERE i.order_id = :orderId
                              AND n.assigned_user_id = :userId
                            """)
                    .param("orderId", orderId)
                    .param("userId", identity.userId())
                    .query(Long.class)
                    .single();
            if (assignedNodeCount > 0) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "identity cannot access this order's design drafts");
    }

    private boolean doctorOwnsOrder(long orderId, long doctorUserId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE order_id = :orderId
                          AND doctor_user_id = :doctorUserId
                        """)
                .param("orderId", orderId)
                .param("doctorUserId", doctorUserId)
                .query(Long.class)
                .single() > 0;
    }

    private boolean orderExists(long orderId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0;
    }

    private List<Long> normalizeFileIds(List<Long> rawFileIds) {
        if (rawFileIds == null) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long fileId : rawFileIds) {
            if (fileId != null && fileId > 0) {
                seen.add(fileId);
            }
        }
        return List.copyOf(seen);
    }

    private String normalizeAction(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private long lastInsertId() {
        return jdbcClient.sql("SELECT LAST_INSERT_ID()")
                .query(Long.class)
                .single();
    }

    private record TaskRow(
            long taskId,
            long orderId,
            String orderNo,
            String productType,
            String orderStatus,
            Long doctorUserId,
            String status,
            Long assignedUserId,
            String assignedUserName,
            LocalDateTime claimedAt,
            LocalDateTime updatedAt) {
    }

    private record DraftRow(
            long draftId,
            Long taskId,
            long orderId,
            Long uploaderUserId,
            String status,
            LocalDateTime submittedAt) {
    }

    private record FileBindingRow(
            long fileId,
            Long orderId,
            Long ownerUserId,
            String sourceType,
            String visibility,
            String uploadStatus,
            String status) {
    }

    private record NotificationPayload(String event, long orderId, String orderNo, String message) {
    }
}
