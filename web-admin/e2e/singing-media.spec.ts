import {execFileSync} from 'node:child_process'
import {mkdtempSync,readFileSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {expect,test,type Page} from '@playwright/test'
function wav(frequency:number){
 const rate=48000,length=rate*10,body=Buffer.alloc(44+length*2)
 body.write('RIFF');body.writeUInt32LE(body.length-8,4);body.write('WAVEfmt ',8);body.writeUInt32LE(16,16);body.writeUInt16LE(1,20);body.writeUInt16LE(1,22);body.writeUInt32LE(rate,24);body.writeUInt32LE(rate*2,28);body.writeUInt16LE(2,32);body.writeUInt16LE(16,34);body.write('data',36);body.writeUInt32LE(length*2,40)
 for(let i=0;i<length;i++)body.writeInt16LE(frequency?Math.round(12000*(.15+.85*Math.pow(.5+.5*Math.sin(2*Math.PI*.7*i/rate),3))*Math.sin(2*Math.PI*frequency*i/rate)):0,44+i*2)
 return body
}
const envelope=(data:unknown)=>({code:'ok',message:'',request_id:'media-browser',data})
const session={id:'s1',status:'completed',duration_seconds:10,score:80,burp_count:1,is_mock:true,patient:{name:'测试患者',medical_record_no:'P001'},song:{title:'音轨验证'},media:[{asset_id:'patient',media_type:'singing_audio',status:'ready',mime:'audio/wav',size:960044}],analysis_results:[{task_type:'singing_audio_metrics',status:'completed',is_mock:true,payload:{burp_events:[3]},time_series:{snr_db:{sample_interval_ms:1000,values:[32]}}}],playback:{source_asset_id:'source',accompaniment_asset_id:'backing',reference_version:null,combined_available:true,metadata:{schema_version:1,sample_rate:48000,source_asset_id:'source',accompaniment_asset_id:'backing',reference_version:null,mode_changes:[],anchors:[{recording_ms:0,song_ms:0,track:'accompaniment',playing:true,segment:0},{recording_ms:10000,song_ms:10000,track:'accompaniment',playing:false,segment:0}]}}}
async function prepare(page:Page,frequency=220,denied=false,initial='/singing/s1',prepareMedia=false,withVideo=false,videoGate?:Promise<void>){
 await page.route('**/api/**',async route=>{
  const path=new URL(route.request().url()).pathname
    if(!path.startsWith('/api/')){await route.continue();return}
  let data:unknown={}
  if(path.endsWith('/auth/refresh/'))data={access:'browser-test-token',refresh_expires_at:'2099-01-01',user:{login_id:'DTEST',role:'doctor',must_change_password:false}}
  else if(path.endsWith('/singing-sessions/s1/'))data=withVideo?{...session,media:[...session.media,{asset_id:'video',media_type:'singing_video',status:'ready',mime:'video/mp4',size:10000}]}:session
  else if(path.endsWith('/admin/patients/'))data={count:21,page:2,page_size:20,results:[{id:'30000000-0000-0000-0000-000000000001',user_id:'u',name:'张患者',medical_record_no:'P001',gender:'male',phone:'13900000000',enrollment_age:30,primary_doctor:null,treatment_plan:null}]}
  else if(path.endsWith('/admin/patients/30000000-0000-0000-0000-000000000001/'))data={id:'30000000-0000-0000-0000-000000000001',name:'张患者',medical_record_no:'P001',gender:'male',phone:'13900000000',enrollment_age:30,primary_doctor:null,treatment_plan:null}
  else if(path.endsWith('/admin/singing-sessions/'))data={count:1,page:1,page_size:10,results:[session]}
  else if(path.endsWith('/analytics/patients/'))data={metric_version:'1.0',count:0,results:[]}
  else if(path.endsWith('/admin/doctors/'))data={count:0,page:1,page_size:20,results:[]}
  else if(path.endsWith('/patient/private-url/')){
   if(denied){await route.fulfill({status:403,json:{code:'denied',message:'患者录音授权拒绝',request_id:'denied',data:{}}});return}
   data={url:'/fixture/patient.wav',expires_at:'2099-01-01'}
  }else if(path.endsWith('/video/private-url/')){await videoGate;data={url:'/fixture/video.mp4',expires_at:'2099-01-01'}}
  else if(path.endsWith('/playback-accompaniment/'))data={asset_id:'backing',url:'/fixture/backing.wav',expires_at:'2099-01-01'}
  await route.fulfill({json:envelope(data)})
 })
 await page.route('**/fixture/*.wav',route=>{
   const body=wav(route.request().url().includes('patient')?frequency:440),range=route.request().headers().range
   const match=range?.match(/^bytes=(\d+)-(\d*)$/)
   if(match){const start=Number(match[1]),end=match[2]?Math.min(Number(match[2]),body.length-1):body.length-1;return route.fulfill({status:206,contentType:'audio/wav',headers:{'Accept-Ranges':'bytes','Content-Range':`bytes ${start}-${end}/${body.length}`},body:body.subarray(start,end+1)})}
   return route.fulfill({contentType:'audio/wav',headers:{'Accept-Ranges':'bytes'},body})
 })
 if(withVideo){
   const temp=mkdtempSync(join(tmpdir(),'vocaease-browser-video-'))
   let video:Buffer
   try {
     const source=fileURLToPath(new URL('../../android-patient/app/src/androidTest/assets/sample_avc_only.mp4',import.meta.url))
     const output=join(temp,'video.mp4')
     execFileSync(process.env.VOCAEASE_FFMPEG_BIN ?? 'ffmpeg',['-nostdin','-v','error','-stream_loop','-1','-i',source,'-t','10','-an','-c:v','copy','-movflags','+faststart',output])
     video=readFileSync(output)
   } finally {rmSync(temp,{recursive:true,force:true})}
   await page.route('**/fixture/video.mp4',route=>{
     const match=route.request().headers().range?.match(/^bytes=(\d+)-(\d*)$/)
     if(match){const start=Number(match[1]),end=match[2]?Math.min(Number(match[2]),video.length-1):video.length-1;return route.fulfill({status:206,contentType:'video/mp4',headers:{'Accept-Ranges':'bytes','Content-Range':`bytes ${start}-${end}/${video.length}`},body:video.subarray(start,end+1)})}
     return route.fulfill({contentType:'video/mp4',headers:{'Accept-Ranges':'bytes'},body:video})
   })
 }
 await page.goto(initial);if(prepareMedia)await page.getByRole('button',{name:'准备回放'}).click()
}
test('真实PCM驱动双图，暂停恢复和模式切换保持同一个患者音源',async({page})=>{
 const errors:string[]=[];page.on('pageerror',error=>errors.push(error.message))
 await prepare(page)
 await expect(page.getByRole('tab',{name:'人声 + 伴奏'})).toBeEnabled()
 await expect(page.getByRole('heading',{name:'演唱回放',exact:true})).toHaveCount(0)
 await page.getByRole('button',{name:'播放',exact:true}).click()
 await expect(page.locator('.voice-pitch')).toHaveText('220 Hz')
 await expect(page.locator('.voice-volume')).toContainText('dBFS')
 const audio=page.locator('audio').first();await audio.evaluate(element=>{element.setAttribute('data-patient-original','true')})
 await page.getByRole('tab',{name:'人声',exact:true}).click()
 await expect(audio).toHaveAttribute('data-patient-original','true');await expect(page.locator('.voice-pitch')).toHaveText('220 Hz')
 await page.getByRole('button',{name:'暂停',exact:true}).click();const paused=await audio.evaluate(element=>(element as HTMLAudioElement).currentTime)
 await page.getByRole('button',{name:'播放',exact:true}).click();await expect.poll(()=>audio.evaluate(element=>(element as HTMLAudioElement).currentTime)).toBeGreaterThan(paused+.1)
 await expect(page.locator('.voice-pitch')).toHaveText('220 Hz')
 await page.getByRole('tab',{name:'人声 + 伴奏'}).click()
 await expect.poll(()=>page.locator('audio').nth(1).evaluate(element=>(element as HTMLAudioElement).paused)).toBe(false)
 await expect.poll(()=>page.evaluate(()=>{const a=document.querySelectorAll('audio');return Math.abs(a[0].currentTime-a[1].currentTime)})).toBeLessThanOrEqual(.1)
 const pixels=()=>page.locator('canvas[aria-label="患者极坐标频谱"]').evaluate(element=>{const c=element as HTMLCanvasElement,d=c.getContext('2d')!.getImageData(0,0,c.width,c.height).data;let orange=0;for(let i=0;i<d.length;i+=4)if(d[i]>200&&d[i+1]>50&&d[i+1]<200)orange++;return orange})
 await expect.poll(pixels).toBeGreaterThan(100)
 await page.screenshot({path:'/tmp/vocaease-voice-board-1440.png',fullPage:true})
 await page.setViewportSize({width:390,height:844});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 await page.screenshot({path:'/tmp/vocaease-voice-board-390.png',fullPage:true})
 expect(errors).toEqual([])
})
test('真实静音保持基线且不被当成跨域错误',async({page})=>{
 await prepare(page,0)
 await expect(page.locator('.voice-board-plots')).toBeVisible()
 const before=await page.locator('.voice-board-plots').boundingBox()
 await page.getByRole('button',{name:'播放',exact:true}).click()
 await expect.poll(()=>page.locator('audio').first().evaluate(node=>(node as HTMLAudioElement).currentTime)).toBeGreaterThan(.3)
 await expect(page.getByText(/无稳定患者人声音高|患者声音采样不可用/)).toHaveCount(0)
 await expect(page.locator('.voice-pitch')).toHaveText('—')
 expect(await page.locator('.voice-board-plots').boundingBox()).toEqual(before)
 await page.getByRole('button',{name:'暂停',exact:true}).click()
 expect(await page.locator('.voice-board-plots').boundingBox()).toEqual(before)
})
test('拒绝授权时不显示假声音图和假音高',async({page})=>{
 await prepare(page,220,true);await expect(page.locator('.ant-message').getByText('患者录音授权拒绝')).toBeVisible();await expect(page.locator('.singing-detail-content .ant-alert')).toHaveCount(0);await expect(page.locator('audio')).toHaveCount(0);await expect(page.locator('canvas')).toHaveCount(0)
})

test('患者列表筛选经过详情与演唱明细两次返回仍保留',async({page})=>{
 await prepare(page,220,false,'/patient-data?page=2&page_size=20&search=张',false)
 await page.getByRole('button',{name:'查看数据'}).click()
 await expect(page.getByRole('heading',{name:'患者数据',exact:true})).toBeVisible()
 await page.getByRole('button',{name:'查看明细'}).click()
 await expect(page.getByRole('heading',{name:'演唱明细',exact:true})).toBeVisible()
 await page.getByRole('button',{name:'返回',exact:true}).click()
 await page.getByRole('button',{name:'返回患者列表'}).click()
 await expect(page.getByRole('heading',{name:'病人数据',exact:true})).toBeVisible()
 expect(new URL(page.url()).searchParams.get('page')).toBe('2')
 expect(new URL(page.url()).searchParams.get('search')).toBe('张')
 await expect(page.getByLabel('搜索患者')).toHaveValue('张')
})

test('录像授权迟到时真实MP4静音跟随且患者播放连续',async({page})=>{
 let releaseVideo!:()=>void
 const gate=new Promise<void>(resolve=>{releaseVideo=resolve})
 await prepare(page,220,false,'/singing/s1',false,true,gate)
 await page.getByRole('button',{name:'播放',exact:true}).click()
 await expect(page.locator('.voice-pitch')).toHaveText('220 Hz')
 const patient=page.locator('audio').first()
 await patient.evaluate(node=>{node.setAttribute('data-original-patient','true')})
 const before=await patient.evaluate(node=>(node as HTMLAudioElement).currentTime)
 releaseVideo()
 await expect(page.locator('video')).toBeVisible()
 await expect.poll(()=>page.locator('video').evaluate(node=>(node as HTMLVideoElement).readyState)).toBeGreaterThanOrEqual(2)
 await expect(patient).toHaveAttribute('data-original-patient','true')
 await expect.poll(()=>patient.evaluate(node=>(node as HTMLAudioElement).currentTime)).toBeGreaterThan(before+.1)
 await expect.poll(()=>page.evaluate(()=>Math.abs(document.querySelector('video')!.currentTime-document.querySelector('audio')!.currentTime))).toBeLessThanOrEqual(.1)
 expect(await page.locator('video').evaluate(node=>({muted:(node as HTMLVideoElement).muted,controls:(node as HTMLVideoElement).controls}))).toEqual({muted:true,controls:false})
 await expect(page.locator('.voice-pitch')).toHaveText('220 Hz')
})
