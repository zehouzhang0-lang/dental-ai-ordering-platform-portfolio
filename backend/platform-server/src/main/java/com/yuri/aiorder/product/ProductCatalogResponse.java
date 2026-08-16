package com.yuri.aiorder.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record ProductCatalogResponse(
        @JsonProperty("product_id") long productId,
        @JsonProperty("product_type") String productType,
        @JsonProperty("product_name") String productName,
        @JsonProperty("material_spec") String materialSpec,
        @JsonProperty("base_price_cents") long basePriceCents,
        String currency,
        String status,
        @JsonProperty("price_note") String priceNote,
        @JsonProperty("created_at") LocalDateTime createdAt,
        @JsonProperty("updated_at") LocalDateTime updatedAt) {}
