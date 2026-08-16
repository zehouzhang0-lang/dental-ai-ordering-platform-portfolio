package com.yuri.aiorder.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
class OrderCaseGroupTests {

    private static final long DOCTOR_USER_ID = 9731L;
    private static final long OTHER_DOCTOR_USER_ID = 9732L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;
    private long patientId;
    private long otherPatientId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:name)")
                .param("name", "病例组测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("""
                        SELECT clinic_id
                        FROM clinic
                        WHERE clinic_name = :name
                        """)
                .param("name", "病例组测试诊所-" + suffix)
                .query(Long.class)
                .single();
        upsertDoctor(DOCTOR_USER_ID, "case-group-doctor-" + suffix);
        upsertDoctor(OTHER_DOCTOR_USER_ID, "other-case-group-doctor-" + suffix);
        patientId = createPatient("病例组患者", DOCTOR_USER_ID);
        otherPatientId = createPatient("另一个患者", DOCTOR_USER_ID);
    }

    @Test
    void createsDraftIdempotentlyAndRejectsMismatchedReuse() throws Exception {
        String key = "case-group-" + UUID.randomUUID();
        String request = """
                {"patient_id": %d, "idempotency_key": "%s"}
                """.formatted(patientId, key);

        String first = mockMvc.perform(post("/order-case-groups")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycle_status").value("DRAFT"))
                .andExpect(jsonPath("$.data.external_status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long firstGroupId = readGroupId(first);
        mockMvc.perform(post("/order-case-groups")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_id").value(firstGroupId));

        mockMvc.perform(post("/order-case-groups")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patient_id": %d, "idempotency_key": "%s"}
                                """.formatted(otherPatientId, key)))
                .andExpect(status().isConflict());

        long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_case_group
                        WHERE doctor_user_id = :doctorUserId
                          AND idempotency_key = :key
                        """)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("key", key)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1L);
    }

    @Test
    void derivesDoctorSafePartialCompletionAndEnforcesOwnership() throws Exception {
        long groupId = createGroup("partial-" + UUID.randomUUID());
        insertOrder(groupId, 1, "PRODUCING");
        insertOrder(groupId, 2, "COMPLETED");

        mockMvc.perform(get("/order-case-groups/{groupId}", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external_status").value("PARTIALLY_COMPLETED"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].line_no").value(1))
                .andExpect(jsonPath("$.data.items[0].internal_status").doesNotExist());

        mockMvc.perform(get("/order-case-groups/{groupId}", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void migrationLeavesNoOrphanedOrDuplicateLegacyGroups() {
        long orphanedLegacyGroupCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_case_group legacy_group
                        WHERE legacy_group.group_no LIKE 'CG-%'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM orders
                              WHERE orders.group_id = legacy_group.group_id
                          )
                        """)
                .query(Long.class)
                .single();
        long duplicateLegacyGroupCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM (
                            SELECT orders.group_id
                            FROM orders
                            JOIN order_case_group
                              ON order_case_group.group_id = orders.group_id
                            WHERE order_case_group.group_no LIKE 'CG-%'
                            GROUP BY orders.group_id
                            HAVING COUNT(*) > 1
                        ) duplicate_group
                        """)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(orphanedLegacyGroupCount).isZero();
        org.assertj.core.api.Assertions.assertThat(duplicateLegacyGroupCount).isZero();
    }

    @Test
    @Transactional
    void multiProductDraftSupportsIdempotentAddCopyDeleteTypedValidationAndAtomicSubmit()
            throws Exception {
        CatalogFixture catalog = createActiveCatalog();
        long groupId = createGroup("multi-" + UUID.randomUUID());

        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "item_client_key":"invalid-quantity-%s",
                                  "form_values":{"patient_note":"病例 A","quantity":5,"urgent":false,"tags":["fixed"],"metadata":{"source":"doctor"}},
                                  "material_selections":[{"item_id":%d,"quantity":1}],
                                  "accessory_selections":[],
                                  "file_ids":[],
                                  "expected_draft_version":1
                                }
                                """.formatted(catalog.firstProductId(), UUID.randomUUID(), catalog.materialId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "item_client_key":"invalid-option-%s",
                                  "form_values":{"patient_note":"病例 A","quantity":2,"urgent":false,"tags":["unsupported"],"metadata":{"source":"doctor"}},
                                  "material_selections":[{"item_id":%d,"quantity":1}],
                                  "accessory_selections":[],
                                  "file_ids":[],
                                  "expected_draft_version":1
                                }
                                """.formatted(catalog.firstProductId(), UUID.randomUUID(), catalog.materialId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "item_client_key":"invalid-object-%s",
                                  "form_values":{"patient_note":"病例 A","quantity":2,"urgent":false,"tags":["fixed"],"metadata":"not-an-object"},
                                  "material_selections":[{"item_id":%d,"quantity":1}],
                                  "accessory_selections":[],
                                  "file_ids":[],
                                  "expected_draft_version":1
                                }
                                """.formatted(catalog.firstProductId(), UUID.randomUUID(), catalog.materialId())))
                .andExpect(status().isBadRequest());

        String firstRequest = """
                {
                  "product_id":%d,
                  "item_client_key":"line-a-%s",
                  "form_values":{"patient_note":"病例 A","tooth_positions":"11","case_note":"制作说明 A","quantity":2,"urgent":false,"tags":["fixed"],"metadata":{"source":"doctor"}},
                  "material_selections":[{"item_id":%d,"quantity":1}],
                  "accessory_selections":[],
                  "file_ids":[],
                  "expected_draft_version":1
                }
                """.formatted(catalog.firstProductId(), UUID.randomUUID(), catalog.materialId());
        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_version").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].pricing_status").value("PRICED"))
                .andExpect(jsonPath("$.data.items[0].quoted_price_cents").value(1200))
                .andExpect(jsonPath("$.data.items[0].form_values.quantity").value(2));

        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_version").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        long firstOrderId = jdbcClient.sql("""
                        SELECT order_id FROM orders
                        WHERE group_id = :groupId AND line_no = 1
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
        mockMvc.perform(post("/order-case-groups/{groupId}/items/{orderId}/copy", groupId, firstOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "item_client_key":"copy-%s",
                                  "expected_draft_version":2
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_version").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        long copiedOrderId = jdbcClient.sql("""
                        SELECT order_id FROM orders
                        WHERE group_id = :groupId AND line_no = 2
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
        mockMvc.perform(delete("/order-case-groups/{groupId}/items/{orderId}", groupId, copiedOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expected_draft_version":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_version").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(post("/order-case-groups/{groupId}/items", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "item_client_key":"line-b-%s",
                                  "form_values":{"patient_note":"病例 B"},
                                  "material_selections":[],
                                  "accessory_selections":[],
                                  "file_ids":[],
                                  "expected_draft_version":4
                                }
                                """.formatted(catalog.secondProductId(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft_version").value(5))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].pricing_status").value("PENDING_QUOTE"))
                .andExpect(jsonPath("$.data.items[1].quoted_price_cents").doesNotExist());

        mockMvc.perform(put("/order-case-groups/{groupId}/items/{orderId}", groupId, firstOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id":%d,
                                  "form_values":{"patient_note":"并发旧版本"},
                                  "material_selections":[{"item_id":%d,"quantity":1}],
                                  "accessory_selections":[],
                                  "file_ids":[],
                                  "expected_draft_version":4
                                }
                                """.formatted(catalog.firstProductId(), catalog.materialId())))
                .andExpect(status().isConflict());

        String submitKey = "submit-" + UUID.randomUUID();
        String submitBody = """
                {"idempotency_key":"%s","expected_draft_version":5}
                """.formatted(submitKey);
        mockMvc.perform(post("/order-case-groups/{groupId}/submit", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lifecycle_status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.draft_version").value(6))
                .andExpect(jsonPath("$.data.items[0].configuration_status").value("FROZEN"))
                .andExpect(jsonPath("$.data.items[0].external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.items[1].external_status").value("PENDING_REVIEW"));

        mockMvc.perform(get("/orders/{orderId}", firstOrderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.form_schema_snapshot").isArray())
                .andExpect(jsonPath("$.data.form_data.patient_name").value("病例组患者"))
                .andExpect(jsonPath("$.data.form_data.tooth_position").value("11"))
                .andExpect(jsonPath("$.data.form_data.doctor_note").value("制作说明 A"))
                .andExpect(jsonPath("$.data.form_data.material").value("氧化锆"));

        mockMvc.perform(get("/orders/{orderId}", firstOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group_id").value(groupId))
                .andExpect(jsonPath("$.data.form_data.patient_name").value("病例组患者"))
                .andExpect(jsonPath("$.data.form_data.tooth_position").value("11"))
                .andExpect(jsonPath("$.data.form_data.doctor_note").value("制作说明 A"))
                .andExpect(jsonPath("$.data.form_data.material").value("氧化锆"));

        mockMvc.perform(post("/ai/check-missing")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"order_id":%d}
                                """.formatted(firstOrderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.is_complete").value(true))
                .andExpect(jsonPath("$.data.missing_items.length()").value(0));

        mockMvc.perform(post("/order-case-groups/{groupId}/submit", groupId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));

        long snapshotCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM order_catalog_snapshot snapshot
                        JOIN orders ON orders.order_id = snapshot.order_id
                        WHERE orders.group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
        long auditCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM order_case_group_audit
                        WHERE group_id = :groupId
                        """)
                .param("groupId", groupId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(snapshotCount).isEqualTo(2L);
        org.assertj.core.api.Assertions.assertThat(auditCount).isEqualTo(6L);
    }

    private void upsertDoctor(long userId, String username) {
        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name,
                             clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, 'test-only', :username,
                             :clinicId, 'DOCTOR', 'ACTIVE')
                        ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            clinic_id = VALUES(clinic_id),
                            user_type = 'DOCTOR',
                            status = 'ACTIVE'
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("clinicId", clinicId)
                .update();
    }

    private long createPatient(String name, long doctorUserId) {
        jdbcClient.sql("""
                        INSERT INTO patient_record
                            (clinic_id, doctor_user_id, patient_name)
                        VALUES
                            (:clinicId, :doctorUserId, :name)
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", doctorUserId)
                .param("name", name)
                .update();
        return jdbcClient.sql("""
                        SELECT patient_id
                        FROM patient_record
                        WHERE clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                          AND patient_name = :name
                        ORDER BY patient_id DESC
                        LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", doctorUserId)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long createGroup(String key) throws Exception {
        String response = mockMvc.perform(post("/order-case-groups")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patient_id": %d, "idempotency_key": "%s"}
                                """.formatted(patientId, key)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return readGroupId(response);
    }

    private long readGroupId(String response) {
        String marker = "\"group_id\":";
        int start = response.indexOf(marker) + marker.length();
        int end = response.indexOf(',', start);
        return Long.parseLong(response.substring(start, end));
    }

    private void insertOrder(long groupId, int lineNo, String externalStatus) {
        jdbcClient.sql("""
                        INSERT INTO orders
                            (group_id, line_no, relationship_type, order_no,
                             clinic_id, doctor_user_id, patient_id, product_type,
                             form_data, internal_status, external_status)
                        VALUES
                            (:groupId, :lineNo, 'PRIMARY', :orderNo,
                             :clinicId, :doctorUserId, :patientId, 'REGULAR_CROWN',
                             JSON_OBJECT(), 'IN_PRODUCTION', :externalStatus)
                        """)
                .param("groupId", groupId)
                .param("lineNo", lineNo)
                .param("orderNo", "CGT-" + UUID.randomUUID().toString().replace("-", ""))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("patientId", patientId)
                .param("externalStatus", externalStatus)
                .update();
    }

    private CatalogFixture createActiveCatalog() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        int versionNo = jdbcClient.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM catalog_config_version")
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        UPDATE catalog_config_version
                        SET publication_status = 'INACTIVE'
                        WHERE publication_status = 'ACTIVE'
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO catalog_config_version
                            (version_no, version_name, publication_status, effective_at, published_at)
                        VALUES
                            (:versionNo, :name, 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)
                .param("versionNo", versionNo)
                .param("name", "病例组目录-" + suffix)
                .update();
        long catalogVersionId = jdbcClient.sql("""
                        SELECT config_version_id FROM catalog_config_version
                        WHERE version_no = :versionNo
                        """)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_category_v2
                            (config_version_id, category_code, display_name)
                        VALUES (:versionId, :code, '固定类')
                        """)
                .param("versionId", catalogVersionId)
                .param("code", "FIXED_" + suffix)
                .update();
        long categoryId = jdbcClient.sql("""
                        SELECT category_id FROM catalog_category_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", catalogVersionId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_product_v2
                            (config_version_id, category_id, product_code, display_name,
                             workflow_product_type, pricing_status, base_price_cents, currency)
                        VALUES
                            (:versionId, :categoryId, :firstCode, '固定修复产品',
                             'REGULAR_CROWN', 'PRICED', 1000, 'CNY'),
                            (:versionId, :categoryId, :secondCode, '待报价产品',
                             'REGULAR_CROWN', 'PENDING_QUOTE', NULL, 'CNY')
                        """)
                .param("versionId", catalogVersionId)
                .param("categoryId", categoryId)
                .param("firstCode", "FIRST_" + suffix)
                .param("secondCode", "SECOND_" + suffix)
                .update();
        long firstProductId = jdbcClient.sql("""
                        SELECT product_id FROM catalog_product_v2
                        WHERE config_version_id = :versionId AND product_code = :code
                        """)
                .param("versionId", catalogVersionId)
                .param("code", "FIRST_" + suffix)
                .query(Long.class)
                .single();
        long secondProductId = jdbcClient.sql("""
                        SELECT product_id FROM catalog_product_v2
                        WHERE config_version_id = :versionId AND product_code = :code
                        """)
                .param("versionId", catalogVersionId)
                .param("code", "SECOND_" + suffix)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_material_v2
                            (config_version_id, material_code, display_name)
                        VALUES (:versionId, :code, '氧化锆')
                        """)
                .param("versionId", catalogVersionId)
                .param("code", "ZIRCONIA_" + suffix)
                .update();
        long materialId = jdbcClient.sql("""
                        SELECT material_id FROM catalog_material_v2
                        WHERE config_version_id = :versionId
                        """)
                .param("versionId", catalogVersionId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_product_material_binding_v2
                            (config_version_id, product_id, material_id,
                             selection_group_code, required_flag, selection_mode,
                             min_quantity, max_quantity, price_increment_cents)
                        VALUES
                            (:versionId, :productId, :materialId,
                             'MAIN_MATERIAL', 1, 'SINGLE', 1, 1, 200)
                        """)
                .param("versionId", catalogVersionId)
                .param("productId", firstProductId)
                .param("materialId", materialId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO catalog_rule_v2
                            (config_version_id, product_id, rule_type, rule_code, rule_schema_json)
                        VALUES
                            (:versionId, :productId, 'FORM_SCHEMA', :ruleCode, CAST(:schema AS JSON))
                        """)
                .param("versionId", catalogVersionId)
                .param("productId", firstProductId)
                .param("ruleCode", "FORM_" + suffix)
                .param("schema", """
                        {
                          "fields": [
                            {"key":"patient_note","label":"病例说明","type":"string","required":true},
                            {"key":"quantity","label":"数量","type":"quantity","required":true,"minimum":1,"maximum":4},
                            {"key":"urgent","label":"加急","type":"boolean","required":true},
                            {"key":"tags","label":"标签","type":"multi_select","required":true,
                             "options":[{"value":"fixed","label":"固定"},{"value":"implant","label":"种植"}],
                             "min_items":1,"max_items":2},
                            {"key":"metadata","label":"结构化参数","type":"object","required":true},
                            {"key":"urgency_reason","label":"加急原因","type":"string","required":true,
                             "visible_when":{"field":"urgent","equals":true}}
                          ]
                        }
                        """)
                .update();
        return new CatalogFixture(firstProductId, secondProductId, materialId);
    }

    private record CatalogFixture(long firstProductId, long secondProductId, long materialId) {
    }
}
