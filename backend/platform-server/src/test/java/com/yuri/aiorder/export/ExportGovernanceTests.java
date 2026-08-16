package com.yuri.aiorder.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TASK-034 E 批次：导出管控与留痕。
 *
 * <p>执行前的现状：后端一个导出接口都没有，而医生端有两个纯前端拼 CSV 的按钮，
 * 点一下就把患者姓名与金额全带走——与客户「不允许医生直接导出」正好相反。
 * 因此这些测试断言的是「数据出口收到了后端、且每条客户要求都有强制点」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportGovernanceTests {

    private static final long ADMIN_USER_ID = 9801L;
    private static final long OTHER_ADMIN_USER_ID = 9802L;
    private static final long CS_USER_ID = 9803L;
    private static final long DOCTOR_USER_ID = 9804L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long clinicId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("""
                        INSERT INTO clinic (clinic_code, clinic_name, contact_name, contact_phone, status)
                        VALUES (:code, :name, '张三', '00000000001', 'ACTIVE')
                        """)
                .param("code", "EXP" + suffix.substring(0, 8).toUpperCase())
                .param("name", "导出测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :name")
                .param("name", "导出测试诊所-" + suffix)
                .query(Long.class)
                .single();
        upsertUser(ADMIN_USER_ID, "export-admin-" + suffix, "ADMIN", null);
        upsertUser(OTHER_ADMIN_USER_ID, "export-admin2-" + suffix, "ADMIN", null);
        upsertUser(CS_USER_ID, "export-cs-" + suffix, "CS", null);
        upsertUser(DOCTOR_USER_ID, "export-doctor-" + suffix, "DOCTOR", clinicId);
    }

    // ---------------------------------------------------------------------
    // 「不允许医生直接导出」
    // ---------------------------------------------------------------------

    @Test
    void doctorHasNoExportPermissionCodeAndEveryExportEndpointRejectsThem() throws Exception {
        // 医生端角色一个导出权限码都没有——这是第一层。
        long doctorExportCodes = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM system_role r
                        JOIN system_role_permission rp ON rp.role_id = r.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code IN ('DOCTOR', 'CLINIC_ADMIN', 'CLINIC_DOCTOR',
                                              'CLINIC_FRONTDESK', 'CLINIC_ASSISTANT')
                          AND p.permission_code LIKE 'export:%'
                        """)
                .query(Long.class)
                .single();
        assertThat(doctorExportCodes).isZero();

        // 接口层是第二层：即使有人给医生端补了权限码，注解上也没有 DOCTOR。
        mockMvc.perform(get("/exports/datasets").headers(doctor()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/exports").headers(doctor()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/exports").headers(doctor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"ORDER_LIST","acknowledged":true}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/exports/audits").headers(doctor()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // 「客户信息、地址、账单的导出是需要批准的」
    // ---------------------------------------------------------------------

    @Test
    void sensitiveExportCannotBeDownloadedBeforeApproval() throws Exception {
        long requestId = createRequest(admin(), "CUSTOMER_PROFILE");

        mockMvc.perform(get("/exports").headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.export_request_id==" + requestId + ")].approval_status")
                        .value("PENDING"))
                .andExpect(jsonPath("$.data[?(@.export_request_id==" + requestId + ")].downloadable")
                        .value(false));

        // 未批准就下载 → 409，而不是悄悄给数据。
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isConflict());
        assertThat(auditCount(requestId)).isZero();

        // 批准后才能下载。
        mockMvc.perform(post("/exports/{id}/approve", requestId).headers(otherAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"用于对账\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approval_status").value("APPROVED"));
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(".csv")));
        assertThat(auditCount(requestId)).isEqualTo(1L);
    }

    @Test
    void allFourCustomerNamedCategoriesAreClassifiedAsSensitive() throws Exception {
        // 客户点名的四类：客户信息、地址、账单、价格。
        for (String datasetCode : new String[] {
                "CUSTOMER_PROFILE", "CUSTOMER_SHIPPING_ADDRESS", "ORDER_BILL", "PRODUCT_PRICE"}) {
            long requestId = createRequest(admin(), datasetCode);
            mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                    .andExpect(status().isConflict());
        }
        // 其余数据集免审批，直接可下载。
        long normal = createRequest(admin(), "ORDER_LIST");
        mockMvc.perform(post("/exports/{id}/download", normal).headers(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void requesterCannotApproveTheirOwnSensitiveExport() throws Exception {
        long requestId = createRequest(admin(), "ORDER_BILL");
        // 自批等于没批。客户要的是「需要批准」。
        mockMvc.perform(post("/exports/{id}/approve", requestId).headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/exports/{id}/approve", requestId).headers(otherAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectedSensitiveExportStaysUndownloadable() throws Exception {
        long requestId = createRequest(admin(), "CUSTOMER_SHIPPING_ADDRESS");
        mockMvc.perform(post("/exports/{id}/reject", requestId).headers(otherAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"事由不充分\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approval_status").value("REJECTED"));
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isConflict());
        assertThat(auditCount(requestId)).isZero();
    }

    // ---------------------------------------------------------------------
    // 「别的数据需要导出留痕」
    // ---------------------------------------------------------------------

    @Test
    void everyDownloadRecordsOperatorTimeRangeRowCountAndFieldList() throws Exception {
        long requestId = createRequestWithFilters(admin(), "ORDER_LIST", """
                {"created_from":"2020-01-01","created_to":"2099-12-31","status":"COMPLETED"}
                """);
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isOk());

        var audit = jdbcClient.sql("""
                        SELECT operator_user_id, exported_at, filter_json, row_count, field_list
                        FROM export_audit
                        WHERE export_request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new Object[] {
                        rs.getLong("operator_user_id"),
                        rs.getObject("exported_at"),
                        rs.getString("filter_json"),
                        rs.getInt("row_count"),
                        rs.getString("field_list")})
                .single();

        // 客户点名的五项逐一断言，缺一条这条留痕就不算数。
        assertThat(audit[0]).isEqualTo(ADMIN_USER_ID);          // 操作人
        assertThat(audit[1]).isNotNull();                        // 时间
        assertThat(String.valueOf(audit[2]))                     // 导出范围
                .contains("created_from").contains("2020-01-01")
                .contains("created_to").contains("2099-12-31")
                .contains("COMPLETED");
        assertThat((Integer) audit[3]).isNotNegative();          // 行数
        assertThat(String.valueOf(audit[4])).contains("订单号").contains("客户名称"); // 字段清单

        // 同一份申请下载两次要留两条痕。
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isOk());
        assertThat(auditCount(requestId)).isEqualTo(2L);
    }

    @Test
    void auditFieldListMatchesTheCsvHeaderActuallyProduced() throws Exception {
        long requestId = createRequest(admin(), "CUSTOMER_PROFILE");
        mockMvc.perform(post("/exports/{id}/approve", requestId).headers(otherAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        String csv = mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String recordedFields = jdbcClient.sql("""
                        SELECT field_list FROM export_audit WHERE export_request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .single();
        // 留痕里的字段清单必须就是实际导出的表头，否则审计对不上真实文件。
        String header = csv.replace("﻿", "").lines().findFirst().orElse("");
        String headerFields = header.replace("\"", "");
        assertThat(headerFields).isEqualTo(recordedFields);
    }

    // ---------------------------------------------------------------------
    // 越权拒绝
    // ---------------------------------------------------------------------

    @Test
    void accountWithoutExportPermissionCodeIsDeniedEvenWhenThePortalRoleMatches() throws Exception {
        revokePermission("CS", "export:execute");
        try {
            mockMvc.perform(get("/exports/datasets").headers(cs()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/exports").headers(cs())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"dataset_code":"ORDER_LIST","acknowledged":true}
                                    """))
                    .andExpect(status().isForbidden());
        } finally {
            grantPermission("CS", "export:execute");
        }
    }

    @Test
    void csCanExportNormalDataButCannotRequestOrApproveSensitiveData() throws Exception {
        // 「各个管理端都需要数据导出」——非敏感的客服端可以导。
        long normal = createRequest(cs(), "ORDER_LIST");
        mockMvc.perform(post("/exports/{id}/download", normal).headers(cs()))
                .andExpect(status().isOk());

        // 但敏感类客服端拿不到 export:sensitive，申请阶段就被拒。
        mockMvc.perform(post("/exports").headers(cs())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"CUSTOMER_PROFILE","acknowledged":true}
                                """))
                .andExpect(status().isForbidden());

        // 审批权也没有。
        long sensitive = createRequest(admin(), "ORDER_BILL");
        mockMvc.perform(post("/exports/{id}/approve", sensitive).headers(cs())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyTheRequesterCanDownloadTheirOwnApprovedExport() throws Exception {
        long requestId = createRequest(admin(), "CUSTOMER_PROFILE");
        mockMvc.perform(post("/exports/{id}/approve", requestId).headers(otherAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // 批准人自己也不能替申请人下载——批准的是「谁导」，不是「谁都能导」。
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(otherAdmin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/exports/{id}/download", requestId).headers(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void auditTrailRequiresItsOwnPermissionCode() throws Exception {
        mockMvc.perform(get("/exports/audits").headers(cs()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/exports/audits").headers(admin()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------
    // 「导出需要反复确认」与入参守卫
    // ---------------------------------------------------------------------

    @Test
    void exportWithoutExplicitAcknowledgementIsRejected() throws Exception {
        // 界面上的确认框绕得过去，接口调用绕不过去。
        mockMvc.perform(post("/exports").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"ORDER_LIST"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/exports").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"ORDER_LIST","acknowledged":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownDatasetAndUnknownFilterKeysAreRejected() throws Exception {
        mockMvc.perform(post("/exports").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"EVERYTHING","acknowledged":true}
                                """))
                .andExpect(status().isBadRequest());
        // 筛选键只认白名单——导出入参来自界面，不能让它决定 SQL 结构。
        mockMvc.perform(post("/exports").headers(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"ORDER_LIST","acknowledged":true,
                                 "filters":{"1=1 OR":"x"}}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyConfiguredDatasetHasAnImplementationAndCanBeListed() throws Exception {
        // ExportDataProvider 启动时已校验目录与实现一一对应；这里再确认接口层能列全。
        mockMvc.perform(get("/exports/datasets").headers(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(7))
                .andExpect(jsonPath("$.data[?(@.dataset_code=='CUSTOMER_PROFILE')].requires_approval")
                        .value(true))
                .andExpect(jsonPath("$.data[?(@.dataset_code=='ORDER_LIST')].requires_approval")
                        .value(false));
    }

    // ---------------------------------------------------------------------
    // 脚手架
    // ---------------------------------------------------------------------

    private long createRequest(org.springframework.http.HttpHeaders headers, String datasetCode)
            throws Exception {
        return createRequestWithFilters(headers, datasetCode, "{}");
    }

    private long createRequestWithFilters(
            org.springframework.http.HttpHeaders headers, String datasetCode, String filters)
            throws Exception {
        String response = mockMvc.perform(post("/exports").headers(headers)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dataset_code":"%s","acknowledged":true,"reason":"测试","filters":%s}
                                """.formatted(datasetCode, filters)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("export_request_id").asLong();
    }

    private long auditCount(long requestId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM export_audit WHERE export_request_id = :requestId")
                .param("requestId", requestId)
                .query(Long.class)
                .single();
    }

    private org.springframework.http.HttpHeaders admin() {
        return headers("ADMIN", ADMIN_USER_ID, null);
    }

    private org.springframework.http.HttpHeaders otherAdmin() {
        return headers("ADMIN", OTHER_ADMIN_USER_ID, null);
    }

    private org.springframework.http.HttpHeaders cs() {
        return headers("CS", CS_USER_ID, null);
    }

    private org.springframework.http.HttpHeaders doctor() {
        return headers("DOCTOR", DOCTOR_USER_ID, clinicId);
    }

    private org.springframework.http.HttpHeaders headers(String role, long userId, Long clinic) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Bootstrap-Role", role);
        headers.add("X-Bootstrap-User-Id", String.valueOf(userId));
        if (clinic != null) {
            headers.add("X-Bootstrap-Clinic-Id", String.valueOf(clinic));
        }
        return headers;
    }

    private void revokePermission(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        DELETE rp FROM system_role_permission rp
                        JOIN system_role r ON r.role_id = rp.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode AND p.permission_code = :permissionCode
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update();
    }

    private void grantPermission(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        INSERT IGNORE INTO system_role_permission (role_id, permission_id)
                        SELECT r.role_id, p.permission_id
                        FROM system_role r
                        JOIN system_permission p ON p.permission_code = :permissionCode
                        WHERE r.role_code = :roleCode
                        """)
                .param("roleCode", roleCode)
                .param("permissionCode", permissionCode)
                .update();
    }

    private void upsertUser(long userId, String username, String userType, Long userClinicId) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, 'test-only', :username, :clinicId, :userType, 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            clinic_id = VALUES(clinic_id),
                            user_type = VALUES(user_type),
                            status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("clinicId", userClinicId)
                .param("userType", userType)
                .update();
    }
}
