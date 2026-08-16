package com.yuri.aiorder.workflow.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductionFlowDocumentAlignmentTests {

    private static final String SOURCE_DOCUMENT_SHA256 =
            "1db603cfbb588a5bcd84eab1d63a44febe382813bf0af36fc8dcde9191d4259c";

    private static final Map<String, ChainContract> EXPECTED_CHAINS = Map.ofEntries(
            Map.entry("REGULAR_CROWN", new ChainContract(
                    "常规冠修复", 1, "BOTH", 30, 2,
                    "4ff337dc39d719c426f0d568c09b28666f6bebc694729dd55c3a9b89d4ac4fa6")),
            Map.entry("IMPLANT_RESTORATION", new ChainContract(
                    "种植类修复", 2, "BOTH", 37, 1,
                    "2f02601a92faa211f58d60bf47482220cb2eeaefa78c6cb3eeac2e1110331119")),
            Map.entry("PRECISION_ATTACHMENT", new ChainContract(
                    "精密附件", 2, "NONE", 38, 1,
                    "5756cadb5b43a11bd28a6fb23783814a339f18c2e29af610afa4ae657b30faca")),
            Map.entry("TELESCOPIC_CROWN", new ChainContract(
                    "套筒冠", 1, "IMPRESSION", 46, 1,
                    "8a1e42593ac669d9b4156c56c6492275ed83091b55bda4ea84e6348333e53f7c")),
            Map.entry("VENEER_RESTORATION", new ChainContract(
                    "贴面修复", 2, "BOTH", 42, 1,
                    "f2a6b3b74f13ae410e6e63d5ed8fd90916ac7525e4f977fa73d67274c4a70581")),
            Map.entry("REMOVABLE_STEEL", new ChainContract(
                    "活动件-钢托", 1, "BOTH", 29, 2,
                    "6a1f2c75ebc1064f3932839daba7a4705bd0637d2f9dd2b62827a9a765425d53")),
            Map.entry("REMOVABLE_ACRYLIC", new ChainContract(
                    "活动件-胶托", 1, "BOTH", 21, 1,
                    "f0ac042c7d8366b3a4947256bcd90440fe7aea1ba05d608330a1c9c0970dad6c")),
            Map.entry("REMOVABLE_INVISIBLE", new ChainContract(
                    "活动件-隐形", 1, "BOTH", 22, 1,
                    "f8ea3f7e3f2146b43271a1983af626427e2cb55c6d2b7e5ba790c22adc4ab600")),
            Map.entry("ORTHODONTICS", new ChainContract(
                    "正畸", 1, "BOTH", 18, 0,
                    "5d9128d377b83434ae31af7c83ecab4ffa0ba8ad8dd5b1578d3c51bd52c2ef18")));

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void activeDefinitionsMatchTheNineDocumentContracts() {
        assertThat(SOURCE_DOCUMENT_SHA256).hasSize(64);

        Map<String, ActiveChain> activeChains = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT chain_id, chain_code, chain_name, version, intake_branch
                        FROM workflow_chain
                        WHERE status = 1
                          AND chain_code IN (
                              'REGULAR_CROWN',
                              'IMPLANT_RESTORATION',
                              'PRECISION_ATTACHMENT',
                              'TELESCOPIC_CROWN',
                              'VENEER_RESTORATION',
                              'REMOVABLE_STEEL',
                              'REMOVABLE_ACRYLIC',
                              'REMOVABLE_INVISIBLE',
                              'ORTHODONTICS'
                          )
                        ORDER BY chain_code
                        """)
                .query((rs, rowNum) -> new ActiveChain(
                        rs.getLong("chain_id"),
                        rs.getString("chain_code"),
                        rs.getString("chain_name"),
                        rs.getInt("version"),
                        rs.getString("intake_branch")))
                .list()
                .forEach(chain -> activeChains.put(chain.chainCode(), chain));

        assertThat(activeChains.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_CHAINS.keySet());
        assertThat(activeChains).hasSize(9);

        EXPECTED_CHAINS.forEach((chainCode, expected) -> {
            ActiveChain actual = activeChains.get(chainCode);
            assertThat(actual).as(chainCode + " active chain").isNotNull();
            assertThat(actual.chainName()).isEqualTo(expected.chainName());
            assertThat(actual.version()).isEqualTo(expected.version());
            assertThat(actual.intakeBranch()).isEqualTo(expected.intakeBranch());

            List<NodeContract> nodes = documentNodes(actual.chainId());
            assertThat(nodes).as(chainCode + " document nodes").hasSize(expected.nodeCount());
            assertThat(nodes.stream().filter(node -> node.optional() == 1).count())
                    .as(chainCode + " optional nodes")
                    .isEqualTo(expected.optionalNodeCount());
            assertThat(contractSha256(nodes))
                    .as(chainCode + " source-document contract digest")
                    .isEqualTo(expected.contractSha256());

            long edgeCount = documentEdgeCount(actual.chainId());
            assertThat(edgeCount)
                    .as(chainCode + " normalized sequential edges")
                    .isEqualTo(nodes.size() - 1L);
        });
    }

    @Test
    void implantBranchesJoinAtTheDocumentNodesWithoutCrossRouteLeakage() {
        List<String> preamble = List.of(
                "客户、客服、销售下单",
                "国外件信息检验、翻译，国内件信息检验",
                "入厂检验、数据技术检验",
                "收发入货",
                "收发出货");
        List<String> impression = List.of("印模", "模型入货检验", "模型检验出货");
        List<String> scan = List.of("口扫", "数据审核", "打印模型");
        List<String> joinAndDispatch = List.of("种植入货检", "种植配基台");
        List<String> finishedAbutment = List.of("成品基台", "客服定基台");
        List<String> customAbutment = List.of(
                "个性化基台", "CAD入货检", "CAD设计", "CAD切削基台", "种植研磨基台");
        List<String> commonTail = List.of(
                "种植上部冠设计",
                "CAD排版/切削/染色",
                "CAD烧结",
                "CAD检验出货",
                "车金入货检验",
                "车金出货检验",
                "上瓷入货检验",
                "上瓷出货检验",
                "车瓷入货检验",
                "车瓷形态确认",
                "车瓷出货检验",
                "上釉",
                "抛光",
                "质检出货",
                "等待出货",
                "客服核对订单信息及账单",
                "发货");

        assertThat(selectedProcessNames(
                        "IMPLANT_RESTORATION", "IMPRESSION", "implant_abutment", "FINISHED_ABUTMENT"))
                .containsExactlyElementsOf(concat(
                        preamble, impression, joinAndDispatch, finishedAbutment, commonTail));
        assertThat(selectedProcessNames(
                        "IMPLANT_RESTORATION", "SCAN", "implant_abutment", "CUSTOM_ABUTMENT"))
                .containsExactlyElementsOf(concat(
                        preamble, scan, joinAndDispatch, customAbutment, commonTail));
    }

    @Test
    void veneerBranchesEachOwnOneFinishingTailBeforeTheCommonShipmentTail() {
        List<String> preamble = List.of(
                "客户、客服、销售下单",
                "国外件信息检验、翻译，国内件信息检验",
                "入厂检验、数据技术检验",
                "收发入货");
        List<String> impression = List.of("印模", "模型入货检验", "模型检验出货", "收发出货检验");
        List<String> scan = List.of("口扫", "数据审核", "打印模型");
        List<String> cadMilling = List.of(
                "CAD切削",
                "车金入货检验",
                "车金就位",
                "车金出货检验",
                "上瓷入货检验",
                "上瓷烧结",
                "上瓷出货检验",
                "车瓷入货检验",
                "车瓷",
                "CAD设计",
                "车瓷形态确认",
                "车瓷出货检验",
                "上釉",
                "抛光",
                "质检出货");
        List<String> traditionalWax = List.of(
                "CAD传统切蜡",
                "CAD包埋",
                "CAD铸造",
                "车金就位",
                "上瓷入货检验",
                "上瓷",
                "上瓷出货检验",
                "车瓷入货检验",
                "车瓷",
                "车瓷出货检验",
                "上釉",
                "抛光",
                "质检出货");
        List<String> shipmentTail = List.of("等待出货", "客服核对订单信息及账单", "发货");

        assertThat(selectedProcessNames(
                        "VENEER_RESTORATION", "IMPRESSION", "veneer_route", "CAD_MILLING"))
                .containsExactlyElementsOf(concat(
                        preamble, impression, cadMilling, shipmentTail));
        assertThat(selectedProcessNames(
                        "VENEER_RESTORATION", "SCAN", "veneer_route", "TRADITIONAL_WAX"))
                .containsExactlyElementsOf(concat(
                        preamble, scan, traditionalWax, shipmentTail));
    }

    @Test
    void runtimeSnapshotsDoNotLeakNodesAcrossImplantOrVeneerBranches() throws Exception {
        List<String> finishedImplant = instantiate(
                "IMPLANT_RESTORATION", "IMPRESSION", "implant_abutment", "FINISHED_ABUTMENT");
        assertThat(finishedImplant)
                .containsSubsequence("种植配基台", "成品基台", "客服定基台", "种植上部冠设计")
                .doesNotContain("个性化基台", "CAD切削基台", "种植研磨基台");

        List<String> customImplant = instantiate(
                "IMPLANT_RESTORATION", "SCAN", "implant_abutment", "CUSTOM_ABUTMENT");
        assertThat(customImplant)
                .containsSubsequence(
                        "口扫",
                        "数据审核",
                        "打印模型",
                        "种植入货检",
                        "种植配基台",
                        "个性化基台",
                        "CAD入货检",
                        "CAD设计",
                        "CAD切削基台",
                        "种植研磨基台",
                        "种植上部冠设计")
                .doesNotContain("成品基台", "客服定基台");

        List<String> cadVeneer = instantiate(
                "VENEER_RESTORATION", "IMPRESSION", "veneer_route", "CAD_MILLING");
        assertThat(cadVeneer)
                .contains("CAD切削")
                .doesNotContain("CAD传统切蜡", "CAD包埋", "CAD铸造");
        assertSingleFinishingTail(cadVeneer);

        List<String> traditionalVeneer = instantiate(
                "VENEER_RESTORATION", "SCAN", "veneer_route", "TRADITIONAL_WAX");
        assertThat(traditionalVeneer)
                .contains("CAD传统切蜡", "CAD包埋", "CAD铸造")
                .doesNotContain("CAD切削", "上瓷烧结");
        assertSingleFinishingTail(traditionalVeneer);
    }

    @Test
    void precisionAttachmentInstantiatesWithoutAnInventedIntakeChoice() throws Exception {
        long orderId = createPendingOrder("PRECISION_ATTACHMENT");

        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("""
                                SELECT instance.intake_branch_used
                                FROM order_process_instance instance
                                WHERE instance.order_id = :orderId
                                """)
                        .param("orderId", orderId)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(instanceProcessNames(orderId))
                .containsSubsequence("收发出货", "CAD入货检", "CAD设计", "CAD打印金属");
    }

    private List<NodeContract> documentNodes(long chainId) {
        return jdbcClient.sql("""
                        SELECT
                            node_code,
                            process_name,
                            step_order,
                            is_optional,
                            branch_group,
                            branch_key
                        FROM workflow_node
                        WHERE chain_id = :chainId
                          AND node_category <> 'DESIGN_GATE'
                        ORDER BY step_order, node_id
                        """)
                .param("chainId", chainId)
                .query((rs, rowNum) -> new NodeContract(
                        rs.getString("node_code"),
                        rs.getString("process_name"),
                        rs.getInt("step_order"),
                        rs.getInt("is_optional"),
                        rs.getString("branch_group"),
                        rs.getString("branch_key")))
                .list();
    }

    private long documentEdgeCount(long chainId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM workflow_edge edge
                        JOIN workflow_node source ON source.node_id = edge.from_node_id
                        JOIN workflow_node target ON target.node_id = edge.to_node_id
                        WHERE edge.chain_id = :chainId
                          AND source.node_category <> 'DESIGN_GATE'
                          AND target.node_category <> 'DESIGN_GATE'
                        """)
                .param("chainId", chainId)
                .query(Long.class)
                .single();
    }

    private List<String> selectedProcessNames(
            String chainCode, String intakeBranch, String routeGroup, String routeKey) {
        return jdbcClient.sql("""
                        SELECT node.process_name
                        FROM workflow_chain chain
                        JOIN workflow_node node ON node.chain_id = chain.chain_id
                        WHERE chain.chain_code = :chainCode
                          AND chain.status = 1
                          AND node.node_category <> 'DESIGN_GATE'
                          AND (
                              node.branch_group IS NULL
                              OR (node.branch_group = 'intake' AND node.branch_key = :intakeBranch)
                              OR (node.branch_group = :routeGroup AND node.branch_key = :routeKey)
                          )
                        ORDER BY node.step_order, node.node_id
                        """)
                .param("chainCode", chainCode)
                .param("intakeBranch", intakeBranch)
                .param("routeGroup", routeGroup)
                .param("routeKey", routeKey)
                .query(String.class)
                .list();
    }

    private List<String> instantiate(
            String productType, String intakeBranch, String routeGroup, String routeKey) throws Exception {
        long orderId = createPendingOrder(productType);

        mockMvc.perform(post("/orders/{orderId}/production-review", orderId)
                        .header("X-Bootstrap-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "APPROVE",
                                  "intake_branch": "%s",
                                  "branch_params": {"%s": "%s"}
                                }
                                """.formatted(intakeBranch, routeGroup, routeKey)))
                .andExpect(status().isOk());

        return instanceProcessNames(orderId);
    }

    private long createPendingOrder(String productType) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String clinicName = "生产流程原文契约-" + suffix;
        String orderNo = "PF" + suffix.substring(0, 16);
        jdbcClient.sql("INSERT INTO clinic (clinic_name) VALUES (:clinicName)")
                .param("clinicName", clinicName)
                .update();
        long clinicId = jdbcClient.sql("SELECT clinic_id FROM clinic WHERE clinic_name = :clinicName")
                .param("clinicName", clinicName)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO orders
                            (order_no, clinic_id, doctor_user_id, product_type,
                             internal_status, external_status, branch_params)
                        VALUES
                            (:orderNo, :clinicId, 9501, :productType,
                             'PENDING_PRODUCTION_REVIEW', 'PENDING_REVIEW', JSON_OBJECT())
                        """)
                .param("orderNo", orderNo)
                .param("clinicId", clinicId)
                .param("productType", productType)
                .update();
        long orderId = jdbcClient.sql("SELECT order_id FROM orders WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
        return orderId;
    }

    private List<String> instanceProcessNames(long orderId) {
        return jdbcClient.sql("""
                        SELECT node.process_name
                        FROM order_process_instance instance
                        JOIN order_process_node node ON node.instance_id = instance.instance_id
                        WHERE instance.order_id = :orderId
                          AND node.node_category <> 'DESIGN_GATE'
                        ORDER BY node.step_order, node.node_instance_id
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .list();
    }

    private static void assertSingleFinishingTail(List<String> processNames) {
        assertThat(processNames.stream().filter("上釉"::equals).count()).isOne();
        assertThat(processNames.stream().filter("抛光"::equals).count()).isOne();
        assertThat(processNames.stream().filter("质检出货"::equals).count()).isOne();
        assertThat(processNames)
                .containsSubsequence("上釉", "抛光", "质检出货", "等待出货", "客服核对订单信息及账单", "发货");
    }

    @SafeVarargs
    private static List<String> concat(List<String>... sections) {
        List<String> result = new ArrayList<>();
        for (List<String> section : sections) {
            result.addAll(section);
        }
        return result;
    }

    private static String contractSha256(List<NodeContract> nodes) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            NodeContract node = nodes.get(index);
            if (index > 0) {
                canonical.append('\n');
            }
            canonical.append(node.nodeCode()).append('|')
                    .append(node.processName()).append('|')
                    .append(node.stepOrder()).append('|')
                    .append(node.optional()).append('|')
                    .append(node.branchGroup() == null ? "-" : node.branchGroup()).append('|')
                    .append(node.branchKey() == null ? "-" : node.branchKey());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ActiveChain(
            long chainId,
            String chainCode,
            String chainName,
            int version,
            String intakeBranch) {
    }

    private record ChainContract(
            String chainName,
            int version,
            String intakeBranch,
            int nodeCount,
            int optionalNodeCount,
            String contractSha256) {
    }

    private record NodeContract(
            String nodeCode,
            String processName,
            int stepOrder,
            int optional,
            String branchGroup,
            String branchKey) {
    }
}
