package com.yuri.aiorder.account;

import com.yuri.aiorder.account.AccountHandoverModels.HandoverPreviewResponse;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverRequest;
import com.yuri.aiorder.account.AccountHandoverModels.HandoverResponse;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** TASK-034 D 批次：账号交接与人员转移。属账号安全操作，只开给管理者账号。 */
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class AccountHandoverController {

    private final AccountHandoverService service;

    public AccountHandoverController(AccountHandoverService service) {
        this.service = service;
    }

    /** 执行前先看清楚会转走什么、转多少，以及哪些历史记录不会跟着走。 */
    @GetMapping("/accounts/{userId}/handover-preview")
    @RequirePermission(value = "account:handover", roles = {UserRole.ADMIN})
    public DataResponse<HandoverPreviewResponse> preview(
            @PathVariable long userId,
            @RequestParam("successor_user_id") long successorUserId,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.preview(userId, successorUserId, identity));
    }

    @PostMapping("/accounts/{userId}/handover")
    @RequirePermission(value = "account:handover", roles = {UserRole.ADMIN})
    public DataResponse<HandoverResponse> handover(
            @PathVariable long userId,
            @Valid @RequestBody HandoverRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(service.handover(userId, request, identity));
    }

    @GetMapping("/accounts/handovers")
    @RequirePermission(value = {"account:handover:read", "account:handover"}, roles = {UserRole.ADMIN})
    public DataResponse<List<HandoverResponse>> list(BootstrapIdentity identity) {
        return new DataResponse<>(service.list(identity));
    }

    @GetMapping("/accounts/handovers/{handoverId}")
    @RequirePermission(value = {"account:handover:read", "account:handover"}, roles = {UserRole.ADMIN})
    public DataResponse<HandoverResponse> get(
            @PathVariable long handoverId, BootstrapIdentity identity) {
        return new DataResponse<>(service.load(handoverId, identity));
    }
}
