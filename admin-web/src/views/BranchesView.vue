<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useBranches } from '@/composables/useBranches';
import { branchFields, emptyBranchForm, formOf, intervalFromInputs, type BranchAction, type Interval } from '@/services/branches';
import CheckinConfigPanel from '@/components/CheckinConfigPanel.vue';
const model = useBranches();
const { session, ready, rows, cursor, selected, detail, hours, busy, message, error } = model;
const createForm = ref(emptyBranchForm()), manager = ref(''), createConfirmed = ref(false);
const editForm = ref(emptyBranchForm()), editLoaded = ref(false), editConfirmed = ref(false);
const reason = ref(''), noExpiry = ref(false), actionConfirmed = ref(false);
const from = ref(''), to = ref(''), day = ref(''), start = ref('09:00'), end = ref('18:00');
const draftIntervals = ref<Interval[]>([]), hoursLoaded = ref(false), hoursConfirmed = ref(false), localError = ref('');
const draftRange = ref('');
const owner = computed(() => session.me?.roles.some(r => r.role_code === 'owner') ?? false);
const disabled = computed(() => busy.value || !ready.value || session.authSubmitting);
const rangeMatches = computed(() => hours.value?.from_date === from.value && hours.value?.to_date === to.value);
const fields = [
  { key: 'code' as const, label: '门店代码', max: 30 }, { key: 'name' as const, label: '门店名称', max: 100 },
  { key: 'address' as const, label: '执业地址', max: 300 }, { key: 'phone' as const, label: '联系电话', max: 30 },
  { key: 'license_no' as const, label: '许可证号', max: 80 }, { key: 'opening_hours' as const, label: '营业时间说明', max: 200 },
];
function resetDrafts() {
  createForm.value = emptyBranchForm(); manager.value = ''; createConfirmed.value = false;
  editForm.value = emptyBranchForm(); editLoaded.value = false; editConfirmed.value = false;
  draftIntervals.value = []; hoursLoaded.value = false; hoursConfirmed.value = false; draftRange.value = '';
  reason.value = ''; noExpiry.value = false; actionConfirmed.value = false; localError.value = '';
}
watch(model.resetRevision, resetDrafts, { flush: 'sync' });
watch([createForm, manager], () => { createConfirmed.value = false; }, { deep: true });
watch(editForm, () => { editConfirmed.value = false; }, { deep: true });
watch([reason, noExpiry, detail], () => { actionConfirmed.value = false; });
watch([draftIntervals, from, to], () => { hoursConfirmed.value = false; }, { deep: true });
watch(detail, d => { if (d && !editLoaded.value) { editForm.value = formOf(d); editLoaded.value = true; } editConfirmed.value = false; });
watch(hours, h => { if (h && !hoursLoaded.value) { draftIntervals.value = h.intervals.map(i => ({ ...i })); hoursLoaded.value = true; draftRange.value = `${h.from_date}/${h.to_date}`; } hoursConfirmed.value = false; });
onMounted(() => { if (ready.value) void model.list(); });
async function choose(id: string) {
  if (disabled.value) return;
  if (selected.value !== id && (editLoaded.value || hoursLoaded.value) && !window.confirm('切换门店将放弃当前草稿，是否继续？')) return;
  if (selected.value !== id) { editLoaded.value = false; hoursLoaded.value = false; draftRange.value = ''; draftIntervals.value = []; reason.value = ''; }
  await model.select(id);
}
async function create() {
  if (!createConfirmed.value) return;
  const body = { ...branchFields(createForm.value), manager_id: manager.value.trim() };
  // 创建DTO中的有效期可省略，但不提交null。
  const { license_valid_to, ...rest } = body;
  await model.create(license_valid_to ? { ...rest, license_valid_to } : rest);
  createConfirmed.value = false;
}
async function save() {
  if (!editConfirmed.value) return;
  if (await model.mutate('patch', branchFields(editForm.value))) {
    if (detail.value) editForm.value = formOf(detail.value);
  }
  editConfirmed.value = false;
}
async function act(action: BranchAction) {
  if (!actionConfirmed.value) return;
  const payload = action === 'verify' ? { verification_basis: reason.value.trim(), license_no_expiry_confirmed: noExpiry.value }
    : action === 'disable' ? { reason: reason.value.trim() } : {};
  await model.mutate(action, payload); actionConfirmed.value = false;
}
async function readHours(discard = false) {
  if (discard && !window.confirm('放弃营业时间草稿，重新读取服务器记录？')) return;
  if (discard) hoursLoaded.value = false;
  if (hoursLoaded.value && draftRange.value !== `${from.value}/${to.value}`) {
    if (!window.confirm('日期范围已变化，读取新范围将替换营业时间草稿，是否继续？')) return;
    hoursLoaded.value = false;
  }
  await model.readHours(from.value, to.value);
}
function addInterval() {
  localError.value = '';
  try {
    const i = intervalFromInputs(day.value, start.value, end.value);
    if (day.value < from.value || day.value > to.value) throw new Error('区间日期必须在已读取的日期范围内。');
    if (draftIntervals.value.some(x => Date.parse(i.starts_at) < Date.parse(x.ends_at) && Date.parse(i.ends_at) > Date.parse(x.starts_at))) throw new Error('该营业区间与已有区间重叠。');
    draftIntervals.value = [...draftIntervals.value, i].sort((a,b) => Date.parse(a.starts_at)-Date.parse(b.starts_at));
  } catch (e) { localError.value = e instanceof Error ? e.message : '时间填写无效。'; }
}
async function saveHours() {
  if (!hoursConfirmed.value || !rangeMatches.value) return;
  if (await model.saveHours(draftIntervals.value)) {
    if (hours.value) draftIntervals.value = hours.value.intervals.map(i => ({ ...i }));
  }
  hoursConfirmed.value = false;
}
</script>

