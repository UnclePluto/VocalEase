import { Tag } from 'antd'

const accountLabels: Record<string, { color: string; text: string }> = {
  active: { color: 'success', text: '启用' },
  inactive: { color: 'default', text: '停用' },
}

const treatmentLabels: Record<string, { color: string; text: string }> = {
  pending: { color: 'processing', text: '待开始' },
  active: { color: 'processing', text: '进行中' },
  completed: { color: 'success', text: '已完成' },
  cancelled: { color: 'default', text: '已取消' },
}

export function StatusTag({ scope = 'account', status }: { scope?: 'account' | 'treatment'; status: string }) {
  const labels = scope === 'treatment' ? treatmentLabels : accountLabels
  const item = labels[status] ?? { color: 'default', text: status }
  return <Tag color={item.color}>{item.text}</Tag>
}
