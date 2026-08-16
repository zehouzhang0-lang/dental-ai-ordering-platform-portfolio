package com.yuri.aiorder.production;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionMaterialExceptionManagementTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerCanCreateMaterialExceptionAndUpdateStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String exceptionNo = "MAT95_" + suffix;

        mockMvc.perform(post("/production/material-exceptions")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 952101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "exception_no": "%s",
                                  "material_code": "ZIR_%s",
                                  "material_name": "氧化锆瓷块-%s",
                                  "exception_type": "SHORTAGE",
                                  "status": "PENDING",
                                  "responsibility_owner": "仓储组",
                                  "loss_quantity": 2.50,
                                  "description": "库存低于安全线，需人工补料"
                                }
                                """.formatted(exceptionNo, suffix, suffix)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exception_no").value(exceptionNo))
                .andExpect(jsonPath("$.data.material_code").value("ZIR_" + suffix))
                .andExpect(jsonPath("$.data.exception_type").value("SHORTAGE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.responsibility_owner").value("仓储组"))
                .andExpect(jsonPath("$.data.loss_quantity").value(2.50));

        mockMvc.perform(put("/production/material-exceptions/{exceptionNo}/status", exceptionNo)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 952102L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CLOSED",
                                  "responsibility_owner": "仓储组",
                                  "description": "已人工补料并关闭"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exception_no").value(exceptionNo))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closed_at").isNotEmpty())
                .andExpect(jsonPath("$.data.description").value("已人工补料并关闭"));

        mockMvc.perform(get("/production/material-exceptions/summary")
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", 952103L)
                        .param("exception_no_prefix", exceptionNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_exception_count").value(1))
                .andExpect(jsonPath("$.data.shortage_count").value(1))
                .andExpect(jsonPath("$.data.pending_count").value(0))
                .andExpect(jsonPath("$.data.closed_count").value(1))
                .andExpect(jsonPath("$.data.responsibility_assigned_count").value(1))
                .andExpect(jsonPath("$.data.total_loss_quantity").value(2.50));
    }

    @Test
    void doctorCannotCreateOrUpdateProductionMaterialException() throws Exception {
        mockMvc.perform(post("/production/material-exceptions")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 952201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "exception_no": "MAT95_DOC_BLOCK",
                                  "material_code": "DOC_BLOCK",
                                  "material_name": "医生端不可写物料",
                                  "exception_type": "SHORTAGE"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/production/material-exceptions/{exceptionNo}/status", "MAT95_DOC_BLOCK")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 952201L)
                        .header("X-Bootstrap-Clinic-Id", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CLOSED",
                                  "description": "医生端不可更新内部处理状态"
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
