<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ApiError, createHttpClient } from '@/services/http';
import { createSuppliersApi, type SupplierCreate, type SupplierDetail, type SupplierRow } from '@/services/suppliers';
import { useSessionStore } from '@/stores/session';

const session = useSessionStore();
const api = createSuppliersApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  getCsrfToken: () => session.csrfToken,
}));
const rows = ref<SupplierRow[]>([]);
const cursor = ref<string | null>(null);
const selected = ref<SupplierDetail | null>(null);
const form = ref<SupplierCreate>({ name: '', credit_code: '', qualification_ref: '', valid_to: '' });
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const notice = ref('');
let revision = 0;
let listRequestId = 0;
let detailRequestId = 0;
let createKey = '';
let createFingerprint = '';

function clearVisibleOnAccessLoss(e: unknown): void {
  if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 403)) return;
  revision++;
  listRequestId++;
  detailRequestId++;
  rows.value = [];
  cursor.value = null;
  selected.value = null;
  loading.value = false;
  saving.value = false;
}

function message(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return '登录状态已变化，请重新确认身份。';
    if (e.status === 403) return '当前账号无供应商管理权限。';
    if (e.status === 409) return '供应商主体标识已存在，或重复请求内容已变化，请核对后重试。';
    return e.message;
  }
  return '请求结果未确认，请保留表单后手动重试。';
}
async function load(more = false): Promise<void> {
  if (more && (loading.value || !cursor.value)) return;
  const token = revision;
  const requestId = ++listRequestId;
  loading.value = true;
  error.value = '';
  try {
    const page = await api.list(more ? cursor.value ?? undefined : undefined);
    if (token !== revision || requestId !== listRequestId) return;
    rows.value = more ? [...rows.value, ...page.items] : page.items;
    cursor.value = page.nextCursor;
  } catch (e) {
    if (token === revision && requestId === listRequestId) {
      clearVisibleOnAccessLoss(e);
      error.value = message(e);
    }
  } finally { if (token === revision && requestId === listRequestId) loading.value = false; }
}
async function choose(id: string): Promise<void> {
  const token = revision;
  const requestId = ++detailRequestId;
  error.value = '';
  try {
    const item = await api.detail(id);
    if (token === revision && requestId === detailRequestId) selected.value = item;
  } catch (e) {
    if (token === revision && requestId === detailRequestId) {
      clearVisibleOnAccessLoss(e);
      error.value = message(e);
    }
  }
}
async function create(): Promise<void> {
  if (saving.value) return;
  const input = { name: form.value.name.trim(), credit_code: form.value.credit_code.trim(),
    qualification_ref: form.value.qualification_ref.trim(), valid_to: form.value.valid_to };
  if (!input.name || !input.credit_code || !input.qualification_ref || !input.valid_to) {
    error.value = '请填写主体名称、标识、人工核验依据和资质有效期。'; return;
  }
  const fingerprint = JSON.stringify(input);
  if (fingerprint !== createFingerprint) { createFingerprint = fingerprint; createKey = crypto.randomUUID(); }
  const token = revision;
  saving.value = true;
  error.value = ''; notice.value = '';
  try {
    const item = await api.create(input, createKey);
    if (token !== revision) return;
    selected.value = item;
    form.value = { name: '', credit_code: '', qualification_ref: '', valid_to: '' };
    createFingerprint = ''; createKey = '';
    notice.value = '供应商档案已保存。采购和入库仍须在对应业务流程办理。';
    await load();
  } catch (e) { if (token === revision) { clearVisibleOnAccessLoss(e); error.value = message(e); } }
  finally { if (token === revision) saving.value = false; }
}
watch(() => session.sessionRevision, () => {
  revision++; listRequestId++; detailRequestId++; rows.value = []; cursor.value = null; selected.value = null;
  form.value = { name: '', credit_code: '', qualification_ref: '', valid_to: '' };
  createFingerprint = ''; createKey = ''; saving.value = false; loading.value = false;
  error.value = ''; notice.value = '';
  if (session.status === 'ready') void load();
});
onMounted(() => { void load(); });
</script>

