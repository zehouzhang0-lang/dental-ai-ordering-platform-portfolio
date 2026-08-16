package com.yuri.aiorder.clinic;

import static org.hamcrest.Matchers.hasSize;
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
class ClinicPreferenceTests {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;
    private long otherClinicId;
    private long doctorUserId;
    private long otherDoctorUserId;
    private String clinicName;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        clinicName = "客户偏好测试诊所-" + suffix;
        clinicId = createClinic(clinicName, "林医生", "00000000002");
        otherClinicId = createClinic("客户偏好其他诊所-" + suffix, "赵医生", "00000000003");
        doctorUserId = 910_000_000L + Math.abs(suffix.hashCode());
        otherDoctorUserId = doctorUserId + 1;
    }

    @Test
    void csCanListClinicAndMaintainPreference() throws Exception {
        mockMvc.perform(get("/clinics")
                        .param("keyword", clinicName)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].clinic_id").value(clinicId))
                .andExpect(jsonPath("$.data.items[0].clinic_name").value(clinicName));

        String preferenceRequest = """
                {
                  "color": "A2",
                  "contact": "邻接偏紧",
                  "occlusion": "咬合空开 1mm",
                  "margin": "龈下 0.5mm",
                  "shape": "自然圆润",
                  "material": "氧化锆",
                  "note": "客户偏好第一增量"
                }
                """;

        mockMvc.perform(put("/clinics/{clinicId}/preference", clinicId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preferenceRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinic_id").value(clinicId))
                .andExpect(jsonPath("$.data.preferences.color").value("A2"))
                .andExpect(jsonPath("$.data.preferences.occlusion").value("咬合空开 1mm"))
                .andExpect(jsonPath("$.data.preferences.note").value("客户偏好第一增量"));

        mockMvc.perform(get("/clinics/{clinicId}/preference", clinicId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preferences.material").value("氧化锆"));
    }

    @Test
    void doctorCanOnlyReadOwnClinicPreference() throws Exception {
        seedDoctor(doctorUserId, clinicId);
        seedDoctor(otherDoctorUserId, otherClinicId);
        seedPreference(clinicId, "color", "\"A1\"");

        mockMvc.perform(get("/clinics/{clinicId}/preference", clinicId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", doctorUserId)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinic_id").value(clinicId))
                .andExpect(jsonPath("$.data.preferences.color").value("A1"));

        mockMvc.perform(get("/clinics/{clinicId}/preference", otherClinicId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", doctorUserId)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());
    }

    @Test
    void workerCannotReadClinicPreference() throws Exception {
        mockMvc.perform(get("/clinics/{clinicId}/preference", clinicId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 9601L))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvedOrderKeepsCustomerRequirementSnapshotAfterPreferenceChanges() throws Exception {
        seedPreference(clinicId, "contact", "\"邻接偏紧\"");
        String orderNo = "SNAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, product_type, form_data, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 'REGULAR_CROWN', JSON_OBJECT('tooth_position', '11'),
                             'PENDING_CS_REVIEW', 'PENDING_REVIEW')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .update();
        long orderId = jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
        String snapshot = "客户档案特殊要求（初审时自动带入）\n- 邻接：邻接偏紧";

        mockMvc.perform(post("/orders/{orderId}/review", orderId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "APPROVE",
                                  "production_note": "%s"
                                }
                                """.formatted(snapshot.replace("\n", "\\n"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.production_note").value(snapshot));

        mockMvc.perform(put("/clinics/{clinicId}/preference", clinicId)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contact\":\"邻接偏松\"}"))
                .andExpect(status().isOk());

        String storedSnapshot = jdbcClient.sql("SELECT production_note FROM orders WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(storedSnapshot)
                .isEqualTo(snapshot)
                .doesNotContain("邻接偏松");
    }

    private long createClinic(String clinicName, String contactName, String contactPhone) {
        jdbcClient.sql("""
                        INSERT INTO clinic (clinic_name, contact_name, contact_phone, status)
                        VALUES (:clinicName, :contactName, :contactPhone, 'ACTIVE')
                        """)
                .param("clinicName", clinicName)
                .param("contactName", contactName)
                .param("contactPhone", contactPhone)
                .update();
        return jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
    }

    private void seedDoctor(long userId, long clinicId) {
        jdbcClient.sql("""
                        INSERT INTO system_user (user_id, username, password_hash, display_name, clinic_id, user_type, status)
                        VALUES (:userId, :username, 'pbkdf2_sha256$120000$test$test', :displayName, :clinicId, 'DOCTOR', 'ACTIVE')
                        """)
                .param("userId", userId)
                .param("username", "doctor-" + userId)
                .param("displayName", "测试医生-" + userId)
                .param("clinicId", clinicId)
                .update();
    }

    private void seedPreference(long clinicId, String key, String jsonValue) {
        jdbcClient.sql("""
                        INSERT INTO customer_preference (clinic_id, preference_key, preference_value)
                        VALUES (:clinicId, :preferenceKey, CAST(:preferenceValue AS JSON))
                        """)
                .param("clinicId", clinicId)
                .param("preferenceKey", key)
                .param("preferenceValue", jsonValue)
                .update();
    }
}
