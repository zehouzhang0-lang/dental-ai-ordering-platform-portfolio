package com.yuri.aiorder.notification;

import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import java.util.List;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    @RequirePermission(value = "notification:read-self", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<List<NotificationResponse>> listNotifications(
            @RequestParam(name = "unread_only", defaultValue = "false") boolean unreadOnly,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            BootstrapIdentity identity) {
        return new DataResponse<>(notificationService.listNotifications(identity, unreadOnly, limit));
    }

    @GetMapping("/notifications/unread-count")
    @RequirePermission(value = "notification:read-self", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<UnreadCountResponse> unreadCount(BootstrapIdentity identity) {
        return new DataResponse<>(notificationService.unreadCount(identity));
    }

    @PostMapping("/notifications/{notificationId}/read")
    @RequirePermission(value = "notification:write-self", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<NotificationResponse> markRead(
            @PathVariable long notificationId,
            BootstrapIdentity identity) {
        return new DataResponse<>(notificationService.markRead(identity, notificationId));
    }

    @PostMapping("/notifications/read-all")
    @RequirePermission(value = "notification:write-self", roles = {UserRole.ADMIN, UserRole.CS, UserRole.WORKER, UserRole.DOCTOR})
    public DataResponse<MarkAllReadResponse> markAllRead(BootstrapIdentity identity) {
        return new DataResponse<>(notificationService.markAllRead(identity));
    }
}
