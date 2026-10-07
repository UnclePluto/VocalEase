import { Button, message } from 'antd'
import { createContext, useContext, useEffect, useId, useRef, type ReactNode } from 'react'

const ToastContext = createContext<ReturnType<typeof message.useMessage>[0] | null>(null)

export function DetailToastProvider({ children }: { children: ReactNode }) {
  const [messageApi, contextHolder] = message.useMessage()
  return <ToastContext.Provider value={messageApi}>{contextHolder}{children}</ToastContext.Provider>
}

/** 明细提示放在浮层；音频采样与播放状态变化不改变内容区高度。 */
export function DetailToast({ text, requestId, type = 'error', onRetry, retryLabel = '重试' }: {
  text?: string
  requestId?: string
  type?: 'error' | 'warning'
  onRetry?: () => void
  retryLabel?: string
}) {
  const sharedMessageApi = useContext(ToastContext)
  const [localMessageApi, contextHolder] = message.useMessage()
  const messageApi = sharedMessageApi ?? localMessageApi
  const key = useId()
  const retry = useRef(onRetry)
  useEffect(() => { retry.current = onRetry }, [onRetry])
  const retryable = Boolean(onRetry)

  useEffect(() => {
    if (!text) return
    void messageApi.open({
      key,
      type,
      duration: retryable ? 0 : 6,
      content: <span className="singing-detail-toast">
        <span>{text}</span>
        {requestId ? <span>请求编号：{requestId}</span> : null}
        {retryable ? <Button type="link" aria-label={retryLabel} onClick={() => retry.current?.()}>{retryLabel}</Button> : null}
        <Button type="text" aria-label="关闭提示" onClick={() => messageApi.destroy(key)}>×</Button>
      </span>,
    })
    return () => messageApi.destroy(key)
  }, [key, messageApi, requestId, retryable, retryLabel, text, type])

  return sharedMessageApi ? null : contextHolder
}
