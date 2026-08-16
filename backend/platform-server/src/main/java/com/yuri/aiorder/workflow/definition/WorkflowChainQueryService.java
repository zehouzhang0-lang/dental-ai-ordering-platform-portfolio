package com.yuri.aiorder.workflow.definition;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class WorkflowChainQueryService {

    private final JdbcClient jdbcClient;

    public WorkflowChainQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<WorkflowChainSummary> listChains() {
        return jdbcClient.sql("""
                        SELECT chain_id, chain_name, product_type, intake_branch, status
                        FROM workflow_chain
                        WHERE status = 1
                        ORDER BY chain_id
                        """)
                .query((rs, rowNum) -> new WorkflowChainSummary(
                        rs.getLong("chain_id"),
                        rs.getString("chain_name"),
                        rs.getString("product_type"),
                        rs.getString("intake_branch"),
                        rs.getInt("status")))
                .list();
    }

    public List<WorkflowNodeSummary> listNodes(long chainId) {
        return jdbcClient.sql("""
                        SELECT node_id, process_name, step_order, is_optional, branch_group
                        FROM workflow_node
                        WHERE chain_id = :chainId
                        ORDER BY step_order, node_id
                        """)
                .param("chainId", chainId)
                .query((rs, rowNum) -> new WorkflowNodeSummary(
                        rs.getLong("node_id"),
                        rs.getString("process_name"),
                        rs.getInt("step_order"),
                        rs.getInt("is_optional"),
                        rs.getString("branch_group")))
                .list();
    }
}
