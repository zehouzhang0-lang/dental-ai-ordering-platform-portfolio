package com.yuri.aiorder.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DesignTaskClaimConcurrencyTests {

    private static final long FIRST_WORKER_USER_ID = 1960101L;
    private static final long SECOND_WORKER_USER_ID = 1960102L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    private long clinicId;
    private long orderId;
    private long taskId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String clinicName = "并发抢单测试诊所-" + suffix;
        String orderNo = "RACE" + suffix.substring(0, 12);
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:name)")
                .param("name", clinicName)
                .update();
        clinicId = jdbcClient.sql("""
                        SELECT clinic_id
                        FROM clinic
                        WHERE clinic_name = :clinicName
                        ORDER BY clinic_id DESC
                        LIMIT 1
                        """)
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, product_type, internal_status, external_status)
                        VALUES
                            (:orderNo, :clinicId, 'DESIGN_CLAIM_RACE', 'IN_DESIGN', 'DESIGNING')
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .update();
        orderId = jdbcClient.sql("""
                        SELECT order_id
                        FROM orders
                        WHERE order_no = :orderNo
                        """)
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO design_task (order_id, task_status)
                        VALUES (:orderId, 'OPEN')
                        """)
                .param("orderId", orderId)
                .update();
        taskId = jdbcClient.sql("""
                        SELECT design_task_id
                        FROM design_task
                        WHERE order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    @AfterEach
    void tearDown() {
        if (taskId > 0) {
            jdbcClient.sql("DELETE FROM design_task_event WHERE design_task_id = :taskId")
                    .param("taskId", taskId)
                    .update();
            jdbcClient.sql("DELETE FROM design_task WHERE design_task_id = :taskId")
                    .param("taskId", taskId)
                    .update();
        }
        if (orderId > 0) {
            jdbcClient.sql("DELETE FROM orders WHERE order_id = :orderId")
                    .param("orderId", orderId)
                    .update();
        }
        if (clinicId > 0) {
            jdbcClient.sql("DELETE FROM clinic WHERE clinic_id = :clinicId")
                    .param("clinicId", clinicId)
                    .update();
        }
    }

    @Test
    void twoWorkersRacingForOneTaskProduceExactlyOneWinner() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> claimStatus(
                    FIRST_WORKER_USER_ID, ready, start));
            Future<Integer> second = executor.submit(() -> claimStatus(
                    SECOND_WORKER_USER_ID, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        Long winner = jdbcClient.sql("""
                        SELECT assigned_user_id
                        FROM design_task
                        WHERE design_task_id = :taskId
                        """)
                .param("taskId", taskId)
                .query(Long.class)
                .single();
        assertThat(winner).isIn(FIRST_WORKER_USER_ID, SECOND_WORKER_USER_ID);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM design_task_event
                        WHERE design_task_id = :taskId
                          AND event_type = 'CLAIM'
                        """)
                .param("taskId", taskId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }

    private int claimStatus(
            long workerUserId,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("claim race did not start in time");
        }
        return mockMvc.perform(post("/design-tasks/{taskId}/claim", taskId)
                        .header("X-Bootstrap-Role", "WORKER")
                        .header("X-Bootstrap-User-Id", workerUserId))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
