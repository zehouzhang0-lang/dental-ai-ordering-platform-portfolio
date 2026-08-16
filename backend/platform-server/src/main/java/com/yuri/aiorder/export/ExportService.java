package com.yuri.aiorder.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.BusinessTime;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.export.ExportDatasetCatalog.Dataset;
import com.yuri.aiorder.export.ExportModels.ApproveExportRequest;
import com.yuri.aiorder.export.ExportModels.CreateExportRequest;
import com.yuri.aiorder.export.ExportModels.DatasetResponse;
import com.yuri.aiorder.export.ExportModels.ExportAuditResponse;
import com.yuri.aiorder.export.ExportModels.ExportRequestResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 导出管控。
 *
 * <p>客户三条要求逐条落地：
 * <ul>
 *   <li>「不允许医生直接导出」——医生端角色一个导出权限码都没有（V82），
 *       医生端前端的两个 CSV 按钮也一并摘掉；</li>
 *   <li>「客户信息、地址、账单的导出是需要批准的」——这四类在 {@code export_dataset}
 *       标为 SENSITIVE，必须由**他人**批准后才能下载；</li>
 *   <li>「别的数据需要导出留痕」——每次实际下载都写一条 {@code export_audit}，
 *       含操作人、时间、导出范围、行数、字段清单。</li>
 * </ul>
 *
 * <p>「导出需要反复确认」落成两道：接口层要求显式 {@code acknowledged=true}（界面上的
 * 确认框绕得过去，接口调用绕不过去），敏感类再叠一道他人审批。
 */
@Service
public class ExportService {

    public static final String APPROVAL_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_APPROVED = "APPROVED";
    public static final String APPROVAL_REJECTED = "REJECTED";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final ExportDatasetCatalog catalog;
    private final ExportDataProvider dataProvider;

