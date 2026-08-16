package com.yuri.aiorder.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.yuri.aiorder.ai.AiGatewayService;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.DataResponse;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "${app.cors.allowed-origin:http://localhost:5173}")
public class AiOrderQueryController {

    private final AiGatewayService aiGatewayService;

    public AiOrderQueryController(AiGatewayService aiGatewayService) {
        this.aiGatewayService = aiGatewayService;
    }

    @PostMapping("/ai/order-query")
    @RequirePermission(value = "ai:doctor", roles = UserRole.DOCTOR)
    public DataResponse<OrderQueryAnswer> orderQuery(
            @Valid @RequestBody OrderQueryRequest request,
            BootstrapIdentity identity) {
        return new DataResponse<>(new OrderQueryAnswer(
                aiGatewayService.orderQuery(request.orderId(), request.question(), identity)));
    }

    public record OrderQueryRequest(
            @JsonProperty("order_id") @NotNull Long orderId,
            @NotBlank String question) {
    }

    public record OrderQueryAnswer(String answer) {
    }
}
