import { useRef, useState } from 'react'
import { Alert, Button, Form, Input, InputNumber, Modal } from 'antd'

import { ApiError } from '../../api/errors'
import type { Doctor } from '../doctors/types'
import type { Patient, PatientWrite } from './types'

type PatientFormModalProps = {
  doctors: Doctor[]
  doctorSearch: string
  loadingDoctors: boolean
  onDoctorSearch: (value: string) => void
  onCancel: () => void
  onSubmit: (values: PatientWrite) => Promise<void>
  open: boolean
  patient?: Patient | null
}

function fieldMessage(value: string | string[]) {
  return Array.isArray(value) ? value.join('；') : value
}

export function PatientFormModal({ doctors, doctorSearch, loadingDoctors, onCancel, onDoctorSearch, onSubmit, open, patient }: PatientFormModalProps) {
  const [form] = Form.useForm<PatientWrite>()
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const submittingRef = useRef(false)
  const cycleWeeks = Form.useWatch('cycle_weeks', form)
  const hasCurrentPlan = !patient || patient.treatment_plan?.status === 'pending' || patient.treatment_plan?.status === 'active'
  const selectableDoctors = patient ? [
    {
      ...doctors.find((doctor) => doctor.id === patient.primary_doctor),
      id: patient.primary_doctor,
      name: patient.primary_doctor_name,
      employee_no: doctors.find((doctor) => doctor.id === patient.primary_doctor)?.employee_no ?? '',
    },
    ...doctors.filter((doctor) => doctor.id !== patient.primary_doctor),
  ] : doctors

  const submit = async (rawValues: PatientWrite) => {
    if (submittingRef.current) return
    submittingRef.current = true
    setSubmitting(true)
    setError(null)
    const values = { ...rawValues }
    if (!hasCurrentPlan) {
      delete values.start_date
      delete values.cycle_weeks
    }
    try {
      await onSubmit(values)
      form.resetFields()
    } catch (caught) {
      const apiError = caught instanceof ApiError ? caught : new ApiError('unknown_error', '保存失败，请重试')
      setError(apiError)
      if (apiError.fieldErrors) {
        form.setFields(Object.entries(apiError.fieldErrors).map(([name, messages]) => ({
          name: name as keyof PatientWrite,
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
      title={patient ? '编辑患者' : '新增患者'}
      open={open}
      onCancel={onCancel}
      footer={null}
      width={680}
      destroyOnHidden
      mask={{ closable: !submitting }}
      keyboard={!submitting}
    >
      <Form
        clearOnDestroy
        form={form}
        initialValues={{
          name: patient?.name ?? '',
          gender: patient?.gender,
          enrollment_age: patient?.enrollment_age,
          phone: patient?.phone ?? '',
          primary_doctor: patient?.primary_doctor,
          start_date: hasCurrentPlan ? patient?.treatment_plan?.start_date : undefined,
          cycle_weeks: hasCurrentPlan ? patient?.treatment_plan?.cycle_weeks : undefined,
          notes: patient?.notes ?? '',
        }}
        layout="vertical"
        onFinish={(values) => void submit(values)}
        requiredMark={false}
      >
        <Form.Item label="病历号" htmlFor="patient-medical-record-no">
          <Input id="patient-medical-record-no" value={patient?.medical_record_no ?? '创建后由系统生成'} disabled />
        </Form.Item>
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
          <Form.Item name="enrollment_age" label="入组年龄" rules={[{ required: true, message: '请输入入组年龄' }]}>
            <InputNumber min={0} max={150} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="phone" label="手机号" rules={[{ required: true, message: '请输入手机号' }]}>
            <Input maxLength={32} inputMode="tel" />
          </Form.Item>
          <Form.Item label="主治医生">
            <div className="doctor-picker">
              <Input
                aria-label="搜索主治医生选项"
                allowClear
                placeholder="输入姓名或工号搜索"
                value={doctorSearch}
                onChange={(event) => onDoctorSearch(event.target.value)}
              />
              <Form.Item name="primary_doctor" noStyle rules={[{ required: true, message: '请选择主治医生' }]}>
                <select aria-label="主治医生" className="native-select" disabled={loadingDoctors}>
                  <option value="">请选择</option>
                  {selectableDoctors.map((doctor) => (
                    <option key={doctor.id} value={doctor.id}>
                      {doctor.name}{doctor.employee_no ? ` · ${doctor.employee_no}` : ''}
                    </option>
                  ))}
                </select>
              </Form.Item>
            </div>
          </Form.Item>
          <Form.Item
            name="start_date"
            label="治疗开始日期"
            rules={hasCurrentPlan ? [{ required: true, message: '请选择开始日期' }] : undefined}
          >
            <Input type="date" disabled={!hasCurrentPlan} />
          </Form.Item>
          <Form.Item
            name="cycle_weeks"
            label="治疗周期（周）"
            rules={hasCurrentPlan ? [{ required: true, message: '请输入治疗周期' }] : undefined}
          >
            <InputNumber min={1} max={520} precision={0} disabled={!hasCurrentPlan} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="目标演唱次数" htmlFor="patient-target-count">
            <Input id="patient-target-count" value={typeof cycleWeeks === 'number' ? cycleWeeks * 3 : ''} disabled />
          </Form.Item>
        </div>
        {!hasCurrentPlan ? <Alert className="form-plan-hint" type="info" showIcon title="当前没有可编辑的治疗计划，本次只更新患者资料。" /> : null}
        <Form.Item name="notes" label="备注"><Input.TextArea rows={3} maxLength={2000} showCount /></Form.Item>
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
