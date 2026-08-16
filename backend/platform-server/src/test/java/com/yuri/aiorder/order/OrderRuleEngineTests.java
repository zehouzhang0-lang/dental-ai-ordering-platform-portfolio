package com.yuri.aiorder.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * TASK-034 F 批次：下单规则后端化。
 *
 * <p>这些测试证明的是「后端认了」——F 批次之前 try_in_required、过程确认、订单周期、运输类型
 * 在前端能勾选，后端检索命中 0，没有任何可验证的后果（GOAL-033 调研结论五）。
 * 因此每条测试都断言一个**可观察的后果**：账单多一项、交期变化几天、提交被拦、客服端出现提示。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderRuleEngineTests {

    private static final long DOCTOR_USER_ID = 9741L;
    private static final long OTHER_DOCTOR_USER_ID = 9742L;
    private static final long CS_USER_ID = 9743L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long clinicId;
    private long patientId;
    private CatalogFixture catalog;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:name)")
                .param("name", "下单规则测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :name")
                .param("name", "下单规则测试诊所-" + suffix)
                .query(Long.class)
                .single();
        upsertUser(DOCTOR_USER_ID, "rule-doctor-" + suffix, "DOCTOR", clinicId);
        upsertUser(OTHER_DOCTOR_USER_ID, "rule-other-doctor-" + suffix, "DOCTOR", clinicId);
        upsertUser(CS_USER_ID, "rule-cs-" + suffix, "CS", null);
        patientId = createPatient("规则测试患者");
        catalog = createActiveCatalog();
    }

    // ---------------------------------------------------------------------
    // 试戴：独立计价项 + 同一订单继续选成品
    // ---------------------------------------------------------------------

    @Test
    void tryInSelectionCreatesItsOwnBillItemAndTheFinalProductStaysOnTheSameOrder() throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "try_in_required": true
                """));

        // 验收「勾选试戴后账单出现对应计价项」：试戴与成品是两条，不是并进产品那条。
        mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.try_in.try_in_required").value(true))
                .andExpect(jsonPath("$.data.try_in.try_in_status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.bill_items.length()").value(2))
                .andExpect(jsonPath("$.data.bill_items[?(@.item_code=='TRY_IN')].item_name")
                        .value("试戴"))
                // 客户原话「试戴费用待报价，不预填金额」。
                .andExpect(jsonPath("$.data.bill_items[?(@.item_code=='TRY_IN')].pricing_status")
                        .value("PENDING_QUOTE"));

        String orderNoBefore = orderNo(orderId);

        mockMvc.perform(post("/orders/{orderId}/try-in/complete", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"试戴合适\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.try_in.try_in_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.try_in.can_select_final_product").value(true));

        // 试戴完成后医生在**原订单**上选定成品与材料。
        mockMvc.perform(post("/orders/{orderId}/try-in/finalize", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "product_id": %d,
                                  "material_selections": [{"item_id": %d, "quantity": 1}],
                                  "note": "按试戴结果确认成品"
                                }
                                """.formatted(catalog.finalProductId(), catalog.materialId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.try_in.try_in_status").value("FINALIZED"))
                .andExpect(jsonPath("$.data.bill_items[?(@.item_code=='PRODUCT')].item_name")
                        .value("试戴后成品"));

        // 不新建订单：订单号与订单数都不变，产品换成了成品。
        assertThat(orderNo(orderId)).isEqualTo(orderNoBefore);
        assertThat(orderCountForPatient()).isEqualTo(1L);
        Long productId = jdbcClient.sql("SELECT product_id FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        assertThat(productId).isEqualTo(catalog.finalProductId());
    }

    // ---------------------------------------------------------------------
    // 交期计算引擎
    // ---------------------------------------------------------------------

    @Test
    void eachProcessConfirmationAddsExactlyOneDayToTheDeliveryDate() throws Exception {
        long none = submitSingleItemGroup(formValues("""
                "process_reviews": []
                """));
        long one = submitSingleItemGroup(formValues("""
                "process_reviews": ["CAD_DESIGN"]
                """));
        long two = submitSingleItemGroup(formValues("""
                "process_reviews": ["CAD_DESIGN", "POST_GLAZING_PHOTOS"]
                """));

        LocalDate baseline = deliveryDate(none);
        assertThat(deliveryDate(one)).isEqualTo(baseline.plusDays(1));
        assertThat(deliveryDate(two)).isEqualTo(baseline.plusDays(2));

        mockMvc.perform(deliveryPlan(two, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.process_confirmation_count").value(2))
                .andExpect(jsonPath("$.data.process_confirmation_days").value(2))
                .andExpect(jsonPath("$.data.process_confirmations.length()").value(2))
                // sequence 按制作顺序，不按医生勾选顺序。
                .andExpect(jsonPath("$.data.process_confirmations[0].confirmation_code")
                        .value("CAD_DESIGN"))
                .andExpect(jsonPath("$.data.process_confirmations[1].confirmation_code")
                        .value("POST_GLAZING_PHOTOS"));
    }

    @Test
    void rushOrderShortensTheDeliveryDateAndStaysDistinguishableFromTheNormalCycle() throws Exception {
        long normal = submitSingleItemGroup(formValues("""
                "case_priority": "NORMAL"
                """));
        long rush = submitSingleItemGroup(formValues("""
                "case_priority": "RUSH_3_DAYS"
                """));
        long sameDay = submitSingleItemGroup(formValues("""
                "case_priority": "SAME_DAY"
                """));

        assertThat(deliveryDate(rush)).isBefore(deliveryDate(normal));
        assertThat(deliveryDate(sameDay)).isBefore(deliveryDate(rush));

        // 「可区分」不只是日期不同：计划里能看出上限是哪个规则给的。
        mockMvc.perform(deliveryPlan(rush, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority_code").value("RUSH_3_DAYS"))
                .andExpect(jsonPath("$.data.priority_cap_days").value(3))
                .andExpect(jsonPath("$.data.production_days").value(3));
        mockMvc.perform(deliveryPlan(normal, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority_cap_days").value(-1));
    }

    @Test
    void shippingMethodChangesTheArrivalDateThroughTransitDays() throws Exception {
        long courier = submitSingleItemGroup(formValues("""
                "shipping_method": "COURIER"
                """));
        long selfPickup = submitSingleItemGroup(formValues("""
                "shipping_method": "SELF_PICKUP"
                """));

        assertThat(deliveryDate(selfPickup)).isBefore(deliveryDate(courier));
        mockMvc.perform(deliveryPlan(selfPickup, doctorHeaders()))
                .andExpect(jsonPath("$.data.transit_days").value(0));
    }

    /**
     * 边界要求：占位周期必须能被界面识别出来标「待确认」，不得表现为正式承诺交期。
     */
    @Test
    void deliveryEstimateIsMarkedPlaceholderUntilCustomerConfirmsTheStandardCycle() throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "case_priority": "NORMAL"
                """));

        mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimate_status").value("PLACEHOLDER"))
                .andExpect(jsonPath("$.data.placeholder_rules.length()").value(
                        org.hamcrest.Matchers.greaterThan(0)));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_estimate_status").value("PLACEHOLDER"));
    }

    /**
     * 占位值转正走配置，不改代码：把标准周期确认掉之后，同一条链路算出的交期跟着变，
     * 且不再标「待确认」。
     */
    @Test
    void confirmingStandardCycleThroughConfigurationChangesDeliveryWithoutCodeChange() throws Exception {
        // 这条改的是全局配置，必须还原：本类其它测试和别的测试类都按占位周期算交期。
        try {
            mockMvc.perform(put("/ordering-rules/{ruleType}/{ruleKey}", "PRODUCT_CYCLE", "REGULAR_CROWN")
                            .header("X-Bootstrap-Role", "ADMIN")
                            .header("X-Bootstrap-User-Id", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"numeric_value\": 4, \"confirmation_status\": \"CONFIRMED\"}"))
                    .andExpect(status().isOk());

            long orderId = submitSingleItemGroup(formValues("""
                    "shipping_method": "SELF_PICKUP"
                    """));

            mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.base_cycle_days").value(4))
                    .andExpect(jsonPath("$.data.production_days").value(4))
                    .andExpect(jsonPath("$.data.estimate_status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.placeholder_rules.length()").value(0));
        } finally {
            jdbcClient.sql("""
                            UPDATE ordering_rule_config
                            SET numeric_value = 5, confirmation_status = 'PLACEHOLDER'
                            WHERE rule_type = 'PRODUCT_CYCLE' AND rule_key = 'REGULAR_CROWN'
                            """)
                    .update();
        }
    }

    // ---------------------------------------------------------------------
    // 订单类型与回寄运单号
    // ---------------------------------------------------------------------

    @Test
    void impressionReworkAndReturnOrdersCannotBeSubmittedWithoutInboundTrackingNo() throws Exception {
        for (String orderType : new String[] {"IMPRESSION", "REWORK", "RETURN"}) {
            long groupId = createGroupWithItem(formValues("""
                    "order_type": "%s"
                    """.formatted(orderType)));
            mockMvc.perform(submitRequest(groupId))
                    .andExpect(status().isBadRequest());
            assertThat(internalStatus(groupId)).isEqualTo("DRAFT");
        }

        // 补上运单号后同一份草稿就能提交——证明拦的是缺运单号，不是订单类型本身。
        long groupId = createGroupWithItem(formValues("""
                "order_type": "IMPRESSION", "inbound_tracking_no": "SF1234567890"
                """));
        mockMvc.perform(submitRequest(groupId)).andExpect(status().isOk());
    }

    @Test
    void designOnlyOrderDoesNotRequireInboundTrackingNo() throws Exception {
        long groupId = createGroupWithItem(formValues("""
                "order_type": "DESIGN_ONLY"
                """));
        mockMvc.perform(submitRequest(groupId)).andExpect(status().isOk());
    }

    /**
     * 「前端能选、后端不认」的反向证明：不认识的取值必须 400，而不是被当成默认值静默吞掉。
     */
    @Test
    void unknownOrderingRuleValuesAreRejectedInsteadOfSilentlyDefaulted() throws Exception {
        long groupId = createGroup();
        mockMvc.perform(addItemRequest(groupId, 1, formValues("""
                "order_type": "MYSTERY_TYPE"
                """)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(addItemRequest(groupId, 1, formValues("""
                "case_priority": "NEXT_WEEK"
                """)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(addItemRequest(groupId, 1, formValues("""
                "shipping_method": "DRONE"
                """)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(addItemRequest(groupId, 1, formValues("""
                "process_reviews": ["NOT_A_STEP"]
                """)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------------
    // 医生调整到货时间 → 客服端时间异常提示
    // ---------------------------------------------------------------------

    @Test
    void doctorPullingTheDeliveryDateForwardRaisesTheCsVarianceAlert() throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "case_priority": "NORMAL"
                """));

        // 提交时没填要求到货日：没有可比对象，就不该有异常提示。
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_alert").isEmpty());

        LocalDate feasible = deliveryDate(orderId);
        mockMvc.perform(put("/orders/{orderId}/delivery-plan/requested-date", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requested_delivery_date": "%s", "reason": "患者提前复诊"}
                                """.formatted(feasible.minusDays(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variance_days").value(-3))
                .andExpect(jsonPath("$.data.variance_flag").value("EARLIER_THAN_FEASIBLE"));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_alert").value("EARLIER_THAN_FEASIBLE"))
                .andExpect(jsonPath("$.data.delivery_variance_days").value(-3))
                .andExpect(jsonPath("$.data.delivery_alert_message")
                        .value(org.hamcrest.Matchers.containsString("早于系统可行交期")));

        // 医生把日期放回可行范围，提示随之消失——不能只会亮不会灭。
        mockMvc.perform(put("/orders/{orderId}/delivery-plan/requested-date", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requested_delivery_date": "%s"}
                                """.formatted(feasible)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variance_flag").value("NONE"));
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(jsonPath("$.data.delivery_alert").isEmpty());
    }

    // ---------------------------------------------------------------------
    // 过程确认等待：延后交期并给出可见提示
    // ---------------------------------------------------------------------

    @Test
    void processConfirmationLeftUnansweredPostponesDeliveryAndSurfacesAWaitingAlert() throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "process_reviews": ["CAD_DESIGN"]
                """));
        LocalDate beforeRequest = deliveryDate(orderId);

        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/request",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].confirmation_status").value("AWAITING_DOCTOR"));

        // 刚发起时还在宽限期内，交期不该动。
        assertThat(deliveryDate(orderId)).isEqualTo(beforeRequest);

        // 把发起时间往回拨到宽限期之外——这是「医生长时间未确认」的唯一可测形态。
        int graceDays = graceDays();
        jdbcClient.sql("""
                        UPDATE order_process_confirmation
                        SET requested_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :days DAY)
                        WHERE order_id = :orderId
                          AND confirmation_code = 'CAD_DESIGN'
                        """)
                .param("days", graceDays + 4)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waiting_days").value(4))
                .andExpect(jsonPath("$.data.delivery_alert").value("WAITING_DOCTOR_CONFIRMATION"))
                .andExpect(jsonPath("$.data.process_confirmations[0].overdue").value(true));
        assertThat(deliveryDate(orderId)).isEqualTo(beforeRequest.plusDays(4));

        // 客服端同样看得到等待提示。
        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery_alert").value("WAITING_DOCTOR_CONFIRMATION"));

        // 医生确认后等待停止累加，但已经耽误的天数留在交期里，不倒回去。
        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/respond",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\": true, \"comment\": \"可以继续\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].confirmation_status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data[0].overdue").value(false));
        assertThat(deliveryDate(orderId)).isEqualTo(beforeRequest.plusDays(4));
        mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                .andExpect(jsonPath("$.data.delivery_alert").isEmpty());
    }

    // ---------------------------------------------------------------------
    // 患者联动
    // ---------------------------------------------------------------------

    @Test
    void patientCreatedWhileOrderingImmediatelyAppearsInPatientManagementAndCarriesIntoTheOrder()
            throws Exception {
        String response = mockMvc.perform(post("/patients")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patient_name": "下单时新建的患者", "patient_gender": "FEMALE",
                                 "treatment_status": "IN_TREATMENT"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long newPatientId = objectMapper.readTree(response).path("data").path("patient_id").asLong();

        mockMvc.perform(get("/patients")
                        .param("keyword", "下单时新建的患者")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].patient_id").value(newPatientId));

        // 既有患者直接带出资料：订单落库时带上 patient_id，客服端看到的就是该患者姓名。
        long groupId = createGroup(newPatientId);
        addItem(groupId, 1, formValues(""));
        long orderId = onlyOrderId(groupId);
        mockMvc.perform(submitRequest(groupId)).andExpect(status().isOk());

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patient_id").value(newPatientId))
                .andExpect(jsonPath("$.data.patient_name").value("下单时新建的患者"));
    }

    // ---------------------------------------------------------------------
    // 越权拒绝
    // ---------------------------------------------------------------------

    @Test
    void processConfirmationRolesAreSeparated() throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "process_reviews": ["CAD_DESIGN"]
                """));

        // 医生不能替内部发起确认请求。
        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/request",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/request",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // 客服不能替医生确认。
        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/respond",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\": true}"))
                .andExpect(status().isForbidden());

        // 另一位医生也不能确认别人的订单。
        mockMvc.perform(post("/orders/{orderId}/process-confirmations/{code}/respond",
                        orderId, "CAD_DESIGN")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingTryInPermissionFromCsDeniesTryInCompletionEvenThoughThePortalRoleMatches()
            throws Exception {
        long orderId = submitSingleItemGroup(formValues("""
                "try_in_required": true
                """));
        revokePermission("CS", "order:try-in-manage");
        try {
            mockMvc.perform(post("/orders/{orderId}/try-in/complete", orderId)
                            .header("X-Bootstrap-Role", "CS")
                            .header("X-Bootstrap-User-Id", CS_USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        } finally {
            grantPermission("CS", "order:try-in-manage");
        }
    }

    @Test
    void orderingRuleConfigurationRequiresItsOwnPermission() throws Exception {
        mockMvc.perform(get("/ordering-rules")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", CS_USER_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ordering-rules")
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 1L))
                .andExpect(status().isOk());
    }

    @Test
    void doctorCannotReadAnotherDoctorsDeliveryPlan() throws Exception {
        long orderId = submitSingleItemGroup(formValues(""));
        mockMvc.perform(get("/orders/{orderId}/delivery-plan", orderId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", OTHER_DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // 测试脚手架
    // ---------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder deliveryPlan(
            long orderId, String[] headers) {
        var request = get("/orders/{orderId}/delivery-plan", orderId);
        for (int index = 0; index < headers.length; index += 2) {
            request = request.header(headers[index], headers[index + 1]);
        }
        return request;
    }

    private String[] doctorHeaders() {
        return new String[] {
                "X-Bootstrap-Role", "DOCTOR",
                "X-Bootstrap-User-Id", String.valueOf(DOCTOR_USER_ID),
                "X-Bootstrap-Clinic-Id", String.valueOf(clinicId)
        };
    }

    private LocalDate deliveryDate(long orderId) throws Exception {
        String body = mockMvc.perform(deliveryPlan(orderId, doctorHeaders()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        return LocalDate.parse(data.path("computed_delivery_date").asText());
    }

    private int graceDays() {
        return jdbcClient.sql("""
                        SELECT numeric_value FROM ordering_rule_config
                        WHERE rule_type = 'PROCESS_CONFIRMATION' AND rule_key = 'DOCTOR_GRACE_DAYS'
                        """)
                .query(Integer.class)
                .single();
    }

    private String formValues(String extra) {
        String base = "\"patient_note\": \"规则测试\"";
        return extra == null || extra.isBlank() ? base : base + ", " + extra.trim();
    }

    private long submitSingleItemGroup(String extraFormValues) throws Exception {
        long groupId = createGroupWithItem(extraFormValues);
        long orderId = onlyOrderId(groupId);
        mockMvc.perform(submitRequest(groupId)).andExpect(status().isOk());
        return orderId;
    }

    private long createGroupWithItem(String extraFormValues) throws Exception {
        long groupId = createGroup();
        addItem(groupId, 1, extraFormValues);
        return groupId;
    }

    private long createGroup() throws Exception {
        return createGroup(patientId);
    }

    private long createGroup(long targetPatientId) throws Exception {
        String response = mockMvc.perform(post("/order-case-groups")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patient_id": %d, "idempotency_key": "%s"}
                                """.formatted(targetPatientId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("group_id").asLong();
    }

    private void addItem(long groupId, int expectedDraftVersion, String extraFormValues)
            throws Exception {
        mockMvc.perform(addItemRequest(groupId, expectedDraftVersion, extraFormValues))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder addItemRequest(
            long groupId, int expectedDraftVersion, String extraFormValues) {
        return post("/order-case-groups/{groupId}/items", groupId)
                .header("X-Bootstrap-Role", "DOCTOR")
                .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                .header("X-Bootstrap-Clinic-Id", clinicId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "product_id": %d,
                          "item_client_key": "%s",
                          "form_values": {%s},
                          "material_selections": [],
                          "accessory_selections": [],
                          "file_ids": [],
                          "expected_draft_version": %d
                        }
                        """.formatted(
                                catalog.productId(),
                                UUID.randomUUID(),
                                extraFormValues,
                                expectedDraftVersion));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submitRequest(
            long groupId) {
        return post("/order-case-groups/{groupId}/submit", groupId)
                .header("X-Bootstrap-Role", "DOCTOR")
                .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                .header("X-Bootstrap-Clinic-Id", clinicId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idempotency_key": "%s", "expected_draft_version": 2}
                        """.formatted(UUID.randomUUID()));
    }

    private long onlyOrderId(long groupId) {
        return jdbcClient.sql("SELECT order_id FROM orders WHERE group_id = :groupId ORDER BY line_no")
                .param("groupId", groupId)
                .query(Long.class)
                .single();
    }

    private String internalStatus(long groupId) {
        return jdbcClient.sql("""
                        SELECT internal_status FROM orders
                        WHERE group_id = :groupId ORDER BY line_no LIMIT 1
                        """)
                .param("groupId", groupId)
                .query(String.class)
                .single();
    }

    private String orderNo(long orderId) {
        return jdbcClient.sql("SELECT order_no FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private long orderCountForPatient() {
        return jdbcClient.sql("SELECT COUNT(*) FROM orders WHERE patient_id = :patientId")
                .param("patientId", patientId)
                .query(Long.class)
                .single();
    }

    private void revokePermission(String roleCode, String permissionCode) {
        jdbcClient.sql("""
                        DELETE rp FROM system_role_permission rp
                        JOIN system_role r ON r.role_id = rp.role_id
                        JOIN system_permission p ON p.permission_id = rp.permission_id
                        WHERE r.role_code = :roleCode
                          AND p.permission_code = :permissionCode
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
                            (user_id, username, password_hash, display_name,
                             clinic_id, user_type, status)
                        VALUES
                            (:userId, :username, 'test-only', :username,
                             :clinicId, :userType, 'ACTIVE')
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

    private long createPatient(String name) {
        jdbcClient.sql("""
                        INSERT INTO patient_record (clinic_id, doctor_user_id, patient_name)
                        VALUES (:clinicId, :doctorUserId, :name)
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("name", name)
                .update();
        return jdbcClient.sql("""
                        SELECT patient_id FROM patient_record
                        WHERE clinic_id = :clinicId AND doctor_user_id = :doctorUserId
                          AND patient_name = :name
                        ORDER BY patient_id DESC LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    /**
     * 目录夹具：一个下单用产品与一个「试戴后成品」，都映射到 REGULAR_CROWN 产品类型，
     * 这样交期断言只与规则配置有关，不受目录数据影响。
     */
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
                .param("name", "下单规则目录-" + suffix)
                .update();
        long versionId = jdbcClient.sql("""
                        SELECT config_version_id FROM catalog_config_version WHERE version_no = :versionNo
                        """)
                .param("versionNo", versionNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_category_v2 (config_version_id, category_code, display_name)
                        VALUES (:versionId, :code, '固定类')
                        """)
                .param("versionId", versionId)
                .param("code", "RULE_FIXED_" + suffix)
                .update();
        long categoryId = jdbcClient.sql("""
                        SELECT category_id FROM catalog_category_v2 WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_product_v2
                            (config_version_id, category_id, product_code, display_name,
                             workflow_product_type, pricing_status, base_price_cents, currency)
                        VALUES
                            (:versionId, :categoryId, :orderCode, '下单产品',
                             'REGULAR_CROWN', 'PRICED', 1000, 'CNY'),
                            (:versionId, :categoryId, :finalCode, '试戴后成品',
                             'REGULAR_CROWN', 'PRICED', 2000, 'CNY')
                        """)
                .param("versionId", versionId)
                .param("categoryId", categoryId)
                .param("orderCode", "RULE_ORDER_" + suffix)
                .param("finalCode", "RULE_FINAL_" + suffix)
                .update();
        long productId = productId(versionId, "RULE_ORDER_" + suffix);
        long finalProductId = productId(versionId, "RULE_FINAL_" + suffix);
        jdbcClient.sql("""
                        INSERT INTO catalog_material_v2 (config_version_id, material_code, display_name)
                        VALUES (:versionId, :code, '氧化锆')
                        """)
                .param("versionId", versionId)
                .param("code", "RULE_ZIRCONIA_" + suffix)
                .update();
        long materialId = jdbcClient.sql("""
                        SELECT material_id FROM catalog_material_v2 WHERE config_version_id = :versionId
                        """)
                .param("versionId", versionId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO catalog_product_material_binding_v2
                            (config_version_id, product_id, material_id, selection_group_code,
                             required_flag, selection_mode, min_quantity, max_quantity,
                             price_increment_cents)
                        VALUES
                            (:versionId, :finalProductId, :materialId, 'MAIN_MATERIAL',
                             0, 'SINGLE', 1, 1, 200)
                        """)
                .param("versionId", versionId)
                .param("finalProductId", finalProductId)
                .param("materialId", materialId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO catalog_rule_v2
                            (config_version_id, product_id, rule_type, rule_code, rule_schema_json)
                        VALUES
                            (:versionId, :productId, 'FORM_SCHEMA', :ruleCode, CAST(:schema AS JSON))
                        """)
                .param("versionId", versionId)
                .param("productId", productId)
                .param("ruleCode", "RULE_FORM_" + suffix)
                .param("schema", """
                        {"fields": [{"key":"patient_note","label":"病例说明","type":"string","required":true}]}
                        """)
                .update();
        return new CatalogFixture(productId, finalProductId, materialId);
    }

    private long productId(long versionId, String productCode) {
        return jdbcClient.sql("""
                        SELECT product_id FROM catalog_product_v2
                        WHERE config_version_id = :versionId AND product_code = :code
                        """)
                .param("versionId", versionId)
                .param("code", productCode)
                .query(Long.class)
                .single();
    }

    private record CatalogFixture(long productId, long finalProductId, long materialId) {
    }
}
