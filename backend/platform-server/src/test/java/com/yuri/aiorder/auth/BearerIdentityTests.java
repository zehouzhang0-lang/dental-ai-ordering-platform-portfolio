package com.yuri.aiorder.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuri.aiorder.common.BootstrapIdentity;
import com.yuri.aiorder.common.UserRole;
import com.yuri.aiorder.common.auth.BearerTokenService;
import com.yuri.aiorder.common.auth.RefreshTokenService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "app.cors.allowed-origin=http://localhost:5173,http://127.0.0.1:5173,http://phase-one.example.test:8088"
})
@AutoConfigureMockMvc
class BearerIdentityTests {

    private static final long DOCTOR_USER_ID = 9701L;
    private static final long OTHER_DOCTOR_USER_ID = 9702L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BearerTokenService tokenService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private long clinicId;
    private long orderId;

    @BeforeEach
    void setUp() {
        BootstrapIdentity.setBootstrapHeadersAllowed(true);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", "Bearer测试诊所-" + suffix)
                .update();
        clinicId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();

        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, cs_user_id, product_type,
                             form_data, internal_status, external_status, production_note)
                        VALUES
                            (:orderNo, :clinicId, :doctorUserId, 8001, 'REGULAR_CROWN',
                             JSON_OBJECT('patient_name', '赵六', 'tooth_position', '16'),
                             'IN_PRODUCTION', 'PRODUCING', 'Bearer内部生产备注')
                        """)
                .param("orderNo", "B" + suffix.substring(0, 12))
                .param("clinicId", clinicId)
                .param("doctorUserId", DOCTOR_USER_ID)
                .update();
        orderId = jdbcClient.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO order_external_projection
                            (order_id, external_status, public_message)
                        VALUES
                            (:orderId, 'PRODUCING', '订单正在制作中。')
                        """)
                .param("orderId", orderId)
                .update();
    }

    @AfterEach
    void tearDown() {
        BootstrapIdentity.setBootstrapHeadersAllowed(true);
    }

    @Test
    void bearerDoctorTokenUsesDoctorDataScopeAndDesensitizedProjection() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, DOCTOR_USER_ID, clinicId));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.external_status").value("PRODUCING"))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(jsonPath("$.data.production_note").doesNotExist())
                .andExpect(content().string(not(containsString("Bearer内部生产备注"))));
    }

    @Test
    void bearerDoctorTokenRejectsOtherDoctorOrder() throws Exception {
        String token = tokenService.issue(new BootstrapIdentity(UserRole.DOCTOR, OTHER_DOCTOR_USER_ID, 998877L));

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledBootstrapHeadersRequireBearerToken() throws Exception {
        BootstrapIdentity.setBootstrapHeadersAllowed(false);

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("X-Bootstrap-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseLoginReturnsRbacIdentityAndMeReadsBearerClaims() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-admin\",\"portal\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.userId").value("8001"))
                .andExpect(jsonPath("$.roles", hasItem("ADMIN")))
                .andExpect(jsonPath("$.permissions", hasItem("workflow:assign")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("system-rbac")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("internal-orders")))
                .andExpect(jsonPath("$.dataScope").value("ALL"))
                .andReturn();

        JsonNode root = objectMapper.readTree(login.getResponse().getContentAsString());
        String token = root.path("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.userId").value("8001"))
                .andExpect(jsonPath("$.roles", hasItem("ADMIN")))
                .andExpect(jsonPath("$.permissions", hasItem("workflow:assign")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("system-rbac")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("internal-orders")))
                .andExpect(jsonPath("$.dataScope").value("ALL"));
    }

    @Test
    void databaseBearerReloadsDirectUserPermissionsWithoutRelogin() throws Exception {
        jdbcClient.sql("""
                        DELETE FROM system_user_permission
                        WHERE user_id = 9601
                          AND permission_id = (
                              SELECT permission_id
                              FROM system_permission
                              WHERE permission_code = 'design-draft:internal-review'
                          )
                        """)
                .update();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"worker\",\"password\":\"change-me-worker\",\"portal\":\"PRODUCTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", not(hasItem("design-draft:internal-review"))))
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken")
                .asText();

        try {
            jdbcClient.sql("""
                            INSERT INTO system_user_permission (user_id, permission_id)
                            SELECT 9601, permission_id
                            FROM system_permission
                            WHERE permission_code = 'design-draft:internal-review'
                            """)
                    .update();

            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions", hasItem("design-draft:internal-review")));
        } finally {
            jdbcClient.sql("""
                            DELETE FROM system_user_permission
                            WHERE user_id = 9601
                              AND permission_id = (
                                  SELECT permission_id
                                  FROM system_permission
                                  WHERE permission_code = 'design-draft:internal-review'
                              )
                            """)
                    .update();
        }
    }

    @Test
    void productionReviewMenuRequiresDirectWorkerPermissionAndReloadsWithoutRelogin() throws Exception {
        jdbcClient.sql("""
                        DELETE FROM system_user_permission
                        WHERE user_id = 9601
                          AND permission_id = (
                              SELECT permission_id
                              FROM system_permission
                              WHERE permission_code = 'workflow:review-production'
                          )
                        """)
                .update();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"worker\",\"password\":\"change-me-worker\",\"portal\":\"PRODUCTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", not(hasItem("workflow:review-production"))))
                .andExpect(jsonPath("$.menus[*].menuCode", not(hasItem("production-review"))))
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken")
                .asText();

        try {
            jdbcClient.sql("""
                            INSERT INTO system_user_permission (user_id, permission_id)
                            SELECT 9601, permission_id
                            FROM system_permission
                            WHERE permission_code = 'workflow:review-production'
                            """)
                    .update();

            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.permissions", hasItem("workflow:review-production")))
                    .andExpect(jsonPath("$.menus[*].menuCode", hasItem("production-review")));
        } finally {
            jdbcClient.sql("""
                            DELETE FROM system_user_permission
                            WHERE user_id = 9601
                              AND permission_id = (
                                  SELECT permission_id
                                  FROM system_permission
                                  WHERE permission_code = 'workflow:review-production'
                              )
                            """)
                    .update();
        }
    }

    @Test
    void databaseLoginAllowsLocalhostAndLoopbackViteOrigins() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", "http://127.0.0.1:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void databaseLoginAllowsConfiguredDeploymentOriginAndRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", "http://phase-one.example.test:8088")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://phase-one.example.test:8088"));

        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", "http://untrusted.example.test:8088")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void databaseDoctorLoginUsesUserDataScopeForDoctorOrder() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("doctor"))
                .andExpect(jsonPath("$.userId").value(Long.toString(DOCTOR_USER_ID)))
                .andExpect(jsonPath("$.roles", hasItem("DOCTOR")))
                .andExpect(jsonPath("$.permissions", hasItem("order:read-doctor")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("doctor-orders")))
                .andExpect(jsonPath("$.menus[*].menuCode", hasItem("ai-doctor")))
                .andExpect(jsonPath("$.menus[*].menuCode", not(hasItem("internal-orders"))))
                .andExpect(jsonPath("$.dataScope").value("CLINIC"))
                .andReturn();

        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("accessToken")
                .asText();

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist())
                .andExpect(content().string(not(containsString("Bearer内部生产备注"))));
    }

    @Test
    void databaseLoginRejectsBadPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\",\"portal\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void databaseLoginRequiresPortalAndMatchesRoleToPortal() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-admin\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"worker\",\"password\":\"change-me-worker\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("DOCTOR")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cs\",\"password\":\"change-me-cs\",\"portal\":\"CS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("CS")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"worker\",\"password\":\"change-me-worker\",\"portal\":\"PRODUCTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("WORKER")));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"change-me-admin\",\"portal\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("ADMIN")));
    }

    @Test
    void refreshTokenCanIssueNewAccessTokenAndLogoutRevokesIt() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.refreshExpiresAt").isString())
                .andReturn();

        JsonNode loginRoot = objectMapper.readTree(login.getResponse().getContentAsString());
        String refreshToken = loginRoot.path("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.username").value("doctor"))
                .andExpect(jsonPath("$.roles", hasItem("DOCTOR")))
                .andReturn();

        JsonNode refreshedRoot = objectMapper.readTree(refreshed.getResponse().getContentAsString());
        String refreshedAccessToken = refreshedRoot.path("accessToken").asText();
        String rotatedRefreshToken = refreshedRoot.path("refreshToken").asText();
        assertNotEquals(refreshToken, rotatedRefreshToken);

        mockMvc.perform(get("/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + refreshedAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order_id").value(orderId))
                .andExpect(jsonPath("$.data.internal_status").doesNotExist());

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + rotatedRefreshToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + rotatedRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenRotatesAndRejectsOldTokenReuse() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"doctor\",\"password\":\"change-me-doctor\",\"portal\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.refreshExpiresAt").isString())
                .andReturn();

        String originalRefreshToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("refreshToken")
                .asText();

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + originalRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.username").value("doctor"))
                .andExpect(jsonPath("$.roles", hasItem("DOCTOR")))
                .andReturn();

        String rotatedRefreshToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .path("refreshToken")
                .asText();
        assertNotEquals(originalRefreshToken, rotatedRefreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + originalRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult secondRefresh = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + rotatedRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();

        String secondRotatedRefreshToken = objectMapper.readTree(secondRefresh.getResponse().getContentAsString())
                .path("refreshToken")
                .asText();
        assertNotEquals(rotatedRefreshToken, secondRotatedRefreshToken);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + secondRotatedRefreshToken + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + secondRotatedRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshRotationIssuesExactlyOneReplacementToken() throws Exception {
        String originalToken = refreshTokenService.issue(DOCTOR_USER_ID).token();
        int requestCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefreshTokenService.IssuedRefreshToken>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return refreshTokenService.rotate(originalToken);
                    } catch (ResponseStatusException ex) {
                        if (ex.getStatusCode().value() != 401) {
                            throw ex;
                        }
                        return null;
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<RefreshTokenService.IssuedRefreshToken> replacements = new ArrayList<>();
            for (Future<RefreshTokenService.IssuedRefreshToken> future : futures) {
                RefreshTokenService.IssuedRefreshToken replacement = future.get(10, TimeUnit.SECONDS);
                if (replacement != null) {
                    replacements.add(replacement);
                }
            }
            org.assertj.core.api.Assertions.assertThat(replacements).hasSize(1);
            refreshTokenService.revoke(replacements.get(0).token());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void logoutWithOldTokenAfterCommittedRotationRevokesEntireTokenFamily() throws Exception {
        String originalToken = refreshTokenService.issue(DOCTOR_USER_ID).token();
        RefreshTokenService.IssuedRefreshToken replacement = refreshTokenService.rotate(originalToken);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + originalToken + "\"}"))
                .andExpect(status().isOk());

        String familyId = jdbcClient.sql("""
                        SELECT family_id
                        FROM auth_refresh_token
                        WHERE user_id = :userId
                        ORDER BY token_id DESC
                        LIMIT 1
                        """)
                .param("userId", DOCTOR_USER_ID)
                .query(String.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM auth_refresh_token
                        WHERE family_id = :familyId
                          AND revoked_at IS NULL
                        """)
                .param("familyId", familyId)
                .query(Long.class)
                .single()).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshTokenService.rotate(replacement.token()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> org.assertj.core.api.Assertions.assertThat(ex.getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void concurrentOldTokenLogoutWaitsForRotationAndRevokesTheReplacement() throws Exception {
        String originalToken = refreshTokenService.issue(DOCTOR_USER_ID).token();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection();
                Connection observer = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement("""
                    SELECT user_id
                    FROM system_user
                    WHERE user_id = ?
                    FOR UPDATE
                    """)) {
                statement.setLong(1, DOCTOR_USER_ID);
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    org.assertj.core.api.Assertions.assertThat(resultSet.next()).isTrue();
                }
            }

            // InnoDB must check the refresh-token user FK before inserting the replacement.
            // Holding the parent row pauses rotate after it has conditionally revoked the old
            // token, leaving the exact race window in which logout used to miss the replacement.
            observer.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            Future<RefreshTokenService.IssuedRefreshToken> replacementFuture = executor.submit(() -> {
                return refreshTokenService.rotate(originalToken);
            });
            awaitUncommittedRevocation(observer, originalToken);
            org.assertj.core.api.Assertions.assertThat(replacementFuture.isDone()).isFalse();

            Future<Integer> logoutFuture = executor.submit(() -> mockMvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refresh_token\":\"" + originalToken + "\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus());
            Thread.sleep(200);
            org.assertj.core.api.Assertions.assertThat(logoutFuture.isDone()).isFalse();

            blocker.commit();
            RefreshTokenService.IssuedRefreshToken replacement = replacementFuture.get(10, TimeUnit.SECONDS);
            org.assertj.core.api.Assertions.assertThat(logoutFuture.get(10, TimeUnit.SECONDS)).isEqualTo(200);

            String familyId = jdbcClient.sql("""
                            SELECT family_id
                            FROM auth_refresh_token
                            WHERE token_hash = SHA2(:replacementToken, 256)
                            """)
                    .param("replacementToken", replacement.token())
                    .query(String.class)
                    .single();
            org.assertj.core.api.Assertions.assertThat(jdbcClient.sql("""
                            SELECT COUNT(*)
                            FROM auth_refresh_token
                            WHERE family_id = :familyId
                              AND revoked_at IS NULL
                            """)
                    .param("familyId", familyId)
                    .query(Long.class)
                    .single()).isZero();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> refreshTokenService.rotate(replacement.token()))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> org.assertj.core.api.Assertions.assertThat(ex.getStatusCode().value()).isEqualTo(401));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void awaitUncommittedRevocation(Connection observer, String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (PreparedStatement statement = observer.prepareStatement("""
                    SELECT revoked_at
                    FROM auth_refresh_token
                    WHERE token_hash = SHA2(?, 256)
                    """)) {
                statement.setString(1, token);
                try (java.sql.ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getTimestamp(1) != null) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("rotation did not reach the post-revoke insertion window");
    }
}
