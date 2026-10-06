import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import userEvent from '@testing-library/user-event'
import { it, expect } from 'vitest'
import { useAuthStore } from '../../auth/store'
import { renderApp } from '../../test/renderApp'
import { server } from '../../test/server'

it('patientDataEntryLoadsAuthorizedPatients', async () => {
  useAuthStore.setState({accessToken:'valid',status:'authenticated',user:{login_id:'D001',role:'doctor',must_change_password:false}})
  server.use(http.get('/api/v1/admin/patients/', () => HttpResponse.json({code:'ok',message:'',request_id:'data-list',data:{count:1,page:1,page_size:20,results:[{id:'30000000-0000-0000-0000-000000000001',user_id:'40000000-0000-0000-0000-000000000001',medical_record_no:'P000001',name:'患者甲',gender:'female',enrollment_age:32,phone:'13900000001',primary_doctor:'10000000-0000-0000-0000-000000000001',primary_doctor_name:'王医生',treatment_plan:null}]}})))
  renderApp('/patient-data')
  expect(await screen.findByText('患者甲')).toBeVisible()
  expect(screen.getByRole('button',{name:'查看数据'})).toBeVisible()
  expect(screen.queryByText('模块建设中')).not.toBeInTheDocument()
})

const envelope = (data: unknown) => ({code:'ok',message:'',request_id:'data-list',data})
const authenticate = () => useAuthStore.setState({accessToken:'valid',status:'authenticated',user:{login_id:'D001',role:'doctor',must_change_password:false}})
it('errorsCanRetryAndEmptyListIsExplicit', async () => {
  authenticate()
  let attempts=0
  server.use(http.get('/api/v1/admin/patients/', () => ++attempts===1 ? HttpResponse.json({code:'unavailable',message:'患者暂不可用',request_id:'list-failure',data:{}},{status:503}) : HttpResponse.json(envelope({count:0,page:1,page_size:20,results:[]}))))
  renderApp('/patient-data')
  expect(await screen.findByText('患者暂不可用')).toBeVisible()
  await userEvent.click(screen.getByRole('button',{name:'重试'}))
  await waitFor(() => expect(screen.getByText('暂无患者数据')).toBeVisible())
})
it('filtersAndPaginationSurviveDetailReturn', async () => {
  authenticate()
  const patient={id:'30000000-0000-0000-0000-000000000001',name:'张患者',medical_record_no:'P1',user_id:'u',gender:'male',enrollment_age:30,primary_doctor:null,treatment_plan:null,phone:'13900000000'}
  const queries:string[]=[]
  server.use(
    http.get('/api/v1/admin/patients/',({request})=>{queries.push(new URL(request.url).search);return HttpResponse.json(envelope({count:21,page:2,page_size:20,results:[patient]}))}),
    http.get('/api/v1/admin/patients/:id/',()=>HttpResponse.json(envelope(patient))),
    http.get('/api/v1/admin/singing-sessions/',()=>HttpResponse.json(envelope({count:0,page:1,page_size:10,results:[]}))),
    http.get('/api/v1/admin/analytics/patients/',()=>HttpResponse.json(envelope({metric_version:'1.0',count:0,results:[]}))),
  )
  renderApp('/patient-data?page=2&page_size=20&search=张')
  await userEvent.click(await screen.findByRole('button',{name:'查看数据'}))
  await screen.findByText('13900000000')
  await userEvent.click(screen.getByRole('button',{name:'返回患者列表'}))
  expect(await screen.findByText('张患者')).toBeVisible()
  expect(screen.getByLabelText('搜索患者')).toHaveValue('张')
  expect(queries[0]).toContain('page=2')
  expect(queries[0]).toContain('keyword=%E5%BC%A0')
})
