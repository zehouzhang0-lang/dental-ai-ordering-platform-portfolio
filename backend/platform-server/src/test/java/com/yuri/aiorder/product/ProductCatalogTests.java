package com.yuri.aiorder.product;

import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProductCatalogTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void csCanCreateUpdateAndListProductCatalog() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String productType = "zirconia_crown_" + suffix;
        String productName = "氧化锆全冠-" + suffix;

        String createRequest = """
                {
                  "product_type": "%s",
                  "product_name": "%s",
                  "material_spec": "氧化锆 / A2 / 标准厚度",
                  "base_price_cents": 88000,
                  "currency": "CNY",
                  "status": "ACTIVE",
                  "price_note": "一期人工维护基础价，不代表自动报价"
                }
                """.formatted(productType, productName);

        mockMvc.perform(post("/products")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_type").value(productType.toUpperCase()))
                .andExpect(jsonPath("$.data.product_name").value(productName))
                .andExpect(jsonPath("$.data.base_price_cents").value(88000))
                .andExpect(jsonPath("$.data.price_note").value("一期人工维护基础价，不代表自动报价"));

        long productId = jdbcClient.sql("""
                        SELECT product_id
                        FROM product_catalog
                        WHERE product_type = :productType
                        """)
                .param("productType", productType.toUpperCase())
                .query(Long.class)
                .single();

        mockMvc.perform(get("/products")
                        .param("keyword", suffix)
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].product_id").value(productId))
                .andExpect(jsonPath("$.data.items[0].material_spec").value("氧化锆 / A2 / 标准厚度"));

        String updateRequest = """
                {
                  "product_name": "%s-更新",
                  "material_spec": "氧化锆 / A3 / 加厚",
                  "base_price_cents": 92000,
                  "currency": "CNY",
                  "status": "INACTIVE",
                  "price_note": "人工停用旧价格"
                }
                """.formatted(productName);

        mockMvc.perform(put("/products/{productId}", productId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .header("X-Bootstrap-User-Id", 8001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.product_id").value(productId))
                .andExpect(jsonPath("$.data.product_name").value(productName + "-更新"))
                .andExpect(jsonPath("$.data.base_price_cents").value(92000))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void doctorCannotReadInternalProductPrices() throws Exception {
        mockMvc.perform(get("/products")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 7001L)
                        .header("X-Bootstrap-Clinic-Id", 1001L))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCanReadActiveOrderProductsWithoutInternalPrices() throws Exception {
        mockMvc.perform(get("/doctor/products")
                        .header("X-Bootstrap-Role", "DOCTOR")
                        .header("X-Bootstrap-User-Id", 7001L)
                        .header("X-Bootstrap-Clinic-Id", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].product_id").exists())
                .andExpect(jsonPath("$.data[0].product_type").isString())
                .andExpect(jsonPath("$.data[0].product_name").isString())
                .andExpect(jsonPath("$.data[0].base_price_cents").doesNotExist())
                .andExpect(jsonPath("$.data[0].price_note").doesNotExist())
                .andExpect(jsonPath("$.data[0].currency").doesNotExist());
    }

    @Test
    void rejectsInvalidBasePrice() throws Exception {
        String productType = "INVALID_PRICE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String createRequest = """
                {
                  "product_type": "%s",
                  "product_name": "非法价格产品",
                  "material_spec": "测试材料",
                  "base_price_cents": 0,
                  "currency": "CNY",
                  "status": "ACTIVE"
                }
                """.formatted(productType);

        mockMvc.perform(post("/products")
                        .header("X-Bootstrap-Role", "CS")
                        .header("X-Bootstrap-User-Id", 8002L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isBadRequest());
    }
}
