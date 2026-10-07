import { useMemo, useRef } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Button, Input, Select, Space } from 'antd'
import type { InputRef, TableColumnsType } from 'antd'
import { DataTable } from '../../components/DataTable'
import { StatusTag } from '../../components/StatusTag'
import { RemoteDoctorSelect } from '../doctors/RemoteDoctorSelect'
import { useRemoteDoctorOptions } from '../doctors/useRemoteDoctorOptions'
import { listPatients, patientKeys } from '../patients/api'
import { readQuery, uiParams } from '../patients/patientListQuery'
import type { Patient, PatientListQuery } from '../patients/types'

export function PatientDataListPage() {
  const [params,setParams]=useSearchParams()
  const query=useMemo(()=>readQuery(params),[params])
  const navigate=useNavigate()
  const search=useRef<InputRef>(null)
  const doctors=useRemoteDoctorOptions()
  const result=useQuery({queryKey:patientKeys.list(query),queryFn:({signal})=>listPatients(query,signal),retry:false})
  const replace=(patch:Partial<PatientListQuery>,reset=true)=>setParams(uiParams({...query,...patch,page:reset?1:patch.page??query.page}))
  const columns:TableColumnsType<Patient>=[
    {title:'病历号',dataIndex:'medical_record_no'},
    {title:'姓名',dataIndex:'name'},
    {title:'年龄',dataIndex:'enrollment_age'},
    {title:'主治医生',dataIndex:'primary_doctor_name'},
    {title:'治疗状态',render:(_,row)=>row.treatment_plan?<StatusTag status={row.treatment_plan.status}/>: '无计划'},
    {title:'操作',render:(_,row)=><Button type="link" onClick={()=>navigate(`/patients/${row.id}/data`,{state:{backTo:`/patient-data?${uiParams(query)}`}})}>查看数据</Button>},
  ]
  return <section className="management-page" aria-labelledby="patient-data-list-title">
    <div className="management-heading"><div><h1 id="patient-data-list-title">病人数据</h1><p>查看患者演唱记录与治疗数据</p></div></div>
    <div className="management-surface">
      <div className="management-toolbar">
        <Space.Compact className="management-search">
          <Input ref={search} aria-label="搜索患者" placeholder="搜索姓名或病历号" defaultValue={query.search} key={query.search??''} allowClear onPressEnter={(event)=>replace({search:event.currentTarget.value.trim()||undefined})}/>
          <Button type="primary" onClick={()=>replace({search:search.current?.input?.value.trim()||undefined})}>搜索</Button>
        </Space.Compact>
        <Select aria-label="治疗状态" value={query.status} placeholder="全部治疗状态" allowClear onChange={(status)=>replace({status})} options={[{value:'pending',label:'待开始'},{value:'active',label:'进行中'},{value:'completed',label:'已完成'},{value:'cancelled',label:'已取消'}]}/>
        <RemoteDoctorSelect ariaLabel="主治医生筛选" mode="filter" lookupEnabled={!result.isPending} source={doctors} value={query.doctor} onChange={(doctor)=>replace({doctor})}/>
        <Button onClick={()=>setParams(uiParams({page:1,page_size:query.page_size}))}>重置</Button>
      </div>
      <DataTable ariaLabel="正在加载患者数据列表" columns={columns} data={result.data} emptyText="暂无患者数据" error={result.error} loading={result.isPending} onPageChange={(page,page_size)=>replace({page,page_size},page_size!==query.page_size)} onRetry={()=>void result.refetch()} rowKey="id" scrollX={900}/>
    </div>
  </section>
}