<template>
  <main class="supplier-page">
    <header><div><RouterLink to="/">← 返回工作台</RouterLink><h1>供应商档案</h1><p>只维护人工核验的供应主体；采购验收与库存另按正式流程记录。</p></div><div class="header-actions"><RouterLink to="/inventory">库存管理 →</RouterLink><span>真实接口</span></div></header>
    <div class="supplier-grid">
      <section class="panel"><div class="panel-head"><h2>供应商列表</h2><button type="button" :disabled="loading" @click="load()">刷新</button></div>
        <p v-if="loading">正在读取…</p><p v-if="!loading && !rows.length">暂无可见供应商。</p>
        <button v-for="item in rows" :key="item.supplier_id" type="button" class="supplier-row" @click="choose(item.supplier_id)">
          <strong>{{ item.name }}</strong><small>{{ item.credit_code }} · {{ item.enabled ? '档案已启用' : '已停用' }} · 有效期 {{ item.valid_to || '未填写' }}</small>
        </button><button v-if="cursor" type="button" :disabled="loading" @click="load(true)">加载更多</button>
      </section>
      <section class="panel"><h2>人工核验后建档</h2><p class="hint">请依据真实供应商资质资料填写；本页面不上传证书，也不会自动生成采购或库存。</p>
        <form @submit.prevent="create"><fieldset :disabled="saving"><label>法定名称<input v-model="form.name" maxlength="120" required /></label>
          <label>统一社会信用代码或主体标识<input v-model="form.credit_code" maxlength="30" required /></label>
          <label>资质文件位置与核验依据<textarea v-model="form.qualification_ref" maxlength="300" rows="3" required /></label>
          <label>资质有效期<input v-model="form.valid_to" type="date" required /></label>
          <button type="submit">{{ saving ? '保存中…' : '保存供应商' }}</button></fieldset></form>
        <div v-if="selected" class="detail"><h3>{{ selected.name }}</h3><p>主体标识：{{ selected.credit_code }}</p><p>资质依据：{{ selected.qualification_ref }}</p>
          <p>有效期：{{ selected.valid_to }} · 核验员工编号：{{ selected.verified_by }}</p></div>
      </section>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </main>
</template>

<style scoped>
.supplier-page{min-height:100vh;padding:28px;background:#f4faf8;color:#23443e}.supplier-page header{max-width:1180px;margin:0 auto 22px;display:flex;justify-content:space-between;align-items:center}.supplier-page a{color:#3c9b85;text-decoration:none}.supplier-page h1{font-size:30px;margin:8px 0}.supplier-page header p,.hint{color:#6b8580}.supplier-page header span{border-radius:99px;background:#def5ec;padding:8px 14px;color:#247c65}.header-actions{display:flex;align-items:center;gap:14px;flex:none}.header-actions a{font-size:13px;font-weight:650;white-space:nowrap}@media(max-width:760px){.supplier-page header{display:flex;align-items:flex-start;gap:10px}.header-actions{flex-direction:column;align-items:flex-end;gap:8px}.supplier-page h1{font-size:26px}}.supplier-grid{max-width:1180px;margin:auto;display:grid;grid-template-columns:1fr 1fr;gap:20px}.panel{background:#fff;border:1px solid #daece5;border-radius:20px;padding:24px;box-shadow:0 12px 32px #25564b10}.panel h2{margin:0 0 16px}.panel-head{display:flex;justify-content:space-between}.supplier-row{display:block;width:100%;text-align:left;margin:7px 0;padding:14px;background:#f5fbf8;border:1px solid #dcece6;border-radius:12px;color:inherit;cursor:pointer}.supplier-row strong,.supplier-row small{display:block}.supplier-row small{margin-top:6px;color:#718882}.panel label{display:block;margin:13px 0;font-weight:600}.panel input,.panel textarea{box-sizing:border-box;display:block;width:100%;margin-top:6px;padding:10px;border:1px solid #cbded7;border-radius:9px;font:inherit}.panel button:not(.supplier-row),.panel form button{padding:9px 16px;border:0;border-radius:9px;background:#2b9a80;color:#fff;cursor:pointer}.panel button:disabled{opacity:.5}.panel fieldset{border:0;padding:0;margin:0}.detail{margin-top:24px;padding:16px;background:#f8fbf8;border-radius:12px}.detail h3{margin:0 0 10px}.detail p{overflow-wrap:anywhere}.error,.notice{max-width:1180px;margin:15px auto;padding:12px;border-radius:10px}.error{background:#fff0ed;color:#9e3c35}.notice{background:#e6f7ee;color:#24765b}@media(max-width:760px){.supplier-grid{grid-template-columns:1fr}.supplier-page{padding:14px}}
</style>
