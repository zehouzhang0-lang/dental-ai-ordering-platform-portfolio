package com.yuri.aiorder.order.casegroup;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.BindSharedFilesRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.CopyGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.CreateGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.DeleteGroupItemRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.SubmitCaseGroupRequest;
import com.yuri.aiorder.order.casegroup.CaseGroupItemModels.UpdateGroupItemRequest;
import jakarta.validation.Valid;
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
public class CaseGroupController {

    private final CaseGroupService service;
    private final CaseGroupDraftService draftService;

    public CaseGroupController(
            CaseGroupService service,
            CaseGroupDraftService draftService) {
        this.service = service;
        this.draftService = draftService;
    }

    @PostMapping("/order-case-groups")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> createDraft(
            @Valid @RequestBody CreateCaseGroupRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.createDraft(request, identity));
    }

    @GetMapping("/order-case-groups/{groupId}")
    @RequirePermission(value = {"order:read-internal", "order:read-doctor"}, roles = {
            UserRole.ADMIN, UserRole.CS, UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> get(
            @PathVariable long groupId,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.get(groupId, identity));
    }

    @PostMapping("/order-case-groups/{groupId}/items")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> addItem(
            @PathVariable long groupId,
            @Valid @RequestBody CreateGroupItemRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.addItem(groupId, request, identity));
    }

    @PutMapping("/order-case-groups/{groupId}/items/{orderId}")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> updateItem(
            @PathVariable long groupId,
            @PathVariable long orderId,
            @Valid @RequestBody UpdateGroupItemRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.updateItem(groupId, orderId, request, identity));
    }

    @PostMapping("/order-case-groups/{groupId}/items/{orderId}/copy")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> copyItem(
            @PathVariable long groupId,
            @PathVariable long orderId,
            @Valid @RequestBody CopyGroupItemRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.copyItem(groupId, orderId, request, identity));
    }

    @DeleteMapping("/order-case-groups/{groupId}/items/{orderId}")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> deleteItem(
            @PathVariable long groupId,
            @PathVariable long orderId,
            @Valid @RequestBody DeleteGroupItemRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.deleteItem(groupId, orderId, request, identity));
    }

    @PutMapping("/order-case-groups/{groupId}/shared-files")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> bindSharedFiles(
            @PathVariable long groupId,
            @Valid @RequestBody BindSharedFilesRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.bindSharedFiles(groupId, request, identity));
    }

    @PostMapping("/order-case-groups/{groupId}/submit")
    @RequirePermission(value = "order:write-doctor", roles = {UserRole.DOCTOR})
    public DataResponse<CaseGroupResponse> submit(
            @PathVariable long groupId,
            @Valid @RequestBody SubmitCaseGroupRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(draftService.submit(groupId, request, identity));
    }
}
