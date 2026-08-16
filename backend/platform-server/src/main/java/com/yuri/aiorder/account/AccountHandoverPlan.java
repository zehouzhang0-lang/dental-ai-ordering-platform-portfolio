package com.yuri.aiorder.account;

import java.util.List;
import java.util.Set;

/**
 * 账号交接的分类结果：哪些列是「当前负责关系」（要转移），哪些是「历史事实」（绝不能改）。
 *
 * <p>客户原话「把他账号分配给新同事，**并保留之前得服务记录**」。后半句是这一批的难点：
 * 无脑把某个 user_id 全库替换掉，会把已完成工序的执行人、工时的归属人、质检与终检的检验人、
 * 返工的责任人一起改写。那种改写**不报错**——绩效照算，只是算到了别人头上，
 * 等到有人对不上账才发现，而那时已经无法区分哪些是交接改的、哪些本来就是。
 *
 * <p>因此全库所有带用户 ID 的列都逐个判定，结果就是下面两个集合。
 * {@code AccountHandoverClassificationTests} 会扫 {@code information_schema}，
 * 强制**每一个用户 ID 列都出现在其中一个集合里**：以后新增一个列不做分类就过不了测试。
 * 这条守卫比「小心一点」有用得多。
 */
public final class AccountHandoverPlan {

    private AccountHandoverPlan() {
    }

    /**
     * 当前负责关系——交接时转移。
     *
     * <p>判定标准只有一条：这一列回答的是「**现在**归谁负责」，还是「**当时**是谁做的」。
     * 前者转，后者不转。进行中的任务另加状态条件，已完成的不动——已完成的工序即使
     * assigned_user_id 也是历史事实了。
     */
    public static final List<TransferRule> TRANSFER_RULES = List.of(
            new TransferRule(
                    "ORDER_DOCTOR", "订单归属医生", "orders", "doctor_user_id", "order_id",
                    null, Set.of("DOCTOR")),
            new TransferRule(
                    "ORDER_CS", "订单受理客服", "orders", "cs_user_id", "order_id",
                    null, Set.of("CS", "ADMIN")),
            new TransferRule(
                    "PATIENT", "患者档案负责医生", "patient_record", "doctor_user_id", "patient_id",
                    "status = 'ACTIVE'", Set.of("DOCTOR")),
            new TransferRule(
                    "CASE_GROUP", "病例订单组归属医生", "order_case_group", "doctor_user_id", "group_id",
                    null, Set.of("DOCTOR")),
            new TransferRule(
                    "DOCTOR_FILE", "医生上传资料的归属", "file_resource", "owner_user_id", "file_id",
                    "status = 'ACTIVE'", Set.of("DOCTOR")),
            // 已完成的设计任务不转：那时的执行人是历史事实。
            new TransferRule(
                    "DESIGN_TASK", "进行中的设计任务", "design_task", "assigned_user_id", "design_task_id",
                    "task_status NOT IN ('COMPLETED', 'CANCELLED')", Set.of("WORKER", "ADMIN")),
            // 同理，只转还没做完的工序节点。
            new TransferRule(
                    "PROCESS_NODE", "未完成的工序任务", "order_process_node", "assigned_user_id",
                    "node_instance_id", "node_status IN ('PENDING', 'READY')", Set.of("WORKER", "ADMIN")),
            new TransferRule(
                    "REWORK_ROUTE", "未关闭返工的退回对象", "rework_record", "routed_to_user_id", "rework_id",
                    "closed_at IS NULL", Set.of("WORKER", "ADMIN")),
            new TransferRule(
                    "EQUIPMENT", "设备责任人", "production_equipment", "owner_user_id", "equipment_id",
                    null, Set.of("WORKER", "ADMIN")));

