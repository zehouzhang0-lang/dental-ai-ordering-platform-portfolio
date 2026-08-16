package com.yuri.aiorder.patient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class PatientManagementTests {

    private static final long DOCTOR_USER_ID = 9001L;
    private static final long OTHER_DOCTOR_USER_ID = 9002L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String clinicName = "患者管理测试诊所-" + suffix;

        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
    }

    @Test
    void doctorCanCreateSearchAndReadOwnPatientHistory() throws Exception {
        long patientId = createPatient(DOCTOR_USER_ID, "林一舟");

        mockMvc.perform(get("/patients")
                        .param("keyword", "一舟")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].patient_id").value(patientId))
                .andExpect(jsonPath("$.data.items[0].patient_name").value("林一舟"))
                .andExpect(jsonPath("$.data.items[0].phone").value("00000000005"))
                .andExpect(jsonPath("$.data.items[0].email").value("lin@example.com"))
                .andExpect(jsonPath("$.data.items[0].treatment_status").value("IN_TREATMENT"))
                .andExpect(jsonPath("$.data.items[0].order_count").value(0));

        mockMvc.perform(put("/patients/{patientId}", patientId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patient_name": "林一舟",
                                  "patient_age": 43,
                                  "patient_gender": "男",
                                  "date_of_birth": "1983-08-12",
                                  "phone": "00000000006",
                                  "email": "lin.updated@example.com",
                                  "medical_notes": "青霉素过敏",
                                  "tags": "VIP，种植",
                                  "treatment_status": "FOLLOW_UP",
                                  "treatment_started_at": "2026-03-01",
                                  "oral_description": "右下后牙修复复诊"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("00000000006"))
                .andExpect(jsonPath("$.data.medical_notes").value("青霉素过敏"))
                .andExpect(jsonPath("$.data.tags").value("VIP，种植"))
                .andExpect(jsonPath("$.data.treatment_status").value("FOLLOW_UP"));

        mockMvc.perform(get("/patients/{patientId}", patientId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("lin.updated@example.com"));

        String orderRequest = """
                {
                  "patient_id": %d,
                  "product_type": "REGULAR_CROWN",
                  "form_data": {
                    "patient_name": "林一舟",
                    "tooth_position": "36"
                  },
                  "file_ids": [%d]
                }
                """.formatted(patientId, insertCompletedStl(DOCTOR_USER_ID));

        String orderResponse = mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(orderResponse, "$.data.order_id")).longValue();

        mockMvc.perform(get("/patients/{patientId}/orders", patientId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].order_id").value(orderId))
                .andExpect(jsonPath("$.data.items[0].external_status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].production_note").doesNotExist())
                .andExpect(content().string(not(containsString("PENDING_CS_REVIEW"))));
    }

    @Test
    void doctorCannotBindOrReadAnotherDoctorsPatient() throws Exception {
        long otherPatientId = createPatient(OTHER_DOCTOR_USER_ID, "赵不同");

        String orderRequest = """
                {
                  "patient_id": %d,
                  "product_type": "REGULAR_CROWN",
                  "form_data": {
                    "patient_name": "赵不同",
                    "tooth_position": "11"
                  }
                }
                """.formatted(otherPatientId);

        mockMvc.perform(post("/orders")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderRequest))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/patients/{patientId}/orders", otherPatientId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/patients/{patientId}", otherPatientId)
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", DOCTOR_USER_ID)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "patient_name": "越权修改",
                                  "treatment_status": "IN_TREATMENT"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    private long insertCompletedStl(long ownerUserId) {
        String objectKey = "patient-management/" + UUID.randomUUID() + ".stl";
        jdbcClient.sql("""
                        INSERT INTO file_resource
                            (owner_user_id, source_type, visibility, bucket_name, object_key,
                             original_filename, content_type, file_size, upload_status, status)
                        VALUES
                            (:ownerUserId, 'ORDER_ATTACHMENT', 'DOCTOR', 'test-bucket', :objectKey,
                             'patient-case.stl', 'model/stl', 128, 'COMPLETED', 'ACTIVE')
                        """)
                .param("ownerUserId", ownerUserId)
                .param("objectKey", objectKey)
                .update();
        return jdbcClient.sql("SELECT file_id FROM file_resource WHERE object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private long createPatient(long doctorUserId, String patientName) throws Exception {
        String request = """
                {
                  "patient_name": "%s",
                  "patient_age": 42,
                  "patient_gender": "男",
                  "date_of_birth": "1983-08-12",
                  "phone": "00000000005",
                  "email": "lin@example.com",
                  "medical_notes": "无特殊用药",
                  "tags": "一期患者",
                  "treatment_status": "IN_TREATMENT",
                  "treatment_started_at": "2026-03-01",
                  "oral_description": "一期患者档案验收"
                }
                """.formatted(patientName);

        String response = mockMvc.perform(post("/patients")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", doctorUserId)
                        .header("X-Bootstrap-Clinic-Id", clinicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.patient_id").isNumber())
                .andExpect(jsonPath("$.data.patient_name").value(patientName))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(response, "$.data.patient_id")).longValue();
    }
}