<template>
  <main class="branches-page">
    <header><div><RouterLink to="/">← 管理首页</RouterLink><h1>门店管理</h1><p>维护门店档案与营业时间，为医生排班做好准备。</p></div><span class="badge">组织与门店</span></header>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="error || localError" class="error" role="alert">{{ error || localError }}</p>
    <section v-if="!ready" class="panel"><h2>请先确认登录身份</h2><p>门店资料已隐藏。</p><RouterLink to="/login">重新登录或核实身份</RouterLink></section>
    <template v-else>
      <section class="panel"><div class="section-title"><h2>我的门店</h2><button :disabled="disabled" @click="model.list()">刷新列表</button></div>
        <p class="muted">仅显示你有权配置的门店；创建权限还需由系统核对经营主体负责人身份。</p>
        <div class="table-wrap"><table><thead><tr><th>代码</th><th>名称</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="row in rows" :key="row.branch_id" :class="{ selected: selected === row.branch_id }"><td>{{ row.code }}</td><td>{{ row.name }}</td><td><span class="state">{{ row.state }}</span></td><td><button :disabled="disabled" @click="choose(row.branch_id)">配置</button></td></tr>
        </tbody></table></div><p v-if="!rows.length && !busy" class="muted">尚无可显示门店。可刷新重试；权限以服务端核验为准。</p>
        <button v-if="cursor" :disabled="disabled" @click="model.list(true)">加载更多</button>
      </section>
      <details v-if="owner" class="panel"><summary>＋ 创建门店</summary><p class="muted">创建草稿后需重新登录。首期负责人必须为本人，不会自动赋予其他门店权限。</p>
        <form @submit.prevent="create"><fieldset :disabled="disabled"><div class="form-grid">
          <label v-for="f in fields" :key="f.key">{{ f.label }}<input v-model="createForm[f.key]" :maxlength="f.max" required /></label>
          <label>许可有效期（可后补）<input v-model="createForm.license_valid_to" type="date" /></label>
          <label>本人员工编号<input v-model="manager" pattern="[1-9][0-9]*" required /><small>当前接口未提供员工选择器，请填经管理员确认的编号，服务端会核对是否本人。</small></label>
        </div><label class="check"><input v-model="createConfirmed" type="checkbox" />确认以上资料，创建草稿门店</label><button class="primary" :disabled="!createConfirmed">创建门店</button></fieldset></form>
      </details>
      <template v-if="selected">
        <section class="panel"><div class="section-title"><h2>门店档案</h2><button :disabled="disabled" @click="model.select(selected)">重新读取并核对</button></div>
          <p v-if="!detail" class="muted">先读取最新门店资料再保存。已有草稿会保留，读取后请核对差异。</p>
          <p v-else>当前状态：<strong>{{ detail.state }}</strong> · 资料版本 {{ detail.version }} · 负责人编号 {{ detail.manager_id }}</p>
          <form @submit.prevent="save"><fieldset :disabled="disabled || !detail"><div class="form-grid">
            <label v-for="f in fields" :key="f.key">{{ f.label }}<input v-model="editForm[f.key]" :maxlength="f.max" required :disabled="detail?.state === '营业' && ['address','license_no'].includes(f.key)" /><small v-if="detail && editForm[f.key] !== detail[f.key]">服务器当前值：{{ detail[f.key] }}</small></label>
            <label>许可有效期<input v-model="editForm.license_valid_to" type="date" :disabled="detail?.state === '营业'" /><small v-if="detail">服务器当前值：{{ detail.license_valid_to || '未填写' }}</small></label>
          </div><p class="muted">营业期间变更执业地址、许可信息须先安全停用并重新核验。营业时间说明不会修改下面的实际营业区间。</p>
          <label class="check"><input v-model="editConfirmed" type="checkbox" />已核对当前资料与草稿，确认保存</label><button class="primary" :disabled="!editConfirmed">保存资料</button></fieldset></form>
          <div v-if="detail" class="lifecycle"><h3>状态管理</h3><p class="muted">核验是人工核对记录，不代表已接通官方许可查询。停用保留历史资料。</p>
            <fieldset :disabled="disabled"><label v-if="detail.state === '待核验' || detail.state === '营业'">{{ detail.state === '待核验' ? '核验依据' : '停用原因' }}<textarea v-model="reason" maxlength="300" rows="2" /></label>
              <label v-if="detail.state === '待核验' && !detail.license_valid_to" class="check"><input v-model="noExpiry" type="checkbox" />已人工核实许可证无期限，并在依据中说明</label>
              <label class="check"><input v-model="actionConfirmed" type="checkbox" />确认对当前门店执行下列状态操作（未保存草稿不生效）</label>
              <button v-if="detail.state === '草稿'" :disabled="!actionConfirmed" @click="act('submit')">提交核验</button>
              <button v-if="detail.state === '待核验'" :disabled="!actionConfirmed || !reason.trim() || (!detail.license_valid_to && !noExpiry)" @click="act('verify')">核验并营业</button>
              <button v-if="detail.state === '营业'" class="danger" :disabled="!actionConfirmed || !reason.trim()" @click="act('disable')">停用门店</button>
              <button v-if="detail.state === '停用'" :disabled="!actionConfirmed" @click="act('reopen')">重新提交核验</button>
            </fieldset>
          </div>
        </section>
        <section class="panel"><h2>实际营业时间</h2><p class="muted">按北京时间配置。一次最多31个自然日，可安排更远的未来。今天已经开始的区间必须原样保留。</p>
          <fieldset :disabled="disabled"><div class="range"><label>开始日期<input v-model="from" type="date" /></label><label>结束日期<input v-model="to" type="date" /></label><button :disabled="!from || !to" @click="readHours()">读取并核对</button><button :disabled="!from || !to" @click="readHours(true)">放弃时间草稿并读取</button></div></fieldset>
          <p v-if="hours">已读取：{{ hours.from_date }} 至 {{ hours.to_date }} · 版本 {{ hours.version }}</p>
          <p v-if="hoursLoaded && !hours" class="muted">草稿已保留。请重新读取最新版本，再核对保存。</p>
          <details v-if="hours && hoursLoaded"><summary>查看服务器当前营业区间，核对草稿差异</summary><ul><li v-for="(i,n) in hours.intervals" :key="n">{{ i.valid_on }}：{{ i.starts_at }} — {{ i.ends_at }}</li></ul><p v-if="!hours.intervals.length">该范围目前没有营业区间。</p></details>
          <fieldset :disabled="disabled || !hours || !rangeMatches"><div class="range"><label>营业日期<input v-model="day" type="date" /></label><label>开始时间<input v-model="start" type="time" /></label><label>结束时间<input v-model="end" placeholder="18:00 或 24:00" /></label><button @click="addInterval">添加区间</button></div>
            <ul class="intervals"><li v-for="(i,n) in draftIntervals" :key="n"><span>{{ i.valid_on }}：{{ i.starts_at }} — {{ i.ends_at }}</span><button @click="draftIntervals.splice(n,1)">移除</button></li></ul>
            <p v-if="hoursLoaded && !draftIntervals.length" class="warning">当前草稿为空：保存将关闭所选日期范围的营业时间。</p>
            <label class="check"><input v-model="hoursConfirmed" type="checkbox" />已核对当前区间与草稿，确认替换整个所选日期范围</label>
            <button class="primary" :disabled="!hoursConfirmed" @click="saveHours">保存营业时间</button>
          </fieldset>
        </section>
        <CheckinConfigPanel :branch-id="selected" />
      </template>
      <p v-if="busy" role="status" class="muted">正在处理，请稍候…</p>
    </template>
  </main>
