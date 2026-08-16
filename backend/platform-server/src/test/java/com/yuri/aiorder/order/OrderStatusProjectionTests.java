package com.yuri.aiorder.order;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yuri.aiorder.order.status.InternalOrderStatus;
import com.yuri.aiorder.order.status.OrderStatusService;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import java.time.LocalDate;
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

@SpringBootTest
@AutoConfigureMockMvc
class OrderStatusProjectionTests {

    private static final long DOCTOR_USER_ID = 9001L;
    private static final long OTHER_DOCTOR_USER_ID = 9002L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderStatusService statusService;

    @Autowired
    private BearerTokenService tokenService;

    private long clinicId;
    private String clinicName;
    private long patientId;
    private long orderId;
    private String orderNo;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        clinicName = "测试诊所-" + suffix;
        orderNo = "T" + suffix.substring(0, 12);

        jdbcClient.sql("""
                        DELETE sup
                        FROM system_user_permission sup
                        JOIN system_permission permission ON permission.permission_id = sup.permission_id
                        WHERE sup.user_id = 9601
                          AND permission.permission_code = 'workflow:review-production'
                        """)
                .update();
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO system_user
                            (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES
                            (:userId, 'order-projection-doctor', 'test-only', '订单测试医生',
                             :clinicId, 'DOCTOR', 'ACTIVE')
                        ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)
                        """)
                .param("userId", DOCTOR_USER_ID)
                .param("clinicId", clinicId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO patient_record
                            (clinic_id, doctor_user_id, patient_name)
                        VALUES
                            (:clinicId, :doctorUserId, '张三')
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .update();
        patientId = jdbcClient.sql("""
                        SELECT patient_id
                        FROM patient_record
                        WHERE clinic_id = :clinicId
                          AND doctor_user_id = :doctorUserId
                          AND patient_name = '张三'
                        ORDER BY patient_id DESC
                        LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, patient_id, cs_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, :patientId, 8001, 'REGULAR_CROWN',
                             JSON_OBJECT('patient_name', '张三', 'tooth_position', '11'),
                             'PENDING_CS_REVIEW', 'PENDING_REVIEW', '内部生产备注')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("patientId", patientId)
                .update();
        orderId = jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    @Test
    void statusServiceUpdatesExternalProjectionAndHistory() {
        statusService.updateOrderState(orderId, InternalOrderStatus.IN_PRODUCTION, "TEST_START_PRODUCTION", 8001L, null);

        String externalStatus = jdbcClient.sql("SELECT external_status FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
        String projectionStatus = jdbcClient.sql("""
                        SELECT external_status
                        FROM order_external_projection
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND to_internal_status = 'IN_PRODUCTION'
                          AND to_external_status = 'PRODUCING'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(externalStatus).isEqualTo("PRODUCING");
        org.assertj.core.api.Assertions.assertThat(projectionStatus).isEqualTo("PRODUCING");
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
    }

    @Test
    void doctorOrderDetailUsesDesensitizedProjection() throws Exception {
        statusService.updateOrderState(orderId, InternalOrderStatus.IN_PRODUCTION, "TEST_START_PRODUCTION", 8001L, null);

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.external_status").value("PRODUCING"))
                .andExpect(jsonPath("$.data.editable").value(false))
                .andExpect(jsonPath("$.data.public_progress", hasSize(8)))
                .andExpect(jsonPath("$.data.public_progress[0].key").value("submitted"))
                .andExpect(jsonPath("$.data.public_progress[0].status").value("DONE"))
                .andExpect(jsonPath("$.data.public_progress[2].label").value("方案设计"))
                .andExpect(jsonPath("$.data.public_progress[3].label").value("制作处理中"))
                .andExpect(jsonPath("$.data.public_progress[3].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.public_progress[3].occurred_at").exists())
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.production_note").doesNotExist())
                .andExpect(jsonPath("$.data.cs_user_id").doesNotExist())
                .andExpect(content().string(not(containsString("内部生产备注"))))
                .andExpect(content().string(not(containsString("to_internal_status"))))
                .andExpect(content().string(not(containsString("operator_user_id"))));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal_status").value("IN_PRODUCTION"))
                .andExpect(jsonPath("$.data.clinic_name").value(clinicName))
                .andExpect(jsonPath("$.data.patient_name").value("张三"))
                .andExpect(jsonPath("$.data.doctor_name").value("订单测试医生"))
                .andExpect(jsonPath("$.data.production_note").value("内部生产备注"))
                .andExpect(jsonPath("$.data.cs_user_id").value(8001));
    }

    @Test
    void receiptConfirmationRequiresShipmentAndFreezesDeliveredLogisticsFact() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/confirm-receipt", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isConflict());

        statusService.updateOrderState(orderId, InternalOrderStatus.SHIPPED, "TEST_ORDER_SHIPPED", 8001L, "SF-TEST");
        jdbcClient.sql("""
                        INSERT INTO order_logistics
                            (order_id, carrier_name, tracking_no, logistics_status, shipped_at)
                        VALUES
                            (:orderId, '顺丰速运', 'SF-TEST', 'SHIPPED', CURRENT_TIMESTAMP(3))
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(post("/orders/{orderId}/confirm-receipt", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/confirm-receipt", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.externalStatus").value("COMPLETED"));

        String logisticsStatus = jdbcClient.sql("""
                        SELECT logistics_status
                        FROM order_logistics
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        long deliveredCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_logistics
                        WHERE order_id = :orderId
                          AND delivered_at IS NOT NULL
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        long receiptHistoryCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND event_type = 'DOCTOR_CONFIRM_RECEIPT'
                          AND to_internal_status = 'COMPLETED'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(logisticsStatus).isEqualTo("DELIVERED");
        org.assertj.core.api.Assertions.assertThat(deliveredCount).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(receiptHistoryCount).isEqualTo(1L);

        mockMvc.perform(post("/orders/{orderId}/confirm-receipt", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorOrderListUsesDataScopeAndDesensitizedProjection() throws Exception {
        statusService.updateOrderState(orderId, InternalOrderStatus.IN_PRODUCTION, "TEST_START_PRODUCTION", 8001L, null);
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, 8001, 'REGULAR_CROWN',
                             JSON_OBJECT('patient_name', '李四', 'tooth_position', '21'),
                             'IN_PRODUCTION', 'PRODUCING', '其他医生内部备注')
                        """)
                .param("orderNo", "OTHER" + UUID.randomUUID().toString().replace("-", "").substring(0, 10))
                .param("clinicId", clinicId)
                .param("doctorUserId", OTHER_DOCTOR_USER_ID)
                .update();

        mockMvc.perform(get("/orders")
                        .param("keyword", orderNo)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data.items[0].external_status").value("PRODUCING"))
                .andExpect(jsonPath("$.data.items[0].editable").value(false))
                .andExpect(jsonPath("$.data.items[0].internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].production_note").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].cs_user_id").doesNotExist())
                .andExpect(content().string(not(containsString("内部生产备注"))))
                .andExpect(content().string(not(containsString("其他医生内部备注"))));
    }

    @Test
    void doctorCanReadDynamicFormAndCreateSubmittedOrderWithOwnCompletedFiles() throws Exception {
        long completedFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED");

        mockMvc.perform(get("/form-configs")
                        .param("product_type", "REGULAR_CROWN")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.data[0].field_key").value("patient_name"))
                .andExpect(jsonPath("$.data[0].is_required").value(true))
                .andExpect(jsonPath("$.data[1].field_key").value("tooth_position"));

        String request = """
                {
                  "product_type": "REGULAR_CROWN",
                  "form_data": {
                    "patient_name": "王五",
                    "tooth_position": "36"
                  },
                  "file_ids": [%d]
                }
                """.formatted(completedFileId);

        String response = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").isNumber())
                .andExpect(jsonPath("$.data.order_no").value(containsString("ORD")))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(content().string(not(containsString("PENDING_CS_REVIEW"))))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long createdOrderId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.order_id")).longValue();
        String internalStatus = jdbcClient.sql("SELECT internal_status FROM orders WHERE order_id = :orderId")
                .param("orderId", createdOrderId)
                .query(String.class)
                .single();
        Long fileOrderId = jdbcClient.sql("SELECT order_id FROM file_resource WHERE file_id = :fileId")
                .param("fileId", completedFileId)
                .query(Long.class)
                .single();
        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND to_internal_status = 'PENDING_CS_REVIEW'
                          AND to_external_status = 'PENDING_REVIEW'
                          AND event_type = 'DOCTOR_SUBMIT_ORDER'
                        """)
                .param("orderId", createdOrderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(internalStatus).isEqualTo("PENDING_CS_REVIEW");
        org.assertj.core.api.Assertions.assertThat(fileOrderId).isEqualTo(createdOrderId);
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
    }

    @Test
    void doctorCanCreateDraftWithoutRequiredFieldsAndItDoesNotEnterCsReviewQueue() throws Exception {
        String request = """
                {
                  "product_type": "REGULAR_CROWN",
                  "form_data": {
                    "patient_name": "草稿患者"
                  },
                  "is_draft": true
                }
                """;

        String response = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").isNumber())
                .andExpect(jsonPath("$.data.external_status").value("DRAFT"))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.form_data.patient_name").value("草稿患者"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long draftOrderId = ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.order_id")).longValue();
        String draftOrderNo = com.jayway.jsonpath.JsonPath.read(response, "$.data.order_no");
        String internalStatus = jdbcClient.sql("SELECT internal_status FROM orders WHERE order_id = :orderId")
                .param("orderId", draftOrderId)
                .query(String.class)
                .single();
        long submittedHistoryCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND event_type = 'DOCTOR_SUBMIT_ORDER'
                        """)
                .param("orderId", draftOrderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(internalStatus).isEqualTo("DRAFT");
        org.assertj.core.api.Assertions.assertThat(submittedHistoryCount).isZero();

        mockMvc.perform(get("/orders")
                        .param("internal_status", "PENDING_CS_REVIEW")
                        .param("keyword", draftOrderNo)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));

        mockMvc.perform(get("/orders/{orderId}", draftOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotSubmitOrderWithoutCompletedStl() throws Exception {
        long submittedOrderCountBefore = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(form_data, '$.patient_name')) = '无附件提交'
                        """)
                .query(Long.class)
                .single();

        mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {
                                    "patient_name": "无附件提交",
                                    "tooth_position": "36"
                                  },
                                  "file_ids": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getErrorMessage())
                        .contains("at least one completed STL file is required"));

        long submittedOrderCountAfter = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM orders
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(form_data, '$.patient_name')) = '无附件提交'
                        """)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(submittedOrderCountAfter).isEqualTo(submittedOrderCountBefore);

        long pdfFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED",
                "case.pdf");
        assertCreateOrderWithFileRejected(pdfFileId, is(400));
    }

    @Test
    void doctorCannotSubmitExistingDraftAfterRemovingAllStlFiles() throws Exception {
        String draftResponse = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {"patient_name": "空附件草稿"},
                                  "is_draft": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long draftOrderId = ((Number) com.jayway.jsonpath.JsonPath.read(
                draftResponse, "$.data.order_id")).longValue();

        mockMvc.perform(put("/orders/{orderId}", draftOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {
                                    "patient_name": "空附件草稿",
                                    "tooth_position": "36"
                                  },
                                  "file_ids": [],
                                  "submit": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                                result.getResponse().getErrorMessage())
                        .contains("at least one completed STL file is required"));

        String internalStatus = jdbcClient.sql("SELECT internal_status FROM orders WHERE order_id = :orderId")
                .param("orderId", draftOrderId)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(internalStatus).isEqualTo("DRAFT");
    }

    @Test
    void doctorRemovingUploadedDraftFileDeletesItFromLaterOrderStages() throws Exception {
        long completedFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED");
        String draftResponse = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {"patient_name": "附件删除草稿"},
                                  "file_ids": [%d],
                                  "is_draft": true
                                }
                                """.formatted(completedFileId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long draftOrderId = ((Number) com.jayway.jsonpath.JsonPath.read(
                draftResponse, "$.data.order_id")).longValue();

        mockMvc.perform(put("/orders/{orderId}", draftOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {"patient_name": "附件删除草稿"},
                                  "file_ids": [],
                                  "submit": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(draftOrderId));

        mockMvc.perform(get("/orders/{orderId}/files", draftOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        String fileStatus = jdbcClient.sql("SELECT status FROM file_resource WHERE file_id = :fileId")
                .param("fileId", completedFileId)
                .query(String.class)
                .single();
        long deleteAuditCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM file_access_audit
                        WHERE file_id = :fileId
                          AND order_id = :orderId
                          AND actor_user_id = :actorUserId
                          AND action = 'DELETE'
                          AND access_result = 'ALLOWED'
                        """)
                .param("fileId", completedFileId)
                .param("orderId", draftOrderId)
                .param("actorUserId", DOCTOR_USER_ID)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(fileStatus).isEqualTo("DELETED");
        org.assertj.core.api.Assertions.assertThat(deleteAuditCount).isEqualTo(1L);
    }

    @Test
    void doctorCanSubmitOwnDraftWithCompletedFiles() throws Exception {
        String draftResponse = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {
                                    "patient_name": "待提交草稿"
                                  },
                                  "is_draft": true
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long draftOrderId = ((Number) com.jayway.jsonpath.JsonPath.read(draftResponse, "$.data.order_id")).longValue();
        long completedFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED");

        mockMvc.perform(put("/orders/{orderId}", draftOrderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {
                                    "patient_name": "已提交草稿",
                                    "tooth_position": "16"
                                  },
                                  "file_ids": [%d],
                                  "submit": true
                                }
                                """.formatted(completedFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(draftOrderId))
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.form_data.patient_name").value("已提交草稿"));

        String internalStatus = jdbcClient.sql("SELECT internal_status FROM orders WHERE order_id = :orderId")
                .param("orderId", draftOrderId)
                .query(String.class)
                .single();
        Long fileOrderId = jdbcClient.sql("SELECT order_id FROM file_resource WHERE file_id = :fileId")
                .param("fileId", completedFileId)
                .query(Long.class)
                .single();
        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND from_internal_status = 'DRAFT'
                          AND to_internal_status = 'PENDING_CS_REVIEW'
                          AND event_type = 'DOCTOR_SUBMIT_ORDER'
                        """)
                .param("orderId", draftOrderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(internalStatus).isEqualTo("PENDING_CS_REVIEW");
        org.assertj.core.api.Assertions.assertThat(fileOrderId).isEqualTo(draftOrderId);
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
    }

    @Test
    void doctorCanSupplementRejectedOrderAndResubmitIt() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "REJECT",
                                  "reject_reason": "缺少咬合记录，请补充附件。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal_status").value("CS_REJECTED"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.editable").value(true))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist());
        long completedFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED");

        mockMvc.perform(put("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_type": "REGULAR_CROWN",
                                  "form_data": {
                                    "patient_name": "张三-已补资料",
                                    "tooth_position": "11"
                                  },
                                  "file_ids": [%d],
                                  "submit": true
                                }
                                """.formatted(completedFileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(content().string(not(containsString("CS_REJECTED"))));

        String internalStatus = jdbcClient.sql("SELECT internal_status FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
        String patientName = jdbcClient.sql("""
                        SELECT JSON_UNQUOTE(JSON_EXTRACT(form_data, '$.patient_name'))
                        FROM orders
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND from_internal_status = 'CS_REJECTED'
                          AND to_internal_status = 'PENDING_CS_REVIEW'
                          AND event_type = 'DOCTOR_RESUBMIT_ORDER'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(internalStatus).isEqualTo("PENDING_CS_REVIEW");
        org.assertj.core.api.Assertions.assertThat(patientName).isEqualTo("张三-已补资料");
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
    }

    @Test
    void doctorCannotBindOtherUnfinishedOrInternalFilesWhenCreatingOrder() throws Exception {
        long otherDoctorFileId = insertFileResource(
                null,
                OTHER_DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "COMPLETED");
        long pendingFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "DOCTOR",
                "PENDING");
        long internalFileId = insertFileResource(
                null,
                DOCTOR_USER_ID,
                "ORDER_ATTACHMENT",
                "INTERNAL",
                "COMPLETED");

        assertCreateOrderWithFileRejected(otherDoctorFileId, is(403));
        assertCreateOrderWithFileRejected(pendingFileId, is(409));
        assertCreateOrderWithFileRejected(internalFileId, is(403));
    }

    @Test
    void csCanApprovePendingDoctorOrderIntoProductionReviewWithoutStartingProduction() throws Exception {
        String confirmedProductionNote = "客服确认：" + "已核对订单资料与生产要求。".repeat(80);

        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "APPROVE",
                                  "production_note": "%s"
                                }
                                """.formatted(confirmedProductionNote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").value("PENDING_PRODUCTION_REVIEW"))
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.production_note").value(confirmedProductionNote))
                .andExpect(jsonPath("$.data.reject_reason").value(nullValue()));

        long processInstanceCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_process_instance
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND from_internal_status = 'PENDING_CS_REVIEW'
                          AND to_internal_status = 'PENDING_PRODUCTION_REVIEW'
                          AND event_type = 'CS_APPROVE_ORDER'
                          AND operator_user_id = 8002
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        String historyReason = jdbcClient.sql("""
                        SELECT reason
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND event_type = 'CS_APPROVE_ORDER'
                        ORDER BY history_id DESC
                        LIMIT 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        long doctorNotificationCount = notificationCount(orderId, "ORDER_APPROVED", "DOCTOR");

        org.assertj.core.api.Assertions.assertThat(processInstanceCount).isZero();
        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(historyReason).isEqualTo("客服初审通过，进入生产审核。");
        org.assertj.core.api.Assertions.assertThat(doctorNotificationCount).isEqualTo(1L);
    }

    @Test
    void authorizedProductionReviewerCanReadAndReviewPendingQueue() throws Exception {
        statusService.updateOrderState(
                orderId,
                InternalOrderStatus.PENDING_PRODUCTION_REVIEW,
                "TEST_PENDING_PRODUCTION_REVIEW",
                8002L,
                "ready for production review");
        jdbcClient.sql("""
                        INSERT INTO system_user_permission (user_id, permission_id)
                        SELECT 9601, permission_id
                        FROM system_permission
                        WHERE permission_code = 'workflow:review-production'
                        """)
                .update();
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                9601L,
                null,
                "production-worker",
                Set.of("order:read-internal", "workflow:read-internal", "workflow:review-production"),
                "SELF"));

        mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("internal_status", "PENDING_PRODUCTION_REVIEW")
                        .param("keyword", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].order_id").value(orderId));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId));

        mockMvc.perform(get("/production/kanban")
                .header("Authorization", "Bearer " + token)
                .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible_order_ids", not(hasItem((int) orderId))));

        mockMvc.perform(get("/production/kanban")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible_order_ids", not(hasItem((int) orderId))));

        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"reject_reason\":\"test production review permission\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.internal_status").value("PRODUCTION_REJECTED"));
    }

    @Test
    void ordinaryWorkerCannotReadOrReviewPendingProductionQueue() throws Exception {
        statusService.updateOrderState(
                orderId,
                InternalOrderStatus.PENDING_PRODUCTION_REVIEW,
                "TEST_PENDING_PRODUCTION_REVIEW",
                8002L,
                "ready for production review");
        String token = tokenService.issue(new BootstrapIdentity(
                UserRole.WORKER,
                9601L,
                null,
                "ordinary-production-worker",
                Set.of("order:read-internal", "workflow:read-internal"),
                "SELF"));

        mockMvc.perform(get("/orders")
                        .header("Authorization", "Bearer " + token)
                        .param("internal_status", "PENDING_PRODUCTION_REVIEW")
                        .param("keyword", orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REJECT\",\"reject_reason\":\"must remain forbidden\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csOrderListCanFilterPendingCsReviewByInternalStatus() throws Exception {
        String listPrefix = "CSLIST" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbcClient.sql("""
                        UPDATE orders
                        SET order_no = :orderNo
                        WHERE order_id = :orderId
                        """)
                .param("orderNo", listPrefix + "-PENDING")
                .param("orderId", orderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, 8002, 'REGULAR_CROWN',
                             JSON_OBJECT('patient_name', '已初审', 'tooth_position', '12'),
                             'PENDING_PRODUCTION_REVIEW', 'PENDING_REVIEW')
                        """)
                .param("orderNo", listPrefix + "-REVIEWED")
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .update();

        mockMvc.perform(get("/orders")
                        .param("internal_status", "PENDING_CS_REVIEW")
                        .param("keyword", listPrefix)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data.items[0].internal_status").value("PENDING_CS_REVIEW"));
    }

    @Test
    void internalOrderListCanSearchHumanFriendlyOrderIdentityFields() throws Exception {
        String customerCaseNo = "CASE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbcClient.sql("""
                        UPDATE orders
                        SET form_data = JSON_SET(
                            form_data,
                            '$.customer_case_no', :customerCaseNo,
                            '$.material', '氧化锆搜索样本',
                            '$.shade', 'A3.5搜索样本',
                            '$.tooth_position', '36-37搜索样本')
                        WHERE order_id = :orderId
                        """)
                .param("customerCaseNo", customerCaseNo)
                .param("orderId", orderId)
                .update();

        for (String keyword : new String[]{customerCaseNo, "氧化锆搜索样本", "A3.5搜索样本", "36-37搜索样本"}) {
            mockMvc.perform(get("/orders")
                            .param("keyword", keyword)
                            .header("X-Bootstrap-Role", "CS")
                            .header("X-Bootstrap-User-Id", 8002L))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items", hasSize(1)))
                    .andExpect(jsonPath("$.data.items[0].order_id").value(orderId));
        }
    }

    @Test
    void csCanRejectPendingDoctorOrderAndDoctorStillSeesOnlyExternalProjection() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "REJECT",
                                  "reject_reason": "缺少咬合记录，请补充附件。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").value("CS_REJECTED"))
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.reject_reason").value("缺少咬合记录，请补充附件。"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.reject_reason").doesNotExist())
                .andExpect(content().string(not(containsString("CS_REJECTED"))));

        long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM order_status_history
                        WHERE order_id = :orderId
                          AND to_internal_status = 'CS_REJECTED'
                          AND event_type = 'CS_REJECT_ORDER'
                          AND reason = '缺少咬合记录，请补充附件。'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        long doctorNotificationCount = notificationCount(orderId, "ORDER_REJECTED", "DOCTOR");

        org.assertj.core.api.Assertions.assertThat(historyCount).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(doctorNotificationCount).isEqualTo(1L);
    }

    @Test
    void orderReviewRejectsWrongRoleInvalidActionAndWrongCurrentStatus() throws Exception {
        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"APPROVE"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"HOLD"}
                                """))
                .andExpect(status().isBadRequest());

        statusService.updateOrderState(
                orderId,
                InternalOrderStatus.PENDING_PRODUCTION_REVIEW,
                "TEST_ALREADY_REVIEWED",
                8002L,
                null);
        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"APPROVE"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorCannotAccessOtherDoctorOrInternalProcessApi() throws Exception {
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", 7777L))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/orders/{orderId}/process-instance", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void aiOrderQueryUsesDoctorSafeReadModel() throws Exception {
        statusService.updateOrderState(orderId, InternalOrderStatus.IN_QC, "TEST_QC", 8001L, null);

        mockMvc.perform(post("/ai/order-query")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order_id\":" + orderId + ",\"question\":\"我的订单谁在做？有没有返工？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value(containsString("QC")))
                .andExpect(content().string(not(containsString("internal_status"))))
                .andExpect(content().string(not(containsString("内部生产备注"))))
                .andExpect(content().string(not(containsString("assigned_user_id"))));
    }

    private long insertFileResource(
            Long fileOrderId,
            long ownerUserId,
            String sourceType,
            String visibility,
            String uploadStatus) {
        return insertFileResource(fileOrderId, ownerUserId, sourceType, visibility, uploadStatus, "case.stl");
    }

    private long insertFileResource(
            Long fileOrderId,
            long ownerUserId,
            String sourceType,
            String visibility,
            String uploadStatus,
            String originalFilename) {
        String objectKey = "task-9d2/" + UUID.randomUUID() + ".stl";
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (order_id, owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:orderId, :ownerUserId, :sourceType, :visibility, 'test-bucket', :objectKey,
                             :originalFilename, 'model/stl', 128, :uploadStatus, 'ACTIVE')
                        """)
                .param("orderId", fileOrderId)
                .param("ownerUserId", ownerUserId)
                .param("sourceType", sourceType)
                .param("visibility", visibility)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("uploadStatus", uploadStatus)
                .update();
        return jdbcClient.sql("SELECT file_id FROM file_resource WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private long notificationCount(long notificationOrderId, String eventType, String audienceRole) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM notification_event
                        WHERE order_id = :orderId
                          AND event_type = :eventType
                          AND audience_role = :audienceRole
                        """)
                .param("orderId", notificationOrderId)
                .param("eventType", eventType)
                .param("audienceRole", audienceRole)
                .query(Long.class)
                .single();
    }

    private void assertCreateOrderWithFileRejected(long fileId, org.hamcrest.Matcher<Integer> statusMatcher) throws Exception {
        String request = """
                {
                  "product_type": "REGULAR_CROWN",
                  "form_data": {
                    "patient_name": "赵六",
                    "tooth_position": "46"
                  },
                  "file_ids": [%d]
                }
                """.formatted(fileId);

        mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().is(statusMatcher));
    }
}
