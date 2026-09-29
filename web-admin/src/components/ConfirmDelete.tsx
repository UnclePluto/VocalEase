import { Alert, Modal } from 'antd'

import { ApiError } from '../api/errors'

type ConfirmDeleteProps = {
  content: string
  error?: unknown
  loading: boolean
  onCancel: () => void
  onConfirm: () => void
  open: boolean
  title: string
}

export function ConfirmDelete({ content, error, loading, onCancel, onConfirm, open, title }: ConfirmDeleteProps) {
  const apiError = error instanceof ApiError ? error : null
  return (
    <Modal
      title={title}
      width={420}
      open={open}
      onCancel={onCancel}
      onOk={onConfirm}
      okText="确认删除"
      cancelText="取消"
      okButtonProps={{ danger: true, loading, disabled: loading, 'aria-label': '确认删除' }}
      cancelButtonProps={{ disabled: loading, 'aria-label': '取消' }}
      mask={{ closable: !loading }}
      keyboard={!loading}
      destroyOnHidden
    >
      <p>{content}</p>
      {apiError ? (
        <Alert
          type="error"
          showIcon
          title={apiError.message}
          description={apiError.requestId ? `请求编号：${apiError.requestId}` : undefined}
        />
      ) : null}
    </Modal>
  )
}
