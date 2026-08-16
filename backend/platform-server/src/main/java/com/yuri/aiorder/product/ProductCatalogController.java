package com.yuri.aiorder.product;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.api.OrderProjectionQueryService.OrderListResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class ProductCatalogController {

    private final ProductCatalogService productCatalogService;

    public ProductCatalogController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping("/products")
    @RequirePermission(value = "product:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<OrderListResponse<ProductCatalogResponse>> listProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            BootstrapIdentity identity) {
        return new DataResponse<>(productCatalogService.listProducts(identity, keyword, status, page, size));
    }

    @GetMapping("/doctor/products")
    @RequirePermission(value = "order:read-doctor", roles = UserRole.DOCTOR)
    public DataResponse<List<DoctorProductCatalogResponse>> listDoctorProducts(BootstrapIdentity identity) {
        return new DataResponse<>(productCatalogService.listDoctorProducts(identity));
    }

    @PostMapping("/products")
    @RequirePermission(value = "product:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ProductCatalogResponse> createProduct(
            @Valid @RequestBody ProductCatalogRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(productCatalogService.createProduct(request, identity));
    }

    @PutMapping("/products/{productId}")
    @RequirePermission(value = "product:manage", roles = {UserRole.CS, UserRole.ADMIN})
    public DataResponse<ProductCatalogResponse> updateProduct(
            @PathVariable long productId,
            @Valid @RequestBody UpdateProductCatalogRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(productCatalogService.updateProduct(productId, request, identity));
    }
}