    /**
     * 历史事实——**任何情况下都不改**。
     *
     * <p>写成 {@code 表.列} 的形式登记在案，是为了让「我们确实看过这一列并决定不动它」
     * 成为可检查的事实，而不是一句口头承诺。
     */
    public static final Set<String> HISTORICAL_COLUMNS = Set.of(
            // 工时与绩效：改了会直接把绩效算到别人头上
            "work_log.worker_user_id",
            "production_reward_penalty_record.employee_user_id",
            "production_reward_penalty_record.approver_user_id",
            // 质检、终检、返工关闭：责任追溯的落点
            "check_record.checker_user_id",
            "final_inspection_report.inspector_user_id",
            "final_inspection_report.signed_by_user_id",
            "rework_record.closed_by_user_id",
            "quality_record.created_by_user_id",
            "quality_record.status_updated_by_user_id",
            // 订单流转与协同的历史
            "order_status_history.operator_user_id",
            "order_message.sender_user_id",
            "order_message_mention.mentioned_user_id",
            "order_payment_record.created_by_user_id",
            "order_case_group_audit.operator_user_id",
            "order_cancellation_request.requester_user_id",
            "order_cancellation_request.resolved_by_user_id",
            "orders.draft_deleted_by",
            "order_process_confirmation.requested_by_user_id",
            "order_process_confirmation.responded_by_user_id",
            "order_try_in.completed_by_user_id",
            "order_try_in.finalized_by_user_id",
            "message_review_log.reviewer_user_id",
            // 设计稿与设计任务事件
            "design_draft.uploaded_by_user_id",
            "design_draft.internal_reviewer_user_id",
            "design_task_event.actor_user_id",
            "design_task_event.from_assignee_user_id",
            "design_task_event.to_assignee_user_id",
            // 派工与业务门禁事件
            "workflow_assignment_event.actor_user_id",
            "workflow_assignment_event.from_user_id",
            "workflow_assignment_event.to_user_id",
            "workflow_business_gate_audit.actor_user_id",
            "workflow_standard_time_audit.operator_user_id",
            "workflow_standard_time_version.created_by_user_id",
            "workflow_standard_time_version.published_by_user_id",
            // 正畸
            "orthodontic_audit.actor_user_id",
            "orthodontic_case.created_by_user_id",
            "orthodontic_change_request.requested_by_user_id",
            "orthodontic_change_request.reviewed_by_user_id",
            "orthodontic_plan_review.reviewer_user_id",
            "orthodontic_plan_version.created_by_user_id",
            "orthodontic_production_batch.created_by_user_id",
            // 目录与配置的变更留痕
            "catalog_change_audit.operator_user_id",
            "catalog_config_version.created_by_user_id",
            "catalog_config_version.published_by_user_id",
            "product_catalog.created_by_user_id",
            "clinic_product_price.created_by_user_id",
            "clinic_print_template_binding.updated_by_user_id",
            "ordering_rule_config.updated_by_user_id",
            "system_config.updated_by_user_id",
            "ai_faq_entry.created_by_user_id",
            "ai_faq_entry.updated_by_user_id",
            // 客户档案变更
            "clinic_blacklist_record.created_by_user_id",
            "clinic_blacklist_record.released_by_user_id",
            "clinic_change_log.operator_user_id",
            // 生产辅助台账
            "production_equipment_event.requested_by_user_id",
            "production_equipment_event.approved_by_user_id",
            "production_question.asked_by_user_id",
            "production_question.resolved_by_user_id",
            // AI、文件与导出留痕
            "ai_audit_log.actor_user_id",
            "file_access_audit.actor_user_id",
            "export_request.requested_by_user_id",
            "export_request.approved_by_user_id",
            "export_audit.operator_user_id",
            "export_audit.approved_by_user_id",
            // RBAC 与账号自身
            "system_rbac_audit.operator_user_id",
            "system_user.user_id",
            "system_user_role.user_id",
            "system_user_post.user_id",
            "system_user_permission.user_id",
            "auth_refresh_token.user_id",
            "auth_refresh_token.family_id",
            "user_notification.user_id",
            // 交接记录自身
            "account_handover.from_user_id",
            "account_handover.to_user_id",
            "account_handover.operator_user_id");

    /**
     * 一条转移规则。
     *
     * @param objectType     对象类型码，落进 {@code account_handover_item}
     * @param label          中文说明，供界面与留痕展示
     * @param table          目标表
     * @param column         目标列（当前负责关系）
     * @param idColumn       主键列，用于记录转移对象清单
     * @param extraCondition 附加条件；为空表示整表按责任人转移
     * @param portalRoles    适用的入口角色。医生的病例不会转给客服，反之亦然
     */
    public record TransferRule(
            String objectType,
            String label,
            String table,
            String column,
            String idColumn,
            String extraCondition,
            Set<String> portalRoles) {

        public boolean appliesTo(String portalRole) {
            return portalRoles.contains(portalRole);
        }
    }
}
