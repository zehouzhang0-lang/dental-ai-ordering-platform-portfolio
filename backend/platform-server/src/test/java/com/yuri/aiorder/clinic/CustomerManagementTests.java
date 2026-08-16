package com.yuri.aiorder.clinic;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest
@AutoConfigureMockMvc
class CustomerManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    private long clinicId;
    private long productId;
    private long templateId;
    private String clinicCode;
    private String updatedClinicName;
    private String productType;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        clinicCode = "CM" + suffix;
        updatedClinicName = "完整档案客户-" + suffix;
        jdbcClient.sql("""
                        INSERT INTO clinic (clinic_code, clinic_name, contact_name, contact_phone, status)
                        VALUES (:clinicCode, :clinicName, '初始联系人', '00000000001', 'ACTIVE')
                        """)
                .param("clinicCode", clinicCode)
                .param("clinicName", "客户管理测试-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_code = :clinicCode")
                .param("clinicCode", clinicCode).query(Long.class).single();

        productType = "CUSTOMER_PRICE_" + suffix;
        jdbcClient.sql("""
                        INSERT INTO product_catalog
                            (product_type, product_name, material_spec, base_price_cents, currency, status)
                        VALUES (:productType, :productName, '测试材料', 100000, 'CNY', 'ACTIVE')
                        """)
                .param("productType", productType)
                .param("productName", "客户价测试产品-" + suffix)
                .update();
        jdbcClient.sql("""
                        INSERT INTO form_field_config
                            (product_type, field_key, field_label, field_type, required_flag, sort_order, status)
                        VALUES (:productType, 'acceptance_note', '验收备注', 'text', 0, 10, 'ACTIVE')
                        """)
                .param("productType", productType)
                .update();
        productId = jdbcClient.sql("SELECT product_id FROM product_catalog WHERE product_type = :productType")
                .param("productType", productType).query(Long.class).single();
        templateId = jdbcClient.sql("""
                        SELECT template_id FROM customer_print_template
                        WHERE document_type = 'ORDER_SHEET' AND status = 'ACTIVE'
                        ORDER BY template_id LIMIT 1
                        """).query(Long.class).single();
    }

    @Test
    void csCanSearchByCodeAndMaintainCompleteCustomerProfile() throws Exception {
        mockMvc.perform(get("/clinics")
                        .param("keyword", clinicCode)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].clinic_code").value(clinicCode));

        String update = """
                {
                  "clinic_code": "%s",
                  "clinic_name": "%s",
                  "contact_name": "林主任",
                  "contact_phone": "00000000004",
                  "contact_email": "clinic@example.com",
                  "business_region": "华东一区",
                  "salesperson": "张伟",
                  "customer_type": "CLINIC",
                  "settlement_type": "MONTHLY",
                  "organization_nature": "PRIVATE",
                  "business_level": "A",
                  "default_shipping_method": "EXPRESS",
                  "status": "ACTIVE",
                  "invoice_profile": {
                    "invoice_title": "完整档案客户",
                    "tax_number": "91310000TEST",
                    "bank_name": "测试银行",
                    "bank_account": "622200001",
                    "registered_address": "上海市测试路 1 号",
                    "registered_phone": "021-12345678"
                  },
                  "addresses": [{
                    "address_label": "总院",
                    "recipient_name": "林主任",
                    "recipient_phone": "00000000004",
                    "province": "上海市",
                    "city": "上海市",
                    "district": "徐汇区",
                    "detail_address": "测试路 1 号",
                    "shipping_method": "EXPRESS",
                    "default_flag": true,
                    "status": "ACTIVE"
                  }],
                  "doctors": [{
                    "doctor_name": "林医生",
                    "phone": "00000000004",
                    "email": "doctor@example.com",
                    "position_title": "主任医师",
                    "primary_flag": true,
                    "notes": "主要医生",
                    "status": "ACTIVE"
                  }],
                  "documents": [{
                    "document_category": "CONTRACT",
                    "document_name": "2026 年度合作合同",
                    "document_no": "HT-2026-001",
                    "valid_from": "2026-01-01",
                    "valid_until": "2026-12-31",
                    "file_id": null,
                    "status": "ACTIVE",
                    "notes": "年度合同"
                  }],
                  "prices": [{
                    "product_id": %d,
                    "custom_price_cents": 88000,
                    "currency": "CNY",
                    "effective_from": "2026-01-01",
                    "effective_until": "2026-12-31",
                    "status": "ACTIVE",
                    "price_note": "A 级客户专享价"
                  }],
                  "preferences": {"color":"A2","note":"优先顺丰"},
                  "template_bindings": [{"document_type":"ORDER_SHEET","template_id":%d}]
                }
                """.formatted(clinicCode, updatedClinicName, productId, templateId);

        mockMvc.perform(put("/clinics/{clinicId}/management", clinicId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinic.business_region").value("华东一区"))
                .andExpect(jsonPath("$.data.invoice_profile.tax_number").value("91310000TEST"))
                .andExpect(jsonPath("$.data.addresses[0].default_flag").value(true))
                .andExpect(jsonPath("$.data.doctors[0].primary_flag").value(true))
                .andExpect(jsonPath("$.data.documents[0].document_no").value("HT-2026-001"))
                .andExpect(jsonPath("$.data.template_bindings[0].template_id").value(templateId));

        assertEquals(88000L, jdbcClient.sql("""
                        SELECT price_cents FROM clinic_product_price
                        WHERE clinic_id = :clinicId AND product_id = :productId
                        """)
                .param("clinicId", clinicId)
                .param("productId", productId)
                .query(Long.class)
                .single());

        mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 9702L)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_type":"%s","form_data":{},"file_ids":[],"draft":true}
                                """.formatted(productType)))
                .andExpect(status().isOk());

        assertEquals(88000L, jdbcClient.sql("""
                        SELECT quoted_price_cents FROM orders
                        WHERE clinic_id = :clinicId AND product_type = :productType
                        ORDER BY order_id DESC LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .param("productType", productType)
                .query(Long.class)
                .single());
        assertEquals("CUSTOMER_PRICE", jdbcClient.sql("""
                        SELECT pricing_source FROM orders
                        WHERE clinic_id = :clinicId AND product_type = :productType
                        ORDER BY order_id DESC LIMIT 1
                        """)
                .param("clinicId", clinicId)
                .param("productType", productType)
                .query(String.class)
                .single());
    }

    @Test
    void blacklistBlocksDoctorOrderAndReleaseRestoresGate() throws Exception {
        mockMvc.perform(post("/clinics/{clinicId}/blacklist", clinicId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"严重欠费","overdue_amount_cents":128000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blacklist.active").value(true))
                .andExpect(jsonPath("$.data.clinic.blacklisted").value(true));

        mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 9701L)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product_type":"REGULAR_CROWN","form_data":{},"file_ids":[],"draft":true}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/clinics/{clinicId}/blacklist/release", clinicId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"release_reason":"欠款已结清"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blacklist.active").value(false));
    }
}
