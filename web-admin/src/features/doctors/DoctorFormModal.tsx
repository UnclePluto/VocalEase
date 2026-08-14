import { useRef, useState } from 'react'
import { Alert, Button, Form, Input, Modal } from 'antd'

import { ApiError } from '../../api/errors'
import type { Doctor, DoctorWrite } from './types'

type DoctorFormModalProps = {
  doctor?: Doctor | null
  onCancel: () => void
  onSubmit: (values: DoctorWrite) => Promise<void>
  open: boolean
}

function fieldMessage(value: string | string[]) {
  return Array.isArray(value) ? value.join('；') : value
}

export function DoctorFormModal({ doctor, onCancel, onSubmit, open }: DoctorFormModalProps) {
  const [form] = Form.useForm<DoctorWrite>()
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const submittingRef = useRef(false)

  const submit = async (values: DoctorWrite) => {
    if (submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(values)
      form.resetFields()
    } catch (caught) {
      const apiError = caught instanceof ApiError ? caught : new ApiError('unknown_error', '保存失败，请重试')
      setError(apiError)
      if (apiError.fieldErrors) {
        form.setFields(Object.entries(apiError.fieldErrors).map(([name, messages]) => ({
          name: name as keyof DoctorWrite,
          errors: [fieldMessage(messages)],
        })))
      }
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title={doctor ? '编辑医生' : '新增医生'}
      open={open}
      onCancel={onCancel}
      footer={null}
      destroyOnHidden
      mask={{ closable: !submitting }}
      keyboard={!submitting}
    >
      <Form
        clearOnDestroy
        form={form}
        initialValues={{
          name: doctor?.name ?? '',
          gender: doctor?.gender,
          phone: doctor?.phone ?? '',
          department: doctor?.department ?? '',
          title: doctor?.title ?? '',
        }}
        layout="vertical"
        onFinish={(values) => void submit(values)}
        requiredMark={false}
      >
        {doctor ? <Form.Item label="医生工号"><Input value={doctor.employee_no} disabled /></Form.Item> : null}
        <div className="form-grid">
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input maxLength={64} autoFocus />
          </Form.Item>
          <Form.Item name="gender" label="性别" rules={[{ required: true, message: '请选择性别' }]}>
            <select className="native-select">
              <option value="">请选择</option>
              <option value="male">男</option>
              <option value="female">女</option>
            </select>
          </Form.Item>
          <Form.Item name="phone" label="手机号" rules={[{ required: true, message: '请输入手机号' }]}>
            <Input maxLength={32} inputMode="tel" />
          </Form.Item>
          <Form.Item name="department" label="科室" rules={[{ required: true, message: '请输入科室' }]}>
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="title" label="职称" rules={[{ required: true, message: '请输入职称' }]}>
            <Input maxLength={64} />
          </Form.Item>
        </div>
        {error ? (
          <Alert
            className="form-api-error"
            type="error"
            showIcon
            title={error.message}
            description={error.requestId ? `请求编号：${error.requestId}` : undefined}
          />
        ) : null}
        <div className="modal-actions">
          <Button aria-label="取消" disabled={submitting} onClick={onCancel}>取消</Button>
          <Button aria-label="确定" type="primary" htmlType="submit" loading={submitting} disabled={submitting}>确定</Button>
        </div>
      </Form>
    </Modal>
  )
}
