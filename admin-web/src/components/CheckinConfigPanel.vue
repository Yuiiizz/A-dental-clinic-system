<script setup lang="ts">
import { ref, watch } from 'vue';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';

const props=defineProps<{ branchId: string }>();
const session=useSessionStore();
const http=createHttpClient({baseUrl:import.meta.env.VITE_API_BASE_URL || '/api/v1',timeoutMs:15000,
  getCsrfToken:()=>session.csrfToken});
type Config={configured:boolean;self_checkin_enabled:boolean;early_minutes:number|null;
  late_minutes:number|null;slot_step_minutes:number;version:number};
const current=ref<Config|null>(null);
const enabled=ref(false),early=ref(''),late=ref(''),step=ref('15');
const busy=ref(false),error=ref(''),message=ref(''),stale=ref(false);
let request=0, savedFingerprint='', savedKey='';

function apply(config: Config) {
  current.value=config; enabled.value=config.self_checkin_enabled;
  early.value=config.early_minutes==null?'':String(config.early_minutes);
  late.value=config.late_minutes==null?'':String(config.late_minutes);
  step.value=String(config.slot_step_minutes); stale.value=false;
  savedFingerprint=''; savedKey='';
}
async function read() {
  const branch=props.branchId, own=++request;
  current.value=null; busy.value=true; error.value=''; message.value='';
  try {
    const result=await http.get<Config>(`/staff/branches/${encodeURIComponent(branch)}/booking-config`);
    if(own===request && branch===props.branchId) apply(result.data);
  } catch(e) {
    if(own===request) error.value=e instanceof ApiError?e.message:'签到设置读取失败。';
  } finally { if(own===request) busy.value=false; }
}
async function save() {
  if(!current.value || busy.value || stale.value) return;
  const earlyValue=String(early.value ?? '').trim(), lateValue=String(late.value ?? '').trim(), stepValue=String(step.value ?? '').trim();
  if(enabled.value && (!earlyValue || !lateValue) || !stepValue
      || (earlyValue && (!/^\d+$/.test(earlyValue)))
      || (lateValue && (!/^\d+$/.test(lateValue))) || !/^[1-9]\d*$/.test(stepValue)) {
    error.value='启用自助签到须填写非负的提前、迟到分钟；时段步长须为正整数。'; return;
  }
  const body={self_checkin_enabled:enabled.value,early_minutes:earlyValue===''?null:Number(earlyValue),
    late_minutes:lateValue===''?null:Number(lateValue),slot_step_minutes:Number(stepValue),expected_version:current.value.version};
  const fingerprint=JSON.stringify({branch_id:props.branchId,...body});
  if(fingerprint!==savedFingerprint){savedFingerprint=fingerprint;savedKey=crypto.randomUUID();}
  const branch=props.branchId,own=++request;
  busy.value=true;error.value='';message.value='';
  try {
    const result=await http.put<Config>(`/staff/branches/${encodeURIComponent(branch)}/booking-config`,body,
      {idempotencyKey:savedKey});
    if(own===request && branch===props.branchId){apply(result.data);message.value='签到设置已保存。';}
  } catch(e) {
    if(own!==request || branch!==props.branchId) return;
    if(e instanceof ApiError && e.status===409){stale.value=true;error.value='设置版本已变化。请重新读取并核对，再保存。';}
    else error.value=e instanceof ApiError?e.message:'保存结果暂不明确，请保留原输入并重试。';
  } finally {if(own===request) busy.value=false;}
}
watch(()=>[props.branchId,session.sessionRevision],()=>{request++;current.value=null;
  error.value='';message.value='';busy.value=false;stale.value=false;savedFingerprint='';savedKey='';
  if(props.branchId) void read();},{immediate:true});
</script>

<template>
  <section class="checkin-panel">
    <div class="head"><div><h2>到店签到设置</h2><p>控制本店小程序扫码签到及现场签到窗口。前台在预约工作台生成短效码。</p></div>
      <button type="button" :disabled="busy" @click="read">重新读取</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="!current" class="muted">{{ busy ? '正在读取…' : '未取得签到设置，请重新读取。' }}</p>
    <form v-else @submit.prevent="save"><fieldset :disabled="busy || stale">
      <p class="muted">当前版本 {{ current.version }} · {{ current.configured ? '已配置' : '尚未配置' }}</p>
      <label class="toggle"><input v-model="enabled" type="checkbox" />开放小程序本人扫码签到</label>
      <div class="fields">
        <label>可提前分钟<input v-model="early" type="number" min="0" step="1" :required="enabled" /></label>
        <label>可迟到分钟<input v-model="late" type="number" min="0" step="1" :required="enabled" /></label>
        <label>时段步长（分钟）<input v-model="step" type="number" min="1" step="1" required /></label>
      </div>
      <button class="primary" type="submit">保存签到设置</button>
    </fieldset></form>
  </section>
</template>

<style scoped>
.checkin-panel{border:1px solid #deebe8;border-radius:14px;background:#fff;padding:24px;margin-top:20px}.head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.head>div{flex:1;min-width:0}.head>button{flex:none;white-space:nowrap}.head h2{margin:0;font-size:20px}.head p,.muted{color:#637b7c}.fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:15px 0}.fields label{display:grid;gap:5px}.toggle{display:flex;align-items:center;gap:8px}input[type=number]{padding:8px;border:1px solid #bfd1cf;border-radius:7px;min-width:0}button{font:inherit;padding:8px 14px;border:1px solid #aac9c5;border-radius:7px;background:#fff;color:#195d60;cursor:pointer}button:disabled{opacity:.5}.primary{background:#166d70;color:#fff}.error,.notice{padding:10px;border-radius:8px}.error{background:#fff0eb;color:#9b382b}.notice{background:#dff3e9}fieldset{border:0;padding:0;margin:0}@media(max-width:680px){.fields{grid-template-columns:1fr}}
</style>