    public ExportService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            ExportDatasetCatalog catalog,
            ExportDataProvider dataProvider) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.catalog = catalog;
        this.dataProvider = dataProvider;
    }

    public List<DatasetResponse> listDatasets(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_EXECUTE,
                "data export requires export:execute");
        return catalog.listActive().stream()
                .map(dataset -> new DatasetResponse(
                        dataset.datasetCode(),
                        dataset.displayName(),
                        dataset.sensitivity(),
                        dataset.permissionCode(),
                        dataset.fields(),
                        dataset.description(),
                        dataset.sensitive(),
                        canRequest(identity, dataset)))
                .toList();
    }

    @Transactional
    public ExportRequestResponse createRequest(CreateExportRequest request, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_EXECUTE,
                "data export requires export:execute");
        if (!Boolean.TRUE.equals(request.acknowledged())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "acknowledged must be true; the exporter has to confirm the request explicitly");
        }
        Dataset dataset = catalog.require(request.datasetCode());
        if (dataset.permissionCode() != null && !dataset.permissionCode().isBlank()) {
            accessControlService.requirePermission(
                    identity, dataset.permissionCode(),
                    "exporting " + dataset.displayName() + " requires " + dataset.permissionCode());
        }
        if (dataset.sensitive()) {
            requireFullDataScope(identity, dataset);
        }
        // 筛选条件先解析一遍：不合法的键在建申请时就拒掉，别等审批完了才发现导不出来。
        ExportDataProvider.Filters.parse(request.filters());

        String requestNo = nextRequestNo();
        String approvalStatus = dataset.sensitive() ? APPROVAL_PENDING : APPROVAL_NOT_REQUIRED;
        jdbcClient.sql("""
                        INSERT INTO export_request
                            (request_no, dataset_code, sensitivity, filter_json, reason,
                             requested_by_user_id, approval_status)
                        VALUES
                            (:requestNo, :datasetCode, :sensitivity, CAST(:filterJson AS JSON), :reason,
                             :requestedByUserId, :approvalStatus)
                        """)
                .param("requestNo", requestNo)
                .param("datasetCode", dataset.datasetCode())
                .param("sensitivity", dataset.sensitivity())
                .param("filterJson", json(request.filters()))
                .param("reason", blankToNull(request.reason()))
                .param("requestedByUserId", identity.userId())
                .param("approvalStatus", approvalStatus)
                .update();
        return loadByRequestNo(requestNo, identity);
    }

    @Transactional
    public ExportRequestResponse approve(
            long exportRequestId, ApproveExportRequest request, boolean approved, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_APPROVE,
                "approving a sensitive export requires export:approve");
        RequestRow row = lock(exportRequestId);
        if (!APPROVAL_PENDING.equals(row.approvalStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "export request is not pending approval");
        }
        // 申请人不能批自己的申请。客户要的是「需要批准」，自批等于没批。
        if (Objects.equals(row.requestedByUserId(), identity.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "an export request cannot be approved by its own requester");
        }
        jdbcClient.sql("""
                        UPDATE export_request
                        SET approval_status = :approvalStatus,
                            approved_by_user_id = :approvedByUserId,
                            approved_at = CURRENT_TIMESTAMP(3),
                            approval_comment = :comment
                        WHERE export_request_id = :exportRequestId
                        """)
                .param("approvalStatus", approved ? APPROVAL_APPROVED : APPROVAL_REJECTED)
                .param("approvedByUserId", identity.userId())
                .param("comment", request == null ? null : blankToNull(request.comment()))
                .param("exportRequestId", exportRequestId)
                .update();
        return load(exportRequestId, identity);
    }

    /**
     * 实际取数并留痕。返回 CSV 内容；调用方负责包成下载响应。
     */
    @Transactional
    public DownloadResult download(long exportRequestId, BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_EXECUTE,
                "data export requires export:execute");
        RequestRow row = lock(exportRequestId);
        if (!Objects.equals(row.requestedByUserId(), identity.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "only the requester can download this export");
        }
        if (APPROVAL_PENDING.equals(row.approvalStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "sensitive export is still waiting for approval");
        }
        if (APPROVAL_REJECTED.equals(row.approvalStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "export request was rejected");
        }
        Dataset dataset = catalog.require(row.datasetCode());
        if (dataset.permissionCode() != null && !dataset.permissionCode().isBlank()) {
            accessControlService.requirePermission(
                    identity, dataset.permissionCode(),
                    "exporting " + dataset.displayName() + " requires " + dataset.permissionCode());
        }
        if (dataset.sensitive()) {
            requireFullDataScope(identity, dataset);
        }

        JsonNode filters = readJson(row.filterJson());
        String dataScope = accessControlService.effectiveDataScope(identity);
        accessControlService.requireScopedIdentity(identity, dataScope);
        ExportDataProvider.Rows rows = dataProvider.fetch(dataset, filters, identity, dataScope);
        List<String> fields = dataset.fields();
        String csv = toCsv(fields, rows.rows());

        jdbcClient.sql("""
                        INSERT INTO export_audit
                            (export_request_id, dataset_code, sensitivity, operator_user_id,
                             filter_json, row_count, field_list, approved_by_user_id)
                        VALUES
                            (:exportRequestId, :datasetCode, :sensitivity, :operatorUserId,
                             CAST(:filterJson AS JSON), :rowCount, :fieldList, :approvedByUserId)
                        """)
                .param("exportRequestId", exportRequestId)
                .param("datasetCode", dataset.datasetCode())
                .param("sensitivity", row.sensitivity())
                .param("operatorUserId", identity.userId())
                .param("filterJson", json(objectMapper.valueToTree(
                        ExportDataProvider.Filters.parse(filters).asAuditRange())))
                .param("rowCount", rows.rowCount())
                .param("fieldList", dataset.fieldList())
                .param("approvedByUserId", row.approvedByUserId())
                .update();
        jdbcClient.sql("""
                        UPDATE export_request
                        SET download_count = download_count + 1,
                            last_downloaded_at = CURRENT_TIMESTAMP(3)
                        WHERE export_request_id = :exportRequestId
                        """)
                .param("exportRequestId", exportRequestId)
                .update();

        String filename = dataset.datasetCode().toLowerCase(Locale.ROOT) + "-" + row.requestNo() + ".csv";
        return new DownloadResult(filename, csv, rows.rowCount());
    }

    public List<ExportRequestResponse> listRequests(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_EXECUTE,
                "data export requires export:execute");
        // 有审批权的看得到全部申请（否则没法审）；其余人只看自己的。
        boolean approver = identity.hasPermission(ExportDatasetCatalog.PERMISSION_APPROVE);
        return jdbcClient.sql(baseRequestSelect() + """
                        WHERE (:approver = TRUE OR request.requested_by_user_id = :userId)
                        ORDER BY request.export_request_id DESC
                        LIMIT 200
                        """)
                .param("approver", approver)
                .param("userId", identity.userId())
                .query((rs, rowNum) -> mapRequest(rs, identity))
                .list();
    }

    public List<ExportAuditResponse> listAudits(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, ExportDatasetCatalog.PERMISSION_AUDIT_READ,
                "export audit trail requires export:audit:read");
        return jdbcClient.sql("""
                        SELECT audit.export_audit_id, audit.export_request_id, request.request_no,
                               audit.dataset_code, dataset.display_name AS dataset_name,
                               audit.sensitivity, audit.operator_user_id,
                               operator.display_name AS operator_name, audit.exported_at,
                               audit.filter_json, audit.row_count, audit.field_list,
                               audit.approved_by_user_id, approver.display_name AS approver_name
                        FROM export_audit audit
                        JOIN export_request request
                          ON request.export_request_id = audit.export_request_id
                        LEFT JOIN export_dataset dataset ON dataset.dataset_code = audit.dataset_code
                        LEFT JOIN system_user operator ON operator.user_id = audit.operator_user_id
                        LEFT JOIN system_user approver ON approver.user_id = audit.approved_by_user_id
                        ORDER BY audit.export_audit_id DESC
                        LIMIT 200
                        """)
                .query((rs, rowNum) -> new ExportAuditResponse(
                        rs.getLong("export_audit_id"),
                        rs.getLong("export_request_id"),
                        rs.getString("request_no"),
                        rs.getString("dataset_code"),
                        rs.getString("dataset_name"),
                        rs.getString("sensitivity"),
                        rs.getLong("operator_user_id"),
                        rs.getString("operator_name"),
                        rs.getObject("exported_at", LocalDateTime.class),
                        readJson(rs.getString("filter_json")),
                        rs.getInt("row_count"),
                        List.of(rs.getString("field_list").split(",")),
                        rs.getObject("approved_by_user_id", Long.class),
                        rs.getString("approver_name")))
                .list();
    }

    // ------------------------------------------------------------------

    /**
     * 敏感导出要求数据范围为 ALL。否则一个只能看自己那部分数据的账号，
     * 一旦拿到敏感权限码就会导出全量——权限码与数据范围是两回事，这里必须同时满足。
     */
    private void requireFullDataScope(BootstrapIdentity identity, Dataset dataset) {
        if (!"ALL".equals(accessControlService.effectiveDataScope(identity))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "exporting " + dataset.displayName() + " requires an ALL data scope");
        }
    }

    private boolean canRequest(BootstrapIdentity identity, Dataset dataset) {
        if (dataset.permissionCode() != null && !dataset.permissionCode().isBlank()
                && !identity.hasPermission(dataset.permissionCode())) {
            return false;
        }
        return !dataset.sensitive() || "ALL".equals(accessControlService.effectiveDataScope(identity));
    }

    private String toCsv(List<String> header, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder();
        // BOM：Excel 打开 UTF-8 CSV 不带 BOM 会把中文显示成乱码。
        builder.append('﻿');
        appendCsvRow(builder, header);
        for (List<String> row : rows) {
            appendCsvRow(builder, row);
        }
        return builder.toString();
    }

    private void appendCsvRow(StringBuilder builder, List<String> cells) {
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            String cell = cells.get(index) == null ? "" : cells.get(index);
            builder.append('"').append(cell.replace("\"", "\"\"")).append('"');
        }
        builder.append('\n');
    }

    private RequestRow lock(long exportRequestId) {
        return jdbcClient.sql("""
                        SELECT export_request_id, request_no, dataset_code, sensitivity,
                               filter_json, requested_by_user_id, approval_status, approved_by_user_id
                        FROM export_request
                        WHERE export_request_id = :exportRequestId
                        FOR UPDATE
                        """)
                .param("exportRequestId", exportRequestId)
                .query((rs, rowNum) -> new RequestRow(
                        rs.getLong("export_request_id"),
                        rs.getString("request_no"),
                        rs.getString("dataset_code"),
                        rs.getString("sensitivity"),
                        rs.getString("filter_json"),
                        rs.getObject("requested_by_user_id", Long.class),
                        rs.getString("approval_status"),
                        rs.getObject("approved_by_user_id", Long.class)))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "export request not found"));
    }

    private ExportRequestResponse load(long exportRequestId, BootstrapIdentity identity) {
        return jdbcClient.sql(baseRequestSelect() + " WHERE request.export_request_id = :exportRequestId")
                .param("exportRequestId", exportRequestId)
                .query((rs, rowNum) -> mapRequest(rs, identity))
                .single();
    }

    private ExportRequestResponse loadByRequestNo(String requestNo, BootstrapIdentity identity) {
        return jdbcClient.sql(baseRequestSelect() + " WHERE request.request_no = :requestNo")
                .param("requestNo", requestNo)
                .query((rs, rowNum) -> mapRequest(rs, identity))
                .single();
    }

    private String baseRequestSelect() {
        return """
                SELECT request.export_request_id, request.request_no, request.dataset_code,
                       dataset.display_name AS dataset_name, request.sensitivity,
                       request.filter_json, request.reason, request.requested_by_user_id,
                       requester.display_name AS requester_name, request.requested_at,
                       request.approval_status, request.approved_by_user_id,
                       approver.display_name AS approver_name, request.approved_at,
                       request.approval_comment, request.download_count, request.last_downloaded_at
                FROM export_request request
                LEFT JOIN export_dataset dataset ON dataset.dataset_code = request.dataset_code
                LEFT JOIN system_user requester ON requester.user_id = request.requested_by_user_id
                LEFT JOIN system_user approver ON approver.user_id = request.approved_by_user_id
                """;
    }

    private ExportRequestResponse mapRequest(java.sql.ResultSet rs, BootstrapIdentity identity)
            throws java.sql.SQLException {
        String approvalStatus = rs.getString("approval_status");
        Long requestedBy = rs.getObject("requested_by_user_id", Long.class);
        boolean downloadable = (APPROVAL_NOT_REQUIRED.equals(approvalStatus)
                || APPROVAL_APPROVED.equals(approvalStatus))
                && Objects.equals(requestedBy, identity.userId());
        return new ExportRequestResponse(
                rs.getLong("export_request_id"),
                rs.getString("request_no"),
                rs.getString("dataset_code"),
                rs.getString("dataset_name"),
                rs.getString("sensitivity"),
                readJson(rs.getString("filter_json")),
                rs.getString("reason"),
                requestedBy,
                rs.getString("requester_name"),
                rs.getObject("requested_at", LocalDateTime.class),
                approvalStatus,
                rs.getObject("approved_by_user_id", Long.class),
                rs.getString("approver_name"),
                rs.getObject("approved_at", LocalDateTime.class),
                rs.getString("approval_comment"),
                rs.getInt("download_count"),
                rs.getObject("last_downloaded_at", LocalDateTime.class),
                downloadable);
    }

    private String nextRequestNo() {
        return "EXP" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize export filters", ex);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "stored export filters are invalid", ex);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RequestRow(
            long exportRequestId,
            String requestNo,
            String datasetCode,
            String sensitivity,
            String filterJson,
            Long requestedByUserId,
            String approvalStatus,
            Long approvedByUserId) {
    }

    public record DownloadResult(String filename, String csv, int rowCount) {
    }
}
