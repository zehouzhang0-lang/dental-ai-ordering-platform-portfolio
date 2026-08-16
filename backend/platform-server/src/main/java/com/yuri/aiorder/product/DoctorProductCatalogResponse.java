package com.yuri.aiorder.product;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DoctorProductCatalogResponse(
        @JsonProperty("product_id") long productId,
        @JsonProperty("product_type") String productType,
        @JsonProperty("product_name") String productName,
        @JsonProperty("material_spec") String materialSpec) {
}
