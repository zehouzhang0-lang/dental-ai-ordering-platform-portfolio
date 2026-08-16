package com.yuri.aiorder.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CatalogVersionResponse;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAccessoryBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAccessoryRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateAliasRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCatalogRuleRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCatalogVersionRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateCategoryRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialColorRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateMaterialRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateProductRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.CreateVariantRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.MaterialResponse;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.PublishCatalogRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateCatalogRuleRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateAccessoryBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateAliasRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateMaterialBindingRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateMaterialRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateNamedCatalogEntityRequest;
import com.yuri.aiorder.catalog.CatalogConfigurationModels.UpdateProductRequest;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class CatalogConfigurationController {

    private final CatalogConfigurationService service;
    private final CatalogExtendedManagementService extendedService;

    public CatalogConfigurationController(
            CatalogConfigurationService service,
            CatalogExtendedManagementService extendedService) {
        this.service = service;
        this.extendedService = extendedService;
    }

    @GetMapping("/admin/catalog/versions")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<List<CatalogVersionResponse>> listVersions(BootstrapIdentity identity) {
        return new DataResponse<>(service.listVersions(identity));
    }

    @PostMapping("/admin/catalog/versions")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<CatalogVersionResponse> createVersion(
            @Valid @RequestBody CreateCatalogVersionRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createVersion(request, identity));
    }

    @GetMapping("/admin/catalog/import-template")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> importTemplate(BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.importTemplate(identity));
    }

    @PostMapping("/admin/catalog/import-validation")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> validateImport(
            @RequestBody JsonNode request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.validateImport(request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/categories")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createCategory(
            @PathVariable long versionId,
            @Valid @RequestBody CreateCategoryRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createCategory(versionId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/products")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createProduct(
            @PathVariable long versionId,
            @Valid @RequestBody CreateProductRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createProduct(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/products/{productId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateProduct(
            @PathVariable long productId,
            @Valid @RequestBody UpdateProductRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.updateProduct(productId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/variants")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createVariant(
            @PathVariable long versionId,
            @Valid @RequestBody CreateVariantRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createVariant(versionId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/accessories")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createAccessory(
            @PathVariable long versionId,
            @Valid @RequestBody CreateAccessoryRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createAccessory(versionId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/material-colors")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createMaterialColor(
            @PathVariable long versionId,
            @Valid @RequestBody CreateMaterialColorRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createMaterialColor(versionId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/accessory-bindings")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> bindAccessory(
            @PathVariable long versionId,
            @Valid @RequestBody CreateAccessoryBindingRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.bindAccessory(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/accessory-bindings/{bindingId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateAccessoryBinding(
            @PathVariable long bindingId,
            @Valid @RequestBody UpdateAccessoryBindingRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(
                extendedService.updateAccessoryBinding(bindingId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/aliases")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createAlias(
            @PathVariable long versionId,
            @Valid @RequestBody CreateAliasRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createAlias(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/aliases/{aliasId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateAlias(
            @PathVariable long aliasId,
            @Valid @RequestBody UpdateAliasRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.updateAlias(aliasId, request, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/rules")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> createRule(
            @PathVariable long versionId,
            @Valid @RequestBody CreateCatalogRuleRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.createRule(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/rules/{ruleId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateRule(
            @PathVariable long ruleId,
            @Valid @RequestBody UpdateCatalogRuleRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(extendedService.updateRule(ruleId, request, identity));
    }

    @PutMapping("/admin/catalog/entities/{entityType}/{entityId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateNamedEntity(
            @PathVariable String entityType,
            @PathVariable long entityId,
            @Valid @RequestBody UpdateNamedCatalogEntityRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(
                extendedService.updateNamedEntity(entityType, entityId, request, identity));
    }

    @DeleteMapping("/admin/catalog/entities/{entityType}/{entityId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> deleteDraftEntity(
            @PathVariable String entityType,
            @PathVariable long entityId,
            BootstrapIdentity identity) {
        extendedService.deleteDraftEntity(entityType, entityId, identity);
        return new DataResponse<>(Map.of(
                "entity_type", entityType.toUpperCase(),
                "entity_id", entityId,
                "deleted", true));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/materials")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<MaterialResponse> createMaterial(
            @PathVariable long versionId,
            @Valid @RequestBody CreateMaterialRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createMaterial(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/materials/{materialId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<MaterialResponse> updateMaterial(
            @PathVariable long materialId,
            @Valid @RequestBody UpdateMaterialRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.updateMaterial(materialId, request, identity));
    }

    @DeleteMapping("/admin/catalog/materials/{materialId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> deleteMaterial(
            @PathVariable long materialId,
            BootstrapIdentity identity) {
        service.deleteMaterial(materialId, identity);
        return new DataResponse<>(Map.of("material_id", materialId, "deleted", true));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/material-bindings")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> bindMaterial(
            @PathVariable long versionId,
            @Valid @RequestBody CreateMaterialBindingRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.bindMaterial(versionId, request, identity));
    }

    @PutMapping("/admin/catalog/material-bindings/{bindingId}")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> updateMaterialBinding(
            @PathVariable long bindingId,
            @Valid @RequestBody UpdateMaterialBindingRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(
                extendedService.updateMaterialBinding(bindingId, request, identity));
    }

    @GetMapping("/admin/catalog/versions/{versionId}/preview")
    @RequirePermission(value = "catalog:manage", roles = {UserRole.ADMIN})
    public DataResponse<Map<String, Object>> preview(
            @PathVariable long versionId,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.preview(versionId, identity));
    }

    @PostMapping("/admin/catalog/versions/{versionId}/publish")
    @RequirePermission(value = "catalog:publish", roles = {UserRole.ADMIN})
    public DataResponse<CatalogVersionResponse> publish(
            @PathVariable long versionId,
            @Valid @RequestBody PublishCatalogRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.publish(versionId, request, identity));
    }

    @GetMapping("/catalog/configuration/active")
    @RequirePermission(value = {"order:read-doctor", "order:read-internal"}, roles = {
            UserRole.DOCTOR, UserRole.CS, UserRole.ADMIN})
    public DataResponse<Map<String, Object>> active(BootstrapIdentity identity) {
        return new DataResponse<>(service.activeDoctorConfiguration(identity));
    }
}
