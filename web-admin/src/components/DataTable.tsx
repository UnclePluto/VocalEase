import { Alert, Button, Empty, Pagination, Skeleton, Table } from 'antd'
import type { TableColumnsType } from 'antd'

import { ApiError } from '../api/errors'

type PageChange = (page: number, pageSize: number) => void

type DataTableProps<T extends object> = {
  ariaLabel: string
  columns: TableColumnsType<T>
  data?: { count: number; page: number; page_size: number; results: T[] }
  emptyText: string
  error: unknown
  loading: boolean
  onPageChange: PageChange
  onRetry: () => void
  rowKey: keyof T
  scrollX: number
}

export function DataTable<T extends object>({
  ariaLabel,
  columns,
  data,
  emptyText,
  error,
  loading,
  onPageChange,
  onRetry,
  rowKey,
  scrollX,
}: DataTableProps<T>) {
  if (loading) {
    return (
      <div className="data-table-state" role="status" aria-label={ariaLabel}>
        <Skeleton active title={false} paragraph={{ rows: 7 }} />
      </div>
    )
  }

  if (error) {
    const apiError = error instanceof ApiError ? error : null
    return (
      <Alert
        className="data-table-state"
        type="error"
        showIcon
        title={apiError?.message ?? '列表加载失败'}
        description={apiError?.requestId ? `请求编号：${apiError.requestId}` : undefined}
        action={<Button aria-label="重试" size="small" onClick={onRetry}>重试</Button>}
      />
    )
  }

  const page = data?.page ?? 1
  const pageSize = data?.page_size ?? 20
  return (
    <>
      <div className="data-table-scroll" tabIndex={0} aria-label={`${ariaLabel.replace('正在加载', '')}，可横向滚动`}>
        <Table<T>
          columns={columns}
          dataSource={data?.results ?? []}
          rowKey={String(rowKey)}
          pagination={false}
          scroll={{ x: scrollX }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} /> }}
          size="middle"
        />
      </div>
      {(data?.count ?? 0) > 0 ? (
        <div className="data-table-pagination">
          <span>共 {data?.count ?? 0} 条</span>
          <Pagination
            current={page}
            pageSize={pageSize}
            total={data?.count ?? 0}
            pageSizeOptions={[10, 20, 50, 100]}
            showSizeChanger={{ id: `data-table-${String(rowKey)}-page-size` }}
            onChange={onPageChange}
          />
        </div>
      ) : null}
    </>
  )
}
