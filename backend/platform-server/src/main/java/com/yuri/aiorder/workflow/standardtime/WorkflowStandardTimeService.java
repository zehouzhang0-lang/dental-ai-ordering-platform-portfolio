package com.yuri.aiorder.workflow.standardtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.BulkUpdateRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.CopyVersionRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.NodeTimeResponse;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.PublishRequest;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.StandardTimeItemUpdate;
import com.yuri.aiorder.workflow.standardtime.WorkflowStandardTimeModels.VersionResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowStandardTimeService {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final WorkflowStandardTimeProperties properties;

    public WorkflowStandardTimeService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            WorkflowStandardTimeProperties properties) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<VersionResponse> listVersions(BootstrapIdentity identity) {
        requireManage(identity);
        return jdbcClient.sql("""
                        SELECT standard_time_version_id, version_no, version_name,
                               publication_status, effective_at, lock_version
                        FROM workflow_standard_time_version
                        ORDER BY version_no DESC
                        """)
                .query((rs, rowNum) -> mapVersion(rs))
                .list();
    }

    @Transactional
    public VersionResponse copyVersion(CopyVersionRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        Long sourceVersionId = request.sourceVersionId();
        if (sourceVersionId != null) {
            loadVersion(sourceVersionId);
        }
        int nextVersion = jdbcClient.sql("""
                        SELECT version_no + 1
                        FROM workflow_standard_time_version
                        ORDER BY version_no DESC
                        LIMIT 1
                        FOR UPDATE
                        """)
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO workflow_standard_time_version
                            (version_no, version_name, publication_status,
                             based_on_version_id, created_by_user_id)
                        VALUES
                            (:versionNo, :versionName, 'DRAFT',
                             :sourceVersionId, :userId)
                        """)
                .param("versionNo", nextVersion)
                .param("versionName", request.versionName().trim())
                .param("sourceVersionId", sourceVersionId)
                .param("userId", identity.userId())
                .update();
        long versionId = jdbcClient.sql("""
                        SELECT standard_time_version_id
                        FROM workflow_standard_time_version
                        WHERE version_no = :versionNo
                        """)
                .param("versionNo", nextVersion)
                .query(Long.class)
                .single();
        if (sourceVersionId == null) {
            jdbcClient.sql("""
                            INSERT INTO workflow_standard_time_item
                                (standard_time_version_id, chain_id, node_id,
                                 standard_duration_minutes)
                            SELECT :versionId, node.chain_id, node.node_id, NULL
                            FROM workflow_node node
                            ORDER BY node.chain_id, node.step_order, node.node_id
                            """)
                    .param("versionId", versionId)
                    .update();
        } else {
            jdbcClient.sql("""
                            INSERT INTO workflow_standard_time_item
                                (standard_time_version_id, chain_id, node_id,
                                 standard_duration_minutes, status)
                            SELECT :versionId, source.chain_id, source.node_id,
                                   source.standard_duration_minutes, source.status
                            FROM workflow_standard_time_item source
                            WHERE source.standard_time_version_id = :sourceVersionId
                            """)
                    .param("versionId", versionId)
                    .param("sourceVersionId", sourceVersionId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO workflow_standard_time_item
                                (standard_time_version_id, chain_id, node_id,
                                 standard_duration_minutes)
                            SELECT :versionId, node.chain_id, node.node_id, NULL
                            FROM workflow_node node
                            WHERE NOT EXISTS (
                                SELECT 1
                                FROM workflow_standard_time_item existing
                                WHERE existing.standard_time_version_id = :versionId
                                  AND existing.node_id = node.node_id
                            )
                            """)
                    .param("versionId", versionId)
                    .update();
        }
        audit(versionId, null, "COPY_VERSION", null, loadVersion(versionId), identity.userId(), null);
        return loadVersion(versionId);
    }

    @Transactional(readOnly = true)
    public List<NodeTimeResponse> listNodes(long versionId, BootstrapIdentity identity) {
        requireManage(identity);
        loadVersion(versionId);
        return jdbcClient.sql("""
                        SELECT item.standard_time_item_id,
                               chain.chain_id, chain.chain_code, chain.chain_name, chain.product_type,
                               node.node_id, node.node_code, node.process_name,
                               node.stage_name, node.step_order,
                               item.standard_duration_minutes, item.status, item.lock_version
                        FROM workflow_standard_time_item item
                        JOIN workflow_chain chain ON chain.chain_id = item.chain_id
                        JOIN workflow_node node ON node.node_id = item.node_id
                        WHERE item.standard_time_version_id = :versionId
                        ORDER BY chain.product_type, chain.version DESC,
                                 node.step_order, node.node_id
                        """)
                .param("versionId", versionId)
                .query((rs, rowNum) -> new NodeTimeResponse(
                        rs.getLong("standard_time_item_id"),
                        rs.getLong("chain_id"),
                        rs.getString("chain_code"),
                        rs.getString("chain_name"),
                        rs.getString("product_type"),
                        rs.getLong("node_id"),
                        rs.getString("node_code"),
                        rs.getString("process_name"),
                        rs.getString("stage_name"),
                        rs.getInt("step_order"),
                        rs.getObject("standard_duration_minutes", Integer.class),
                        rs.getString("status"),
                        rs.getInt("lock_version")))
                .list();
    }

    @Transactional
    public List<NodeTimeResponse> bulkUpdate(
            long versionId, BulkUpdateRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        requireDraft(versionId);
        if (request.items().isEmpty()) {
            return listNodes(versionId, identity);
        }
        for (StandardTimeItemUpdate item : request.items()) {
            if (!"ACTIVE".equals(item.status()) && !"INACTIVE".equals(item.status())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "standard time item status must be ACTIVE or INACTIVE");
            }
            NodeSnapshot before = loadItem(versionId, item.nodeId());
            int updated = jdbcClient.sql("""
                            UPDATE workflow_standard_time_item
                            SET standard_duration_minutes = :minutes,
                                status = :status,
                                lock_version = lock_version + 1
                            WHERE standard_time_version_id = :versionId
                              AND node_id = :nodeId
                              AND lock_version = :lockVersion
                            """)
                    .param("minutes", item.standardDurationMinutes())
                    .param("status", item.status())
                    .param("versionId", versionId)
                    .param("nodeId", item.nodeId())
                    .param("lockVersion", item.lockVersion())
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "standard time item changed concurrently; refresh and retry");
            }
            NodeSnapshot after = loadItem(versionId, item.nodeId());
            audit(
                    versionId,
                    before.itemId(),
                    "UPDATE",
                    before,
                    after,
                    identity.userId(),
                    request.reason());
        }
        return listNodes(versionId, identity);
    }

    @Transactional
    public VersionResponse publish(long versionId, PublishRequest request, BootstrapIdentity identity) {
        requireManage(identity);
        if (!properties.formalEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "formal standard time data is not enabled; keep the version as draft");
        }
        VersionResponse before = loadVersion(versionId);
        requireDraft(versionId);
        LocalDateTime databaseNow = jdbcClient.sql("SELECT CURRENT_TIMESTAMP(3)")
                .query(LocalDateTime.class)
                .single();
        LocalDateTime effectiveAt = Objects.requireNonNullElse(request.effectiveAt(), databaseNow);
        int updated = jdbcClient.sql("""
                        UPDATE workflow_standard_time_version
                        SET publication_status = 'ACTIVE',
                            effective_at = :effectiveAt,
                            published_at = CURRENT_TIMESTAMP(3),
                            published_by_user_id = :userId,
                            lock_version = lock_version + 1
                        WHERE standard_time_version_id = :versionId
                          AND publication_status = 'DRAFT'
                          AND lock_version = :lockVersion
                        """)
                .param("effectiveAt", effectiveAt)
                .param("userId", identity.userId())
                .param("versionId", versionId)
                .param("lockVersion", request.lockVersion())
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "standard time version changed concurrently; refresh and retry");
        }
        if (!effectiveAt.isAfter(databaseNow)) {
            jdbcClient.sql("""
                            UPDATE workflow_standard_time_version
                            SET publication_status = 'INACTIVE',
                                lock_version = lock_version + 1
                            WHERE standard_time_version_id <> :versionId
                              AND publication_status = 'ACTIVE'
                              AND (effective_at IS NULL OR effective_at <= :effectiveAt)
                            """)
                    .param("versionId", versionId)
                    .param("effectiveAt", effectiveAt)
                    .update();
        }
        VersionResponse after = loadVersion(versionId);
        audit(versionId, null, "PUBLISH", before, after, identity.userId(), request.reason());
        return after;
    }

    private void requireManage(BootstrapIdentity identity) {
        if (identity.role() == UserRole.ADMIN
                || identity.hasPermission("workflow:standard-time:manage")) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "workflow:standard-time:manage permission is required");
    }

    private void requireDraft(long versionId) {
        if (!"DRAFT".equals(loadVersion(versionId).publicationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "published standard time versions are immutable");
        }
    }

    private VersionResponse loadVersion(long versionId) {
        try {
            return jdbcClient.sql("""
                            SELECT standard_time_version_id, version_no, version_name,
                                   publication_status, effective_at, lock_version
                            FROM workflow_standard_time_version
                            WHERE standard_time_version_id = :versionId
                            """)
                    .param("versionId", versionId)
                    .query((rs, rowNum) -> mapVersion(rs))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "standard time version not found", ex);
        }
    }

    private VersionResponse mapVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VersionResponse(
                rs.getLong("standard_time_version_id"),
                rs.getInt("version_no"),
                rs.getString("version_name"),
                rs.getString("publication_status"),
                rs.getObject("effective_at", LocalDateTime.class),
                rs.getInt("lock_version"),
                properties.formalEnabled());
    }

    private NodeSnapshot loadItem(long versionId, long nodeId) {
        try {
            return jdbcClient.sql("""
                            SELECT standard_time_item_id, standard_duration_minutes,
                                   status, lock_version
                            FROM workflow_standard_time_item
                            WHERE standard_time_version_id = :versionId
                              AND node_id = :nodeId
                            """)
                    .param("versionId", versionId)
                    .param("nodeId", nodeId)
                    .query((rs, rowNum) -> new NodeSnapshot(
                            rs.getLong("standard_time_item_id"),
                            nodeId,
                            rs.getObject("standard_duration_minutes", Integer.class),
                            rs.getString("status"),
                            rs.getInt("lock_version")))
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "node is not present in this standard time version", ex);
        }
    }

    private void audit(
            Long versionId,
            Long itemId,
            String action,
            Object before,
            Object after,
            Long operatorUserId,
            String reason) {
        jdbcClient.sql("""
                        INSERT INTO workflow_standard_time_audit
                            (standard_time_version_id, standard_time_item_id, action_type,
                             before_value, after_value, operator_user_id, reason)
                        VALUES
                            (:versionId, :itemId, :action,
                             CAST(:beforeValue AS JSON), CAST(:afterValue AS JSON),
                             :operatorUserId, :reason)
                        """)
                .param("versionId", versionId)
                .param("itemId", itemId)
                .param("action", action)
                .param("beforeValue", json(before))
                .param("afterValue", json(after))
                .param("operatorUserId", operatorUserId)
                .param("reason", reason)
                .update();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize standard time audit", ex);
        }
    }

    private record NodeSnapshot(
            long itemId,
            long nodeId,
            Integer standardDurationMinutes,
            String status,
            int lockVersion) {
    }
}
