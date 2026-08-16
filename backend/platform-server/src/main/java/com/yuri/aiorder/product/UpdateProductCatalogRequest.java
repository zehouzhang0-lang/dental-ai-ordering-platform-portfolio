package com.yuri.aiorder.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record UpdateProductCatalogRequest(
        @JsonProperty("product_name") @NotBlank String productName,
        @JsonProperty("material_spec") String materialSpec,
        @JsonProperty("base_price_cents") Long basePriceCents,
        String currency,
        String status,
        @JsonProperty("price_note") String priceNote) {}
