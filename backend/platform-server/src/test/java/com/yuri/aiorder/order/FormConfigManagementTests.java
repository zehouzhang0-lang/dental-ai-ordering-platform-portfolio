package com.yuri.aiorder.order;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class FormConfigManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void adminCanCreateUpdateAndDeactivateFormFieldWhileDoctorCannotManageIt() throws Exception {
        String adminToken = login("admin", "change-me-admin");
        String doctorToken = login("doctor", "change-me-doctor");
        String productType = "FORM_CRUD_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();

        String createRequest = """
                {
                  "product_type": "%s",
                  "field_key": "margin_design",
                  "field_label": "边缘设计",
                  "field_type": "select",
                  "is_required": true,
                  "options": ["肩台", "羽状"],
                  "sort_order": 10
                }
                """.formatted(productType);

        mockMvc.perform(post("/form-configs")
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isForbidden());

        String createResponse = mockMvc.perform(post("/form-configs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field_id").isNumber())
                .andExpect(jsonPath("$.data.product_type").value(productType))
                .andExpect(jsonPath("$.data.field_key").value("margin_design"))
                .andExpect(jsonPath("$.data.field_label").value("边缘设计"))
                .andExpect(jsonPath("$.data.field_type").value("select"))
                .andExpect(jsonPath("$.data.is_required").value(true))
                .andExpect(jsonPath("$.data.options[0]").value("肩台"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long fieldId = ((Number) JsonPath.read(createResponse, "$.data.field_id")).longValue();

        mockMvc.perform(get("/form-configs")
                        .header("Authorization", "Bearer " + doctorToken)
                        .param("product_type", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].field_key").value("margin_design"));

        String updateRequest = """
                {
                  "field_label": "边缘设计要求",
                  "is_required": false,
                  "options": ["圆肩", "浅凹"],
                  "sort_order": 20,
                  "status": "INACTIVE"
                }
                """;

        mockMvc.perform(put("/form-configs/{fieldId}", fieldId)
                        .header("Authorization", "Bearer " + doctorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/form-configs/{fieldId}", fieldId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.field_id").value(fieldId))
                .andExpect(jsonPath("$.data.field_label").value("边缘设计要求"))
                .andExpect(jsonPath("$.data.is_required").value(false))
                .andExpect(jsonPath("$.data.options[0]").value("圆肩"))
                .andExpect(jsonPath("$.data.sort_order").value(20))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/form-configs")
                        .header("Authorization", "Bearer " + doctorToken)
                        .param("product_type", productType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    private String login(String username, String password) throws Exception {
        String portal = switch (username) {
            case "doctor" -> "DOCTOR";
            case "cs" -> "CS";
            case "worker" -> "PRODUCTION";
            default -> "ADMIN";
        };
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s","portal":"%s"}
                                """.formatted(username, password, portal)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("accessToken").asText();
    }
}
