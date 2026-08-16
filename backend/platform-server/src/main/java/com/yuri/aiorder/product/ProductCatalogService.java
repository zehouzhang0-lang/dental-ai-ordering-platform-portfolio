package com.yuri.aiorder.product;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.AccessControlService;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductCatalogService {

    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final JdbcClient jdbcClient;
    private final AccessControlService accessControlService;

    public ProductCatalogService(JdbcClient jdbcClient, AccessControlService accessControlService) {
        this.jdbcClient = jdbcClient;
        this.accessControlService = accessControlService;
    }

    public OrderListResponse<ProductCatalogResponse> listProducts(
            BootstrapIdentity identity, String keyword, String status, int page, int size) {
        requireProductManagement(identity);
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = (safePage - 1) * safeSize;
        String normalizedStatus = normalizeOptionalStatus(status);

        String whereClause = """
                WHERE (:status IS NULL OR status = :status)
                  AND (:keyword IS NULL OR product_type LIKE :keyword OR product_name LIKE :keyword OR material_spec LIKE :keyword)
                """;

        List<ProductCatalogResponse> rows = bindListParams(jdbcClient.sql("""
                        SELECT product_id, product_type, product_name, material_spec,
                               base_price_cents, currency, status, price_note, created_at, updated_at
                        FROM product_catalog
                        %s
                        ORDER BY updated_at DESC, product_id DESC
                        LIMIT :limit OFFSET :offset
                        """.formatted(whereClause)), keyword, normalizedStatus)
                .param("limit", safeSize)
                .param("offset", offset)
                .query(this::mapProduct)
                .list();
        long total = bindListParams(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM product_catalog
                        %s
                        """.formatted(whereClause)), keyword, normalizedStatus)
                .query(Long.class)
                .single();
        return new OrderListResponse<>(rows, total, safePage, safeSize);
    }

    public List<DoctorProductCatalogResponse> listDoctorProducts(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "catalog:read-doctor", "doctor product catalog requires catalog:read-doctor");
        return jdbcClient.sql("""
                        SELECT product_id, product_type, product_name, material_spec
                        FROM product_catalog
                        WHERE status = 'ACTIVE'
                          AND EXISTS (
                              SELECT 1
                              FROM form_field_config form
                              WHERE form.product_type = product_catalog.product_type
                                AND form.status = 'ACTIVE'
                          )
                        ORDER BY product_name, product_id
                        """)
                .query((rs, rowNum) -> new DoctorProductCatalogResponse(
                        rs.getLong("product_id"),
                        rs.getString("product_type"),
                        rs.getString("product_name"),
                        rs.getString("material_spec")))
                .list();
    }

    @Transactional
    public ProductCatalogResponse createProduct(ProductCatalogRequest request, BootstrapIdentity identity) {
        requireProductManagement(identity);
        ProductInput input = normalizeCreate(request);
        try {
            jdbcClient.sql("""
                            INSERT INTO product_catalog
                                (product_type, product_name, material_spec, base_price_cents,
                                 currency, status, price_note, created_by_user_id)
                            VALUES
                                (:productType, :productName, :materialSpec, :basePriceCents,
                                 :currency, :status, :priceNote, :createdByUserId)
                            """)
                    .param("productType", input.productType())
                    .param("productName", input.productName())
                    .param("materialSpec", input.materialSpec())
                    .param("basePriceCents", input.basePriceCents())
                    .param("currency", input.currency())
                    .param("status", input.status())
                    .param("priceNote", input.priceNote())
                    .param("createdByUserId", identity.userId())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "product_type already exists", ex);
        }
        long productId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        return loadProduct(productId);
    }

    @Transactional
    public ProductCatalogResponse updateProduct(
            long productId, UpdateProductCatalogRequest request, BootstrapIdentity identity) {
        requireProductManagement(identity);
        ProductInput input = normalizeUpdate(productId, request);
        int updated = jdbcClient.sql("""
                        UPDATE product_catalog
                        SET product_name = :productName,
                            material_spec = :materialSpec,
                            base_price_cents = :basePriceCents,
                            currency = :currency,
                            status = :status,
                            price_note = :priceNote
                        WHERE product_id = :productId
                        """)
                .param("productId", productId)
                .param("productName", input.productName())
                .param("materialSpec", input.materialSpec())
                .param("basePriceCents", input.basePriceCents())
                .param("currency", input.currency())
                .param("status", input.status())
                .param("priceNote", input.priceNote())
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
        }
        return loadProduct(productId);
    }

    private void requireProductManagement(BootstrapIdentity identity) {
        accessControlService.requirePermission(
                identity, "product:manage", "product management requires product:manage");
    }

    private ProductCatalogResponse loadProduct(long productId) {
        try {
            return jdbcClient.sql("""
                            SELECT product_id, product_type, product_name, material_spec,
                                   base_price_cents, currency, status, price_note, created_at, updated_at
                            FROM product_catalog
                            WHERE product_id = :productId
                            """)
                    .param("productId", productId)
                    .query(this::mapProduct)
                    .single();
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found", ex);
        }
    }

    private ProductInput normalizeCreate(ProductCatalogRequest request) {
        return new ProductInput(
                normalizeProductType(request.productType()),
                normalizeRequired(request.productName(), "product_name is required"),
                normalizeNullable(request.materialSpec()),
                normalizeBasePrice(request.basePriceCents()),
                normalizeCurrency(request.currency()),
                normalizeStatus(request.status()),
                normalizeNullable(request.priceNote()));
    }

    private ProductInput normalizeUpdate(long productId, UpdateProductCatalogRequest request) {
        String productType = jdbcClient.sql("""
                        SELECT product_type
                        FROM product_catalog
                        WHERE product_id = :productId
                        """)
                .param("productId", productId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found"));
        return new ProductInput(
                productType,
                normalizeRequired(request.productName(), "product_name is required"),
                normalizeNullable(request.materialSpec()),
                normalizeBasePrice(request.basePriceCents()),
                normalizeCurrency(request.currency()),
                normalizeStatus(request.status()),
                normalizeNullable(request.priceNote()));
    }

    private String normalizeProductType(String value) {
        String normalized = normalizeRequired(value, "product_type is required").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "product_type must use A-Z, 0-9 or underscore");
        }
        return normalized;
    }

    private long normalizeBasePrice(Long value) {
        if (value == null || value <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "base_price_cents must be positive");
        }
        return value;
    }

    private String normalizeCurrency(String value) {
        String normalized = value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3,16}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currency must be an uppercase currency code");
        }
        return normalized;
    }

    private String normalizeStatus(String value) {
        String normalized = value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported product status");
        }
        return normalized;
    }

    private String normalizeOptionalStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeStatus(value);
    }

    private String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private JdbcClient.StatementSpec bindListParams(
            JdbcClient.StatementSpec spec, String keyword, String normalizedStatus) {
        spec = spec.param("status", normalizedStatus);
        if (keyword == null || keyword.isBlank()) {
            return spec.param("keyword", null);
        }
        return spec.param("keyword", "%" + keyword.trim() + "%");
    }

    private ProductCatalogResponse mapProduct(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductCatalogResponse(
                rs.getLong("product_id"),
                rs.getString("product_type"),
                rs.getString("product_name"),
                rs.getString("material_spec"),
                rs.getLong("base_price_cents"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("price_note"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private record ProductInput(
            String productType,
            String productName,
            String materialSpec,
            long basePriceCents,
            String currency,
            String status,
            String priceNote) {}
}
