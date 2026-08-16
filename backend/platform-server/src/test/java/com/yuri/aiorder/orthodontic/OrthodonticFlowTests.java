package com.yuri.aiorder.orthodontic;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrthodonticFlowTests {

    private static final long DOCTOR_ID = 88101L;
    private static final long WORKER_ID = 88102L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BearerTokenService tokenService;

    private long clinicId;
    private long orderId;
    private String alignerTypeCode;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String clinicName = "正畸测试诊所-" + suffix;
        jdbcClient.sql("INSERT INTO clinic(clinic_name, status) VALUES (:name, 'ACTIVE')")
                .param("name", clinicName)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :name")
                .param("name", clinicName)
                .query(Long.class)
                .single();

        int versionNo = jdbcClient.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM catalog_config_version")
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_config_version
                            (version_no, version_name, publication_status, effective_at)
                        VALUES (:versionNo, :name, 'ACTIVE', CURRENT_TIMESTAMP(3))
                        """)
                .param("versionNo", versionNo)
                .param("name", "正畸目录-" + suffix)
                .update();
        long versionId = jdbcClient.sql("""
                        SELECT config_version_id FROM catalog_config_version WHERE version_no = :versionNo
                        """)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_category_v2
                            (config_version_id, category_code, display_name, status)
                        VALUES (:versionId, :code, '隐形正畸', 'ACTIVE')
                        """)
                .param("versionId", versionId)
                .param("code", "ORTHO_" + suffix)
                .update();
        long categoryId = jdbcClient.sql("""
                        SELECT category_id FROM catalog_category_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_product_v2
                            (config_version_id, category_id, product_code, display_name,
                             workflow_product_type, pricing_status, currency, status)
                        VALUES
                            (:versionId, :categoryId, :code, '隐形矫治测试产品',
                             'ORTHODONTICS', 'PENDING_QUOTE', 'CNY', 'ACTIVE')
                        """)
                .param("versionId", versionId)
                .param("categoryId", categoryId)
                .param("code", "ALIGNER_" + suffix)
                .update();
        long productId = jdbcClient.sql("""
                        SELECT product_id FROM catalog_product_v2 WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        alignerTypeCode = "ALIGNER_A_" + suffix;
        jdbcClient.sql("""
                        INSERT INTO catalog_product_variant_v2
                            (config_version_id, product_id, variant_code, display_name,
                             attributes_json, status)
                        VALUES
                            (:versionId, :productId, :code, 'A 型',
                             JSON_OBJECT('enabled', true), 'ACTIVE')
                        """)
                .param("versionId", versionId)
                .param("productId", productId)
                .param("code", alignerTypeCode)
                .update();

        String orderNo = "ORTHO-" + suffix;
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, product_type, product_id,
                             form_data, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, :doctorId, 'ORTHODONTICS', :productId,
                             JSON_OBJECT(), 'DRAFT', 'DRAFT')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("doctorId", DOCTOR_ID)
                .param("productId", productId)
                .update();
        orderId = jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    @Test
    void sevenStepPrescriptionPlanGatesBatchesAndAdjustmentPreserveHistory() throws Exception {
        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(true, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("PRESCRIPTION_SUBMITTED"))
                .andExpect(jsonPath("$.data.prescription.basic_information.patient_concern").value("改善前牙排列"));

        mockMvc.perform(get("/orders/{orderId}/orthodontic-case", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID + 1)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());

        long firstPlan = createPlan("第一版");
        reviewInternal(firstPlan, "REJECT", "移动策略需调整");
        long secondPlan = createPlan("第二版");
        reviewInternal(secondPlan, "APPROVE", "内部审核通过");
        reviewDoctor(secondPlan, "REJECT", "患者希望减小前牙内收");
        long thirdPlan = createPlan("第三版");
        reviewInternal(thirdPlan, "APPROVE", "修改后通过");

        mockMvc.perform(post("/orders/{orderId}/orthodontic-production-batches", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"plan_version_id":%d,"step_from":1,"step_to":6}
                                """.formatted(thirdPlan)))
                .andExpect(status().isConflict());

        reviewDoctor(thirdPlan, "APPROVE", "医生确认方案");
        createBatch(thirdPlan, 1, 6).andExpect(status().isOk());
        createBatch(thirdPlan, 5, 8).andExpect(status().isConflict());
        createBatch(thirdPlan, 7, 12).andExpect(status().isOk());

        mockMvc.perform(post("/orders/{orderId}/orthodontic-change-requests", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source_plan_version_id":%d,
                                  "request_type":"STAGE_ADJUSTMENT",
                                  "reason":"第六步复诊后申请阶段调整"
                                }
                                """.formatted(thirdPlan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan_versions.length()").value(2))
                .andExpect(jsonPath("$.data.production_batches").doesNotExist())
                .andExpect(jsonPath("$.data.change_requests.length()").value(1))
                .andExpect(jsonPath("$.data.change_requests[0].requested_by_user_id").doesNotExist())
                .andExpect(jsonPath("$.data.change_requests[0].source_batch_id").doesNotExist())
                .andExpect(jsonPath("$.data.reviews[0].review_gate").value("DOCTOR"))
                .andExpect(jsonPath("$.data.reviews[0].reviewer_user_id").doesNotExist());

        long auditCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM orthodontic_audit
                        WHERE orthodontic_case_id = (
                            SELECT orthodontic_case_id FROM orthodontic_case WHERE order_id = :orderId
                        )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(auditCount).isGreaterThanOrEqualTo(10);
    }

    @Test
    void databaseDoctorBearerCanSubmitOwnOrthodonticPrescription() throws Exception {
        long databaseDoctorClinicId = jdbcClient.sql("""
                        SELECT clinic_id FROM system_user WHERE username = 'doctor'
                        """)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        UPDATE orders
                        SET doctor_user_id = 9701, clinic_id = :clinicId
                        WHERE order_id = :orderId
                        """)
                .param("clinicId", databaseDoctorClinicId)
                .param("orderId", orderId)
                .update();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"doctor","password":"change-me-doctor","portal":"DOCTOR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isArray())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken")
                .asText();

        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(true, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("PRESCRIPTION_SUBMITTED"));
    }

    @Test
    void assignedWorkerCanReadOrthodonticCaseThroughDesignTaskScope() throws Exception {
        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(true, 0)))
                .andExpect(status().isOk());

        jdbcClient.sql("""
                        INSERT INTO design_task
                            (order_id, task_status, assigned_user_id, claimed_at)
                        VALUES
                            (:orderId, 'CLAIMED', :workerId, CURRENT_TIMESTAMP(3))
                        """)
                .param("orderId", orderId)
                .param("workerId", WORKER_ID)
                .update();

        mockMvc.perform(get("/orders/{orderId}/orthodontic-case", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.case_status").value("PRESCRIPTION_SUBMITTED"));

        mockMvc.perform(get("/orders/{orderId}/orthodontic-case", orderId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", WORKER_ID + 1))
                .andExpect(status().isForbidden());
    }

    @Test
    void alignerTypeMustComeFromPublishedConfigurationAndPrescriptionUsesOptimisticLock() throws Exception {
        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(false, 0).replace(alignerTypeCode, "HARDCODED_A")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lock_version").value(0));

        mockMvc.perform(put("/orders/{orderId}/orthodontic-prescription", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(prescriptionJson(true, 9)))
                .andExpect(status().isConflict());
    }

    private String prescriptionJson(boolean submit, int lockVersion) {
        return """
                {
                  "aligner_type_code":"%s",
                  "total_steps":12,
                  "basic_information":{"patient_concern":"改善前牙排列"},
                  "records_and_models":{"facial_photos":[101],"intraoral_photos":[102],"panoramic":[103],"models":{"upper":104,"lower":105,"bite":106}},
                  "clinical_diagnosis":{"crowding":"MILD","diagnostic_teeth":["11","12","21","22"]},
                  "appliance_and_combination":{"appliance_type":"TYPE_A","combined_order_id":null},
                  "tooth_targets":{"target_teeth":["11","12","21","22"],"movement_strategy":{"11":"ALIGN"}},
                  "plan_parameters":{"ipr":false,"attachments":true,"staging":"STANDARD"},
                  "preview_and_submission":{"template_code":"ORTHO_DEFAULT","confirmed":true},
                  "submit":%s,
                  "expected_lock_version":%d
                }
                """.formatted(alignerTypeCode, submit, lockVersion);
    }

    private long createPlan(String note) throws Exception {
        mockMvc.perform(post("/orders/{orderId}/orthodontic-plan-versions", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "plan_snapshot":{"total_steps":12,"movement":"版本化方案"},
                                  "design_note":"%s"
                                }
                                """.formatted(note)))
                .andExpect(status().isOk());
        return jdbcClient.sql("""
                        SELECT MAX(plan_version_id)
                        FROM orthodontic_plan_version
                        WHERE orthodontic_case_id = (
                            SELECT orthodontic_case_id FROM orthodontic_case WHERE order_id = :orderId
                        )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private void reviewInternal(long planId, String decision, String reason) throws Exception {
        mockMvc.perform(post("/orthodontic-plan-versions/{planId}/internal-review", planId)
                        .header("Authorization", "Bearer " + internalReviewerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"%s","reason":"%s"}
                                """.formatted(decision, reason)))
                .andExpect(status().isOk());
    }

    private String internalReviewerToken() {
        jdbcClient.sql("""
                        INSERT INTO design_task
                            (order_id, task_status, assigned_user_id, claimed_at)
                        VALUES
                            (:orderId, 'CLAIMED', :workerId, CURRENT_TIMESTAMP(3))
                        ON DUPLICATE KEY UPDATE
                            task_status = 'CLAIMED',
                            assigned_user_id = :workerId,
                            claimed_at = CURRENT_TIMESTAMP(3)
                        """)
                .param("orderId", orderId)
                .param("workerId", WORKER_ID)
                .update();
        return tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                WORKER_ID,
                null,
                null,
                Set.of("design-draft:internal-review", "workflow:read-internal"),
                "SELF"));
    }

    private void reviewDoctor(long planId, String decision, String reason) throws Exception {
        mockMvc.perform(post("/orthodontic-plan-versions/{planId}/doctor-review", planId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"%s","reason":"%s"}
                                """.formatted(decision, reason)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions createBatch(
            long planId, int from, int to) throws Exception {
        return mockMvc.perform(post("/orders/{orderId}/orthodontic-production-batches", orderId)
                .header("X-Bootstrap-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"plan_version_id":%d,"step_from":%d,"step_to":%d}
                        """.formatted(planId, from, to)));
    }
}