</template>

<style scoped>
button{white-space:nowrap}
.branches-page{max-width:1120px;margin:auto;padding:28px 24px 60px;color:#183e40;background:#f5f9f8;min-height:100vh;font:15px/1.6 system-ui,sans-serif}header,.section-title{display:flex;justify-content:space-between;align-items:center;gap:16px}h1{font-size:28px;margin:12px 0 0}h2{font-size:20px;margin:0 0 14px}h3{font-size:17px}header p,.muted,small{color:#637b7c}a{color:#136e73}.panel{background:white;border:1px solid #deebe8;border-radius:14px;padding:24px;margin-top:20px}.badge,.state{background:#e4f3ef;color:#226c60;border-radius:16px;padding:4px 12px;white-space:nowrap}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}label{display:flex;flex-direction:column;gap:5px}input,textarea{font:inherit;border:1px solid #bfd1cf;border-radius:7px;padding:9px;min-width:0;background:white;color:inherit}input:disabled{background:#f1f4f3}small{font-size:12px;overflow-wrap:anywhere}button{font:inherit;border:1px solid #aac9c5;border-radius:7px;background:white;color:#195d60;padding:8px 14px;cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}.primary{background:#166d70;color:white;border-color:#166d70}.danger{color:#a54739;border-color:#d6aaa3}.check{flex-direction:row;align-items:flex-start;margin:18px 0;gap:8px}.check input{margin-top:6px}.range{display:flex;gap:12px;align-items:end;flex-wrap:wrap;margin:16px 0}.range label{flex:1;min-width:140px}.table-wrap{overflow:auto}table{border-collapse:collapse;width:100%;text-align:left}th,td{padding:12px;border-bottom:1px solid #e5eeeb}th{color:#5a7776;font-weight:500}.selected{background:#f0f8f5}.notice,.error{border-radius:8px;padding:14px}.notice{background:#dff3e9}.error{background:#fff0eb;color:#9b382b}.warning{color:#9a5b19}.lifecycle{border-top:1px solid #e1ebe8;margin-top:24px;padding-top:8px}fieldset{border:0;padding:0;margin:0;min-width:0}.intervals{padding:0;list-style:none}.intervals li{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #edf2f0;overflow-wrap:anywhere}summary{cursor:pointer;font-weight:600}details ul{overflow-wrap:anywhere}@media(max-width:680px){.form-grid{grid-template-columns:1fr}.branches-page{padding:16px 12px}.panel{padding:16px}.badge{display:none}}
</style>
