import { useState } from 'react'
import { Alert, Button, Form, Input, Typography } from 'antd'
import { useNavigate } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'

type PasswordFields = { old_password: string; new_password: string; confirm_password: string }

export function ChangePasswordPage() {
  const changePassword = useAuthStore((state) => state.changePassword)
  const navigate = useNavigate()
  const [error, setError] = useState('')

  const submit = async ({ old_password, new_password }: PasswordFields) => {
    setError('')
    try {
      await changePassword({ old_password, new_password })
      navigate('/login', { replace: true, state: { passwordChanged: true } })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '密码修改失败，请稍后重试')
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-panel" aria-labelledby="password-title">
        <Typography.Title id="password-title" level={1}>修改初始密码</Typography.Title>
        <Typography.Paragraph className="auth-description">为保障账号安全，首次登录后请设置新密码。</Typography.Paragraph>
        {error ? <Alert type="error" showIcon title={error} className="auth-error" /> : null}
        <Form<PasswordFields> layout="vertical" requiredMark={false} onFinish={submit} size="large">
          <Form.Item label="当前密码" name="old_password" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item label="新密码" name="new_password" rules={[{ required: true, min: 6, message: '新密码至少6位' }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label="确认新密码"
            name="confirm_password"
            dependencies={['new_password']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  return !value || getFieldValue('new_password') === value
                    ? Promise.resolve()
                    : Promise.reject(new Error('两次输入的密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" aria-label="确认修改" block>确认修改</Button>
        </Form>
      </section>
    </main>
  )
}
