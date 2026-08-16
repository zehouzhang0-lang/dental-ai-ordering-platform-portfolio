export type ProductionProgressNode = {
  node_category?: string | null
  stage_name?: string | null
  process_name?: string | null
  node_status: string
}

const NON_PRODUCTION_CATEGORIES = new Set(['ORDER_INTAKE', 'BILLING'])
const NON_PRODUCTION_PROCESS_NAMES = new Set([
  '客户、客服、销售下单',
  '国外件信息检验、翻译，国内件信息检验',
  '客服定基台',
  '客服核对订单信息及账单'
])
const RESOLVED_PRODUCTION_STATUSES = new Set(['COMPLETED', 'SKIPPED'])

export function isProductionProgressNode(node: ProductionProgressNode) {
  const category = node.node_category?.toUpperCase() ?? ''
  if (NON_PRODUCTION_CATEGORIES.has(category)) return false
  if (NON_PRODUCTION_PROCESS_NAMES.has(node.process_name ?? '')) return false
  return !(node.stage_name === '下单入厂' && category === 'REVIEW')
}

export function productionProgressNodes<T extends ProductionProgressNode>(nodes: readonly T[]) {
  return nodes.filter(isProductionProgressNode)
}

export function productionProgressSummary(nodes: readonly ProductionProgressNode[]) {
  const productionNodes = productionProgressNodes(nodes)
  const completed = productionNodes.filter((node) => RESOLVED_PRODUCTION_STATUSES.has(node.node_status)).length
  return {
    completed,
    total: productionNodes.length,
    percent: productionNodes.length === 0 ? 0 : Math.round(completed / productionNodes.length * 100)
  }
}
