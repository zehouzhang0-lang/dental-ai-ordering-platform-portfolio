package com.yuri.aiorder.common.auth;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 授权判定统一入口。
 *
 * <p><b>{@code identity.role()} 是「入口角色 / Portal」，不是业务角色。</b>
 * 它只有 {@code DOCTOR / CS / WORKER / ADMIN} 四个值，含义是「从哪个端登录」，
 * 用来决定登录入口匹配和数据范围默认值。客户确认的约 20 个细分角色（客服经理、组长、终检员……）
 * 一律表达为 {@code system_role} 记录 + 权限码集合 + 数据范围，不进入这个枚举。
 * 因此**不要用 {@code identity.role()} 做业务权限判定**——新增细分角色时那种判定不会生效。
 *
 * <p>本类中所有授权判定都基于权限码。保留角色语义的只剩两处，且都不是业务权限：
 * <ul>
 *   <li>{@link #requireDoctorOrderScope} / {@link #doctorCanAccessOrder}：医生端数据归属判定；</li>
 *   <li>{@link #effectiveDataScope} 的兜底：身份未携带数据范围时按入口角色给默认值。</li>
 * </ul>
 */
@Service
public class AccessControlService {

    public void requirePermission(BootstrapIdentity identity, String permissionCode, String message) {
        if (!identity.hasPermission(permissionCode)) {
            throw forbidden(message);
        }
    }

    /** 任一权限码命中即可。用于同一能力在不同端各有一个权限码的场景，例如 AI 的 doctor / cs / production。 */
    public void requireAnyPermission(BootstrapIdentity identity, String message, String... permissionCodes) {
        if (!hasAnyPermission(identity, permissionCodes)) {
            throw forbidden(message);
        }
    }

    public boolean hasAnyPermission(BootstrapIdentity identity, String... permissionCodes) {
        return Arrays.stream(permissionCodes).anyMatch(identity::hasPermission);
    }

    /**
     * 医生端专属业务动作。权限码只授予医生端角色，管理端入口不再持有医生端专属码，
     * 因此这里不需要再额外判断入口角色。
     */
    public void requireDoctorPortalAction(BootstrapIdentity identity, String permissionCode, String message) {
        requirePermission(identity, permissionCode, message);
    }

    public void requireInternalAccess(BootstrapIdentity identity, String message) {
        requirePermission(identity, "workflow:read-internal", message);
    }

    public void requireProductionReview(BootstrapIdentity identity) {
        requirePermission(identity, "workflow:review-production", "production review requires workflow:review-production");
    }

    public boolean canReviewProduction(BootstrapIdentity identity) {
        return identity.hasPermission("workflow:review-production");
    }

    public void requireProcessManagement(BootstrapIdentity identity) {
        requirePermission(identity, "workflow:assign", "process assignment requires workflow:assign");
    }

    public void requireCheckRecordRead(BootstrapIdentity identity) {
        requirePermission(identity, "check:read-internal", "check records are internal only");
    }

    public void requireDoctorOrderScope(BootstrapIdentity identity, Long doctorUserId, Long clinicId, String message) {
        if (identity.role() != UserRole.DOCTOR) {
            return;
        }
        if (!doctorCanAccessOrder(identity, doctorUserId, clinicId)) {
            throw forbidden(message);
        }
    }

    public boolean doctorCanAccessOrder(BootstrapIdentity identity, Long doctorUserId, Long clinicId) {
        return (doctorUserId != null && Objects.equals(identity.userId(), doctorUserId))
                || (clinicId != null && Objects.equals(identity.clinicId(), clinicId));
    }

    /**
     * 数据范围：身份携带的值优先（来自用户级覆盖或角色级配置），未携带时按入口角色给默认值。
     * 真正的解析逻辑在 {@link DatabaseAuthService#authenticate}，这里只是兜底。
     */
    public String effectiveDataScope(BootstrapIdentity identity) {
        if (identity.dataScope() != null && !identity.dataScope().isBlank()) {
            return identity.dataScope().trim().toUpperCase(Locale.ROOT);
        }
        return switch (identity.role()) {
            case ADMIN, CS -> "ALL";
            case DOCTOR -> "CLINIC";
            case WORKER -> "SELF";
        };
    }

    public void requireScopedIdentity(BootstrapIdentity identity, String dataScope) {
        if ("ALL".equals(dataScope)) {
            return;
        }
        if (identity.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user id is required for scoped access");
        }
        if ("CLINIC".equals(dataScope) && identity.clinicId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clinic id is required for clinic data scope");
        }
    }

    /**
     * 工序 / 工时的操作归属判定：持有派工权限的可以代操作，否则只能操作分配给本人的对象。
     * 这里的 {@code workflow:assign} 替代了原先写死的「ADMIN 直接放行」。
     */
    public void requireAssignedWorkerOrAdmin(BootstrapIdentity identity, Long assignedUserId, String message) {
        requireAssignedWorker(identity, assignedUserId, message, true);
    }

    /**
     * 开工 / 暂停 / 完工等「代操作生产」动作。客户否决了「管理端一律不能代操作」的建议但未写允许到什么程度，
     * 因此是否放行派工权限持有者由配置开关 {@code role.admin.can-operate-production} 决定，默认关闭。
     */
    public void requireProductionOperator(
            BootstrapIdentity identity, Long assignedUserId, String message, boolean allowDelegation) {
        requireAssignedWorker(identity, assignedUserId, message, allowDelegation);
    }

    private void requireAssignedWorker(
            BootstrapIdentity identity, Long assignedUserId, String message, boolean allowDelegation) {
        if (allowDelegation && identity.hasPermission("workflow:assign")) {
            return;
        }
        if (!identity.hasPermission("workflow:operate-assigned")
                || identity.userId() == null
                || assignedUserId == null
                || !identity.userId().equals(assignedUserId)) {
            throw forbidden(message);
        }
    }

    /**
     * 入检 / 出检的检查人是组长；质检员只做过程抽检。两者用不同权限码区分，新增角色时只配权限码即可。
     */
    public void requireGateInspection(BootstrapIdentity identity) {
        requirePermission(identity, "check:gate-inspect", "in/out check requires check:gate-inspect");
    }

    public void requireSampleInspection(BootstrapIdentity identity) {
        requireAnyPermission(
                identity,
                "sample check requires check:sample-inspect or check:gate-inspect",
                "check:sample-inspect",
                "check:gate-inspect");
    }

    public Long resolvePerformanceTargetUserId(BootstrapIdentity identity, Long requestedUserId) {
        if (identity.hasPermission("performance:read-all")) {
            if (requestedUserId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is required");
            }
            return requestedUserId;
        }
        if (identity.hasPermission("performance:read-self")) {
            if (identity.userId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "worker user id is required");
            }
            return identity.userId();
        }
        throw forbidden("performance requires performance:read-self or performance:read-all");
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
