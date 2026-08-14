import { useState } from 'react'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { Alert, Button, Checkbox, Form, Input, Typography } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'

import { ApiError } from '../../api/errors'
import { useAuthStore } from '../../auth/store'

type LoginFields = { login_id: string; password: string; remember_me: boolean }

export function LoginPage() {
  const login = useAuthStore((state) => state.login)
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState('')
  const passwordChanged = Boolean((location.state as { passwordChanged?: boolean } | null)?.passwordChanged)

  const submit = async (values: LoginFields) => {
    setError('')
    try {
      await login(values)
      const state = useAuthStore.getState()
      const requested = (location.state as { from?: string } | null)?.from
      navigate(state.user?.must_change_password ? '/change-password' : requested || '/doctors', { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '登录失败，请稍后重试')
    }
  }

  return (
    <main className="auth-page">
      <section className="auth-panel" aria-labelledby="login-title">
        <div className="auth-brand" aria-label="VocaEase">
          <span className="auth-brand-mark" aria-hidden="true">V</span>
          <span>VocaEase</span>
        </div>
        <Typography.Title id="login-title" level={1}>登录 VocaEase</Typography.Title>
        <Typography.Paragraph className="auth-description">医生后台管理系统</Typography.Paragraph>
        {passwordChanged ? <Alert type="success" showIcon title="密码修改成功，请重新登录" className="auth-error" /> : null}
        {error ? <Alert type="error" showIcon title={error} className="auth-error" /> : null}
        <Form<LoginFields>
          layout="vertical"
          initialValues={{ remember_me: false }}
          requiredMark={false}
          onFinish={submit}
          size="large"
        >
          <Form.Item label="账号" name="login_id" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<UserOutlined />} autoComplete="username" placeholder="请输入医生工号或管理员账号" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} autoComplete="current-password" placeholder="请输入密码" />
          </Form.Item>
          <Form.Item name="remember_me" valuePropName="checked" className="remember-row">
            <Checkbox>记住我</Checkbox>
          </Form.Item>
          <Button type="primary" htmlType="submit" aria-label="登录" block>登录</Button>
        </Form>
        <Typography.Text type="secondary" className="auth-hint">首次登录需要修改初始密码</Typography.Text>
      </section>
    </main>
  )
}
