<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { useStaff } from '@/composables/useStaff';
import type { CredentialSummary, StaffBranch, StaffCreateInput } from '@/services/staff';
import { ApiError, createHttpClient } from '@/services/http';
import { newIdempotencyKey } from '@/services/account-admin';
import { createCredentialReviewApi, type CredentialDecision, type CredentialFacts, type CredentialReviewItem, type NewCredentialInput } from '@/services/credential-review';

const model = useStaff();
const {
  session, ready, rows, nextCursor, selectedId, detail, listLoading, detailLoading, createBusy,
  listError, detailError, createError, message, resetRevision,
} = model;

interface FormState {
  name: string;
  staff_type: '牙医' | '其他人员';
  job_title: string;
  practice_no: string;
}
interface CredentialForm {
  credential_type: string;
  certificate_no: string;
  valid_from: string;
  valid_to: string;
  no_expiry_confirmed: boolean;
}

const form = ref<FormState>(emptyForm());
const credentialEnabled = ref(false);
const credential = ref<CredentialForm>(emptyCredential());
const localError = ref('');
const submitting = ref(false);
const draftRevision = ref(0);
const selectionRevision = ref(0);
const owner = computed(() => session.me?.roles.some((role) => role.role_code === 'owner') ?? false);
const canCreate = computed(() => owner.value);
const disabled = computed(() => createBusy.value || submitting.value || !ready.value || session.authSubmitting);
const credentialApi = createCredentialReviewApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));
const reviewFacts = ref<CredentialFacts | null>(null);
const factsLoading = ref(false);
const reviewBusy = ref(false);
const reviewError = ref('');
const reviewNotice = ref('');
const reviewDecision = ref<CredentialDecision>('verified');
const reviewBasis = ref('');
const historyCredentialId = ref<string | null>(null);
const historyItems = ref<CredentialReviewItem[]>([]);
const historyCursor = ref<string | null>(null);
const historyLoading = ref(false);
const historyError = ref('');
const addOpen = ref(false);
const addForm = ref<CredentialForm>(emptyCredential());
const addBusy = ref(false);
const addError = ref('');
const addNotice = ref('');
let factsSequence = 0;
let reviewSequence = 0;
let historySequence = 0;
let addSequence = 0;
let reviewKey = '';
let reviewFingerprint = '';
let addKey = '';
let addFingerprint = '';

interface SubmitContext {
  id: number;
  session: string;
  resetRevision: number;
  draftRevision: number;
  selectionRevision: number;
}
let alive = true;
let submitSequence = 0;
let activeSubmit: SubmitContext | null = null;
let clearingFields = false;
const sessionIdentity = () => JSON.stringify([
  session.sessionRevision,
  session.status,
  session.me?.account_id,
  session.me?.must_change_password,
  session.me?.roles,
]);

function emptyForm(): FormState {
  return { name: '', staff_type: '其他人员', job_title: '', practice_no: '' };
}
function emptyCredential(): CredentialForm {
  return {
    credential_type: 'dentist_practice',
    certificate_no: '',
    valid_from: '',
    valid_to: '',
    no_expiry_confirmed: false,
  };
}
function clearFields(): void {
  clearingFields = true;
  form.value = emptyForm();
  credentialEnabled.value = false;
  credential.value = emptyCredential();
  clearingFields = false;
  localError.value = '';
}
function resetForm(): void {
  clearFields();
  model.beginNewCreate();
}
function invalidateSubmit(): void {
  submitSequence++;
  activeSubmit = null;
  submitting.value = false;
}
function isCurrent(context: SubmitContext): boolean {
  return alive
    && activeSubmit?.id === context.id
    && context.session === sessionIdentity()
    && context.resetRevision === model.resetRevision.value;
}
watch([form, credential, credentialEnabled], () => {
  if (clearingFields) return;
  localError.value = '';
  draftRevision.value++;
}, { deep: true, flush: 'sync' });
watch(() => credential.value.no_expiry_confirmed, (value) => {
  if (value) credential.value.valid_to = '';
});
watch(() => addForm.value.no_expiry_confirmed, (value) => {
  if (value) addForm.value.valid_to = '';
});
watch(resetRevision, () => {
  invalidateSubmit();
  resetForm();
}, { flush: 'sync' });
function closeFacts(): void {
  factsSequence++;
  reviewSequence++;
  historySequence++;
  reviewFacts.value = null;
  factsLoading.value = false;
  reviewBusy.value = false;
  reviewError.value = '';
  reviewNotice.value = '';
  reviewBasis.value = '';
  reviewDecision.value = 'verified';
  historyCredentialId.value = null;
  historyItems.value = [];
  historyCursor.value = null;
  historyLoading.value = false;
  historyError.value = '';
  reviewKey = '';
  reviewFingerprint = '';
}
watch([selectedId, resetRevision, sessionIdentity], closeFacts, { flush: 'sync' });
function closeAdd(): void {
  addSequence++;
  addOpen.value = false;
  addForm.value = emptyCredential();
  addBusy.value = false;
  addError.value = '';
  addNotice.value = '';
  addKey = '';
  addFingerprint = '';
}
watch([selectedId, resetRevision, sessionIdentity], closeAdd, { flush: 'sync' });
onUnmounted(() => {
  alive = false;
  invalidateSubmit();
  closeFacts();
  closeAdd();
});

function validateAdd(): string {
  const form = addForm.value;
  if (!form.credential_type.trim() || form.credential_type.trim().length > 40) return '资格类型必须为 1–40 个字符。';
  if (!form.certificate_no.trim() || form.certificate_no.trim().length > 80) return '证件编号必须为 1–80 个字符。';
  if (form.valid_from && !validDate(form.valid_from)) return '资格起始日期无效。';
  if (form.valid_to && !validDate(form.valid_to)) return '资格截止日期无效。';
  if (form.valid_from && form.valid_to && form.valid_to < form.valid_from) return '截止日期不能早于起始日期。';
  if (form.no_expiry_confirmed && form.valid_to) return '确认无期限时不能填写截止日期。';
  return '';
}

async function submitAdd(): Promise<void> {
  if (!addOpen.value || addBusy.value || !ready.value || !detail.value) return;
  const validation = validateAdd();
  if (validation) { addError.value = validation; return; }
  const staffId = detail.value.staff_id;
  const input: NewCredentialInput = {
    credential_type: addForm.value.credential_type.trim(),
    certificate_no: addForm.value.certificate_no.trim(),
    no_expiry_confirmed: addForm.value.no_expiry_confirmed,
  };
  if (addForm.value.valid_from) input.valid_from = addForm.value.valid_from;
  if (addForm.value.valid_to) input.valid_to = addForm.value.valid_to;
  const fingerprint = JSON.stringify([staffId, input]);
  if (addFingerprint !== fingerprint) {
    addFingerprint = fingerprint;
    addKey = newIdempotencyKey();
  }
  const key = addKey;
  const who = sessionIdentity();
  const reset = resetRevision.value;
  const sequence = ++addSequence;
  const current = () => alive && addOpen.value && sequence === addSequence
    && who === sessionIdentity() && reset === resetRevision.value && selectedId.value === staffId;
  addBusy.value = true;
  addError.value = '';
  addNotice.value = '';
  try {
    await credentialApi.add(staffId, input, key);
    if (!current()) return;
    addForm.value = emptyCredential();
    addKey = '';
    addFingerprint = '';
    addNotice.value = '资格已录入为待审核，正在刷新摘要；录入不等于核验通过。';
    const refreshed = await model.select(staffId);
    if (!current()) return;
    addNotice.value = refreshed ? '资格已录入为待审核，摘要已刷新。' : '资格已录入；摘要刷新未完成，请重新选择员工核对。';
  } catch (error) {
    if (!current()) return;
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      addError.value = '当前身份无权录入资格，请重新核对登录和组织负责人权限。';
      if (error.status === 401) await session.fetchMe();
    } else if (error instanceof ApiError && (error.code === 'INVALID_RESPONSE'
      || (error.status === 0 && error.code !== 'VALIDATION_FAILED') || error.status >= 500)) {
      addError.value = '结果尚未确认；请先核对资格摘要。原内容与幂等键已保留，重试不会自动进行。';
    } else {
      addError.value = error instanceof ApiError ? error.message : '结果尚未确认；请先核对资格摘要。';
    }
  } finally {
    if (current()) addBusy.value = false;
  }
}

async function openFacts(item: CredentialSummary): Promise<void> {
  if (!detail.value || !ready.value || factsLoading.value || reviewBusy.value) return;
  closeFacts();
  const staffId = detail.value.staff_id;
  const who = sessionIdentity();
  const reset = resetRevision.value;
  const sequence = ++factsSequence;
  factsLoading.value = true;
  const current = () => alive && sequence === factsSequence && who === sessionIdentity()
    && reset === resetRevision.value && selectedId.value === staffId;
  try {
    const facts = await credentialApi.facts(staffId, item.credential_id);
    if (!current()) return;
    reviewFacts.value = facts;
    reviewDecision.value = facts.verification_state === 'verified' ? 'revoke' : 'verified';
  } catch (error) {
    if (!current()) return;
    reviewError.value = error instanceof ApiError
      ? error.status === 403 ? '当前账号无权查看证件事实。'
        : error.status === 401 ? '登录状态已变化，请重新登录后再查看。' : error.message
      : '资格资料读取失败，请重试。';
    if (error instanceof ApiError && error.status === 401) await session.fetchMe();
  } finally {
    if (current()) factsLoading.value = false;
  }
}

async function loadHistory(cursor: string | null): Promise<void> {
  if (!historyCredentialId.value || !detail.value || historyLoading.value || !ready.value) return;
  const staffId = detail.value.staff_id;
  const credentialId = historyCredentialId.value;
  const who = sessionIdentity();
  const reset = resetRevision.value;
  const sequence = ++historySequence;
  const current = () => alive && sequence === historySequence && who === sessionIdentity()
    && reset === resetRevision.value && selectedId.value === staffId
    && historyCredentialId.value === credentialId;
  historyLoading.value = true;
  historyError.value = '';
  try {
    const page = await credentialApi.reviews(staffId, credentialId, cursor);
    if (!current()) return;
    historyItems.value = cursor === null ? page.items : [...historyItems.value, ...page.items];
    historyCursor.value = page.next_cursor;
  } catch (error) {
    if (!current()) return;
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      historyItems.value = [];
      historyCursor.value = null;
      historyError.value = '当前账号无权读取资格审核历史。';
      if (error.status === 401) await session.fetchMe();
    } else {
      historyError.value = error instanceof ApiError ? error.message : '审核历史读取失败，请重试。';
    }
  } finally {
    if (current()) historyLoading.value = false;
  }
}

async function openHistory(item: CredentialSummary): Promise<void> {
  if (!detail.value || !ready.value || reviewBusy.value) return;
  closeFacts();
  historyCredentialId.value = item.credential_id;
  await loadHistory(null);
}

function historyAction(action: CredentialReviewItem['action']): string {
  return { submit: '提交待审核', verify: '核验通过', reject: '首次驳回', revoke: '撤销资格' }[action];
}

async function submitReview(): Promise<void> {
  const facts = reviewFacts.value;
  if (!facts || reviewBusy.value || !ready.value || !detail.value) return;
  const basis = reviewBasis.value.trim();
  const decision = reviewDecision.value;
  if (!basis || basis.length > 300) {
    reviewError.value = '请填写 1–300 字的实际核验或撤销依据。';
    return;
  }
  const fingerprint = JSON.stringify([facts.staff_id, facts.credential_id, facts.version, decision, basis]);
  if (reviewFingerprint !== fingerprint) {
    reviewFingerprint = fingerprint;
    reviewKey = newIdempotencyKey();
  }
  const key = reviewKey;
  const who = sessionIdentity();
  const reset = resetRevision.value;
  const sequence = ++reviewSequence;
  const current = () => alive && sequence === reviewSequence && who === sessionIdentity()
    && reset === resetRevision.value && selectedId.value === facts.staff_id;
  reviewBusy.value = true;
  reviewError.value = '';
  reviewNotice.value = '';
  try {
    await credentialApi.decide(facts, decision, basis, key);
    if (!current()) return;
    reviewFacts.value = null;
    reviewKey = '';
    reviewFingerprint = '';
    reviewNotice.value = '操作已确认，正在刷新资格摘要。';
    const refreshed = await model.select(facts.staff_id);
    if (!current()) return;
    reviewNotice.value = refreshed ? '资格状态已更新。' : '操作已确认；摘要刷新未完成，请重新选择员工。';
  } catch (error) {
    if (!current()) return;
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      reviewFacts.value = null;
      reviewError.value = '当前身份无权继续操作，请重新登录或切换有权限的账号。';
      if (error.status === 401) await session.fetchMe();
    } else if (error instanceof ApiError && error.status === 409) {
      reviewFacts.value = null;
      reviewKey = '';
      reviewFingerprint = '';
      reviewError.value = '资格版本或状态已变化，请重新读取证件事实后再决定。';
      await model.select(facts.staff_id);
    } else {
      reviewError.value = error instanceof ApiError && (error.status === 0 || error.status >= 500)
        ? '结果尚未确认。请保持原表单内容并用同一操作重试，系统不会自动重复提交。'
        : error instanceof ApiError ? error.message : '结果尚未确认，请稍后重试。';
    }
  } finally {
    if (current()) reviewBusy.value = false;
  }
}

function text(value: string | null | undefined): string {
  return value === null || value === undefined || value === '' ? '未填写' : value;
}
function dateText(value: string | null | undefined): string {
  return value === null || value === undefined || value === '' ? '未填写' : value;
}
function credentialState(value: string): string {
  return value === 'pending' ? '待审核' : value === 'verified' ? '已核验' : value === 'rejected' ? '未通过/已撤销' : value;
}
function effectiveText(value: boolean): string {
  return value ? '资格自身当前有效' : '资格自身当前无效';
}
function validityText(item: CredentialSummary): string {
  const from = item.valid_from ? `自 ${item.valid_from}` : '起始日期未填写';
  const to = item.no_expiry_confirmed ? '已确认无期限' : item.valid_to ? `至 ${item.valid_to}` : '截止日期未填写（不能推断有效）';
  return `${from}；${to}`;
}
function branchState(item: StaffBranch): string {
  return `${item.state}${item.enabled ? ' · 任职启用' : ' · 任职停用'}`;
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  if (!Number.isFinite(date.getTime())) return false;
  return date.toISOString().slice(0, 10) === value;
}

function validateForm(): string {
  if (!form.value.name.trim() || form.value.name.trim().length > 60) return '姓名必须为 1-60 个字符。';
  if (!form.value.job_title.trim() || form.value.job_title.trim().length > 60) return '岗位必须为 1-60 个字符。';
  if (form.value.staff_type === '牙医' && !form.value.practice_no.trim()) return '牙医必须填写执业编号。';
  if (form.value.practice_no.trim().length > 60) return '执业编号不能超过 60 个字符。';
  if (!credentialEnabled.value) return '';
  if (!credential.value.credential_type.trim() || credential.value.credential_type.trim().length > 40) return '资格类型必须为 1-40 个字符。';
  if (!credential.value.certificate_no.trim() || credential.value.certificate_no.trim().length > 80) return '资格证件编号必须为 1-80 个字符。';
  if (credential.value.valid_from && !validDate(credential.value.valid_from)) return '资格起始日期无效。';
  if (credential.value.valid_to && !validDate(credential.value.valid_to)) return '资格截止日期无效。';
  if (credential.value.valid_from && credential.value.valid_to && credential.value.valid_to < credential.value.valid_from) return '截止日期不能早于起始日期。';
  if (credential.value.no_expiry_confirmed && credential.value.valid_to) return '确认无期限时不能填写截止日期。';
  return '';
}

function buildInput(): StaffCreateInput {
  const input: StaffCreateInput = {
    name: form.value.name.trim(),
    staff_type: form.value.staff_type,
    job_title: form.value.job_title.trim(),
  };
  const practiceNo = form.value.practice_no.trim();
  if (practiceNo) input.practice_no = practiceNo;
  if (credentialEnabled.value) {
    input.credential = {
      credential_type: credential.value.credential_type.trim(),
      certificate_no: credential.value.certificate_no.trim(),
      no_expiry_confirmed: credential.value.no_expiry_confirmed,
    };
    if (credential.value.valid_from) input.credential.valid_from = credential.value.valid_from;
    if (credential.value.valid_to) input.credential.valid_to = credential.value.valid_to;
  }
  return input;
}

async function submit(): Promise<void> {
  if (disabled.value) return;
  const validation = validateForm();
  if (validation) { localError.value = validation; return; }
  const context: SubmitContext = {
    id: ++submitSequence,
    session: sessionIdentity(),
    resetRevision: model.resetRevision.value,
    draftRevision: draftRevision.value,
    selectionRevision: selectionRevision.value,
  };
  const submittedInput = buildInput();
  activeSubmit = context;
  localError.value = '';
  submitting.value = true;
  try {
    const outcome = await model.create(submittedInput);
    // Every await boundary rechecks the operation/session/reset, not a stale boolean.
    if (!isCurrent(context)) return;
    const draftUnchanged = draftRevision.value === context.draftRevision;
    if (!outcome.applied) {
      if (outcome.status === 'stale' || !draftUnchanged) return;
      localError.value = outcome.message;
      return;
    }
    if (!draftUnchanged) return;
    clearFields();
    if (!outcome.staff) return;

    const refreshed = await model.refresh();
    if (!isCurrent(context)) return;
    const stillSameDraft = draftRevision.value === context.draftRevision; // clearFields is suppressed
    const stillSameSelection = selectionRevision.value === context.selectionRevision;
    if (!refreshed) {
      message.value = '建档已确认；员工列表刷新未完成，请稍后手动刷新。';
      return;
    }
    if (!stillSameDraft || !stillSameSelection) return;
    await model.select(outcome.staff.staff_id);
  } finally {
    // An invalidated/old callback must not release a newer operation's busy state.
    if (alive && activeSubmit?.id === context.id) {
      activeSubmit = null;
      submitting.value = false;
    }
  }
}

function resetDraft(): void {
  if (disabled.value) return;
  resetForm();
}
function choose(id: string): void {
  if (listLoading.value || detailLoading.value) return;
  selectionRevision.value++;
  void model.select(id);
}
function loadMore(): void {
  if (listLoading.value || !nextCursor.value) return;
  void model.loadMore();
}
onMounted(() => {
  if (ready.value) void model.load(true);
});
watch(ready, (value) => {
  if (value && rows.value.length === 0 && !listLoading.value) void model.load(true);
});
</script>

<template>
  <main class="staff-page">
    <header class="topbar">
      <div>
        <RouterLink to="/" class="back">← 返回首页</RouterLink>
        <h1>员工管理</h1>
      </div>
      <span class="identity">{{ session.me?.name || '身份未确认' }}</span>
    </header>

    <p v-if="!ready" class="notice">请先确认登录身份；身份变化会清空当前员工数据。</p>

    <div v-else class="layout">
      <section class="panel list-panel">
        <div class="panel-head">
          <div>
            <h2>员工列表</h2>
            <p class="muted">按当前账号可见范围读取；跨店任职只显示一次。</p>
          </div>
          <button type="button" :disabled="listLoading" @click="model.refresh()">{{ listLoading ? '刷新中…' : '刷新' }}</button>
        </div>
        <p v-if="listError" class="error" role="alert">{{ listError }}</p>
        <p v-if="listLoading && rows.length === 0" class="muted">正在加载员工…</p>
        <p v-else-if="rows.length === 0 && !listError" class="muted">当前没有可见员工。</p>
        <ul v-else class="staff-list">
          <li v-for="row in rows" :key="row.staff_id">
            <button type="button" :class="{ selected: selectedId === row.staff_id }" :disabled="listLoading || detailLoading" @click="choose(row.staff_id)">
              <span class="name">{{ row.name }}</span>
              <span class="meta">{{ row.staff_type }} · {{ row.job_title }} · {{ row.employment_state }} · v{{ row.version }}</span>
              <span class="id">编号 {{ row.staff_id }}</span>
            </button>
          </li>
        </ul>
        <button v-if="nextCursor" type="button" class="more" :disabled="listLoading" @click="loadMore">
          {{ listLoading ? '加载中…' : '加载更多' }}
        </button>
      </section>

      <section class="panel detail-panel">
        <h2>员工详情</h2>
        <p v-if="detailError" class="error" role="alert">{{ detailError }}</p>
        <p v-if="detailLoading" class="muted">正在读取详情…</p>
        <p v-else-if="!detail" class="muted">从左侧选择一名员工查看最小详情。</p>
        <template v-else>
          <dl class="facts">
            <dt>姓名</dt><dd>{{ text(detail.name) }}</dd>
            <dt>人员类型</dt><dd>{{ detail.staff_type }}</dd>
            <dt>岗位</dt><dd>{{ text(detail.job_title) }}</dd>
            <dt>执业编号</dt><dd>{{ text(detail.practice_no) }}</dd>
            <dt>在岗状态</dt><dd>{{ detail.employment_state }}</dd>
            <dt>主档版本</dt><dd>{{ detail.version }}</dd>
          </dl>

          <h3>可见门店任职</h3>
          <p v-if="detail.branches.length === 0" class="muted">当前没有可见门店任职。</p>
          <ul v-else class="relation-list">
            <li v-for="branch in detail.branches" :key="branch.branch_id">
              <strong>{{ branch.name }}</strong>
              <span>{{ branchState(branch) }}</span>
              <span class="muted">核验时间：{{ dateText(branch.verified_at) }}</span>
            </li>
          </ul>

          <h3>资格摘要</h3>
          <p class="muted">普通详情只展示资格摘要；组织负责人可单独查看证件事实并留下访问审计。</p>
          <button v-if="canCreate" type="button" :disabled="addBusy" @click="addOpen ? closeAdd() : (addOpen = true)">
            {{ addOpen ? '关闭追加资格' : '追加待核验资格' }}
          </button>
          <form v-if="canCreate && addOpen" class="review-panel" @submit.prevent="submitAdd">
            <h3>为当前员工录入资格</h3>
            <p class="muted">只建立待审核记录；需另行读取证件事实并核验，不能据此接诊。</p>
            <fieldset class="form-fields" :disabled="addBusy">
              <label>资格类型<input v-model="addForm.credential_type" maxlength="40" required autocomplete="off" /></label>
              <label>证件编号<input v-model="addForm.certificate_no" maxlength="80" required autocomplete="off" /></label>
              <label>起始日期<input v-model="addForm.valid_from" type="date" /></label>
              <label>截止日期<input v-model="addForm.valid_to" type="date" :disabled="addForm.no_expiry_confirmed" /></label>
              <label class="check"><input v-model="addForm.no_expiry_confirmed" type="checkbox" /> 已确认无固定有效期</label>
              <p v-if="addError" class="error" role="alert">{{ addError }}</p>
              <p v-if="addNotice" class="notice" role="status">{{ addNotice }}</p>
              <button type="submit">{{ addBusy ? '录入中…' : '录入待审核资格' }}</button>
            </fieldset>
          </form>
          <p v-if="detail.credentials.length === 0" class="muted">暂无资格记录。</p>
          <ul v-else class="credential-list">
            <li v-for="item in detail.credentials" :key="item.credential_id">
              <div class="credential-head">
                <strong>{{ item.credential_type }}</strong>
                <span class="badge">{{ credentialState(item.verification_state) }}</span>
                <span class="badge" :class="{ good: item.effective }">{{ effectiveText(item.effective) }}</span>
              </div>
              <p>{{ validityText(item) }} · 资格版本 v{{ item.version }}</p>
              <p class="muted">{{ item.effective ? '仅表示该资格自身审核状态与日期有效，不代表可以接诊或已有排班授权。' : '资格当前不可按有效使用；仍需结合类别、在岗、任职、项目和时段判断。' }}</p>
              <div v-if="canCreate" class="credential-actions">
                <button type="button" :disabled="factsLoading || reviewBusy" @click="openFacts(item)">查看核验资料</button>
                <button type="button" :disabled="historyLoading || reviewBusy" @click="openHistory(item)">查看审核历史</button>
              </div>
            </li>
          </ul>
          <div v-if="canCreate && (reviewFacts || factsLoading || reviewError || reviewNotice)" class="review-panel">
            <div class="credential-head"><h3>资格核验</h3><button type="button" @click="closeFacts">关闭并清除资料</button></div>
            <p v-if="factsLoading" class="muted">正在受控读取证件事实…</p>
            <p v-if="reviewError" class="error" role="alert">{{ reviewError }}</p>
            <p v-if="reviewNotice" class="notice" role="status">{{ reviewNotice }}</p>
            <template v-if="reviewFacts">
              <dl class="facts">
                <dt>资格类别</dt><dd>{{ reviewFacts.credential_type }}</dd>
                <dt>证书编号</dt><dd>{{ reviewFacts.certificate_no }}</dd>
                <dt>起始日期</dt><dd>{{ dateText(reviewFacts.valid_from) }}</dd>
                <dt>截止日期</dt><dd>{{ reviewFacts.no_expiry_confirmed ? '已确认无期限' : dateText(reviewFacts.valid_to) }}</dd>
                <dt>当前状态</dt><dd>{{ credentialState(reviewFacts.verification_state) }} · v{{ reviewFacts.version }}</dd>
              </dl>
              <p class="warning">请对照实际证件和核验材料。页面读取不代表证件真实，审核依据不得填写证件号码等敏感原文。</p>
              <div v-if="reviewFacts.verification_state !== 'rejected'" class="review-form">
                <label>处理决定
                  <select v-model="reviewDecision" :disabled="reviewBusy">
                    <template v-if="reviewFacts.verification_state === 'pending'">
                      <option value="verified">核验通过</option>
                      <option value="rejected">审核不通过</option>
                    </template>
                    <option v-else value="revoke">撤销已核验资格</option>
                  </select>
                </label>
                <label>实际核验或撤销依据<textarea v-model="reviewBasis" maxlength="300" :disabled="reviewBusy" /></label>
                <button type="button" :disabled="reviewBusy" @click="submitReview">{{ reviewBusy ? '提交中…' : '确认处理' }}</button>
              </div>
            </template>
          </div>
          <div v-if="canCreate && historyCredentialId" class="review-panel history-panel">
            <div class="credential-head"><h3>资格审核历史</h3><button type="button" @click="closeFacts">关闭历史</button></div>
            <p class="muted">按审核记录编号读取；依据只供授权负责人核对，不包含证书编号。迁移前记录不会补造历史。</p>
            <p v-if="historyError" class="error" role="alert">{{ historyError }}</p>
            <p v-if="historyLoading && historyItems.length === 0" class="muted">正在读取历史…</p>
            <p v-if="!historyLoading && historyItems.length === 0 && !historyError" class="muted">暂无审核历史。</p>
            <ol v-if="historyItems.length" class="history-list">
              <li v-for="item in historyItems" :key="item.review_id">
                <strong>{{ historyAction(item.action) }}</strong> · v{{ item.resulting_version }} · {{ item.occurred_at }}
                <p>执行人编号 {{ item.actor_staff_id }} · 依据：{{ item.basis || '未填写' }}</p>
              </li>
            </ol>
            <button v-if="historyCursor" type="button" :disabled="historyLoading" @click="loadHistory(historyCursor)">
              {{ historyLoading ? '正在读取…' : '加载更多历史' }}
            </button>
            <button v-if="historyError" type="button" :disabled="historyLoading" @click="loadHistory(historyItems.length ? historyCursor : null)">重试读取</button>
          </div>
          <p v-if="detail.credentials.some(item => item.verification_state === 'rejected')" class="muted">
            摘要中的 rejected 可能来自首次驳回或后续撤销，不能据此推断原因；请查看受控审核历史区分。
          </p>
        </template>
      </section>

      <section v-if="canCreate" class="panel create-panel">
        <h2>新建员工档案</h2>
        <p class="warning">owner 角色不等于组织负责人；最终是否可建档由服务端判定。建档不会创建登录账号、分配角色或建立门店任职，录入资格也不代表审核通过。</p>
        <form @submit.prevent="submit">
          <fieldset class="form-fields" :disabled="disabled">
          <label>姓名<input v-model="form.name" maxlength="60" required autocomplete="off" /></label>
          <label>人员类型
            <select v-model="form.staff_type">
              <option value="其他人员">其他人员</option>
              <option value="牙医">牙医</option>
            </select>
          </label>
          <label>岗位<input v-model="form.job_title" maxlength="60" required autocomplete="off" /></label>
          <label>执业编号
            <input v-model="form.practice_no" maxlength="60" :required="form.staff_type === '牙医'" autocomplete="off" />
            <span class="muted">{{ form.staff_type === '牙医' ? '牙医必填，且必须唯一。' : '其他人员可留空。' }}</span>
          </label>

          <label class="check"><input v-model="credentialEnabled" type="checkbox" /> 同时录入一份待核验资格</label>
          <fieldset v-if="credentialEnabled" class="credential-form">
            <label>资格类型<input v-model="credential.credential_type" maxlength="40" required autocomplete="off" /></label>
            <label>证件编号<input v-model="credential.certificate_no" maxlength="80" required autocomplete="off" /></label>
            <label>起始日期<input v-model="credential.valid_from" type="date" /></label>
            <label>截止日期<input v-model="credential.valid_to" type="date" :disabled="credential.no_expiry_confirmed" /></label>
            <label class="check"><input v-model="credential.no_expiry_confirmed" type="checkbox" /> 已确认无固定有效期（截止日期必须留空）</label>
            <p class="muted">资格将以 pending 状态录入；不会自动变为 verified。</p>
          </fieldset>

          <p v-if="localError" class="error" role="alert">{{ localError }}</p>
          <p v-if="createError" class="error" role="alert">{{ createError }}</p>
          <p v-if="message" class="notice" role="status">{{ message }}</p>
          <div class="actions">
            <button type="submit" :disabled="disabled">{{ disabled ? '提交中…' : '提交建档' }}</button>
            <button type="button" :disabled="disabled" @click="resetDraft">清空草稿</button>
          </div>
          </fieldset>
        </form>
      </section>
      <section v-else class="panel create-panel">
        <h2>新建员工档案</h2>
        <p class="muted">当前账号没有 owner 角色；建档入口仅向 owner 展示，最终仍由服务端校验组织负责人权限。</p>
      </section>
    </div>
  </main>
</template>

<style scoped>
.staff-page { min-height: 100vh; background: #f4f8f6; color: #1f3b3a; }
.topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 24px; border-bottom: 1px solid #dbe8e4; background: #fff; }
.topbar h1 { margin: 6px 0 0; font-size: 22px; }
.back { color: #2f6f65; text-decoration: none; font-size: 14px; }
.identity { color: #5a7776; font-size: 14px; }
.layout { display: grid; grid-template-columns: minmax(260px, 0.9fr) minmax(320px, 1.2fr) minmax(300px, 0.9fr); gap: 18px; padding: 20px; align-items: start; }
.panel { background: #fff; border: 1px solid #dbe8e4; border-radius: 10px; padding: 18px; min-width: 0; }
.panel-head { display: flex; justify-content: space-between; gap: 10px; align-items: start; }
h2 { margin: 0 0 8px; font-size: 17px; }
h3 { margin: 18px 0 8px; font-size: 15px; }
.muted { color: #6a8480; font-size: 13px; line-height: 1.5; }
.warning { color: #8a5a10; background: #fff8e8; padding: 10px; border-radius: 7px; font-size: 13px; line-height: 1.5; }
.notice { color: #1f5d4f; background: #e6f5ee; padding: 10px; border-radius: 7px; font-size: 13px; }
.error { color: #9b382b; background: #fff0eb; padding: 10px; border-radius: 7px; font-size: 13px; }
button, input, select { font: inherit; }
button { border: 1px solid #b9d3cd; background: #fff; color: #1f3b3a; border-radius: 6px; padding: 7px 10px; cursor: pointer; }
button:disabled { opacity: .55; cursor: not-allowed; }
.staff-list, .relation-list, .credential-list { list-style: none; margin: 0; padding: 0; }
.staff-list li { border-bottom: 1px solid #edf2f0; }
.staff-list button { width: 100%; text-align: left; border: 0; border-radius: 0; padding: 12px 8px; display: grid; gap: 4px; }
.staff-list button.selected { background: #eaf6f1; }
.name { font-weight: 600; }
.meta, .id { color: #5a7776; font-size: 12px; }
.more { margin-top: 12px; width: 100%; }
.facts { display: grid; grid-template-columns: 92px 1fr; gap: 8px 10px; margin: 0; font-size: 14px; }
.facts dt { color: #6a8480; }
.facts dd { margin: 0; overflow-wrap: anywhere; }
.relation-list li, .credential-list li { border-top: 1px solid #edf2f0; padding: 10px 0; display: grid; gap: 4px; font-size: 13px; }
.credential-head { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.credential-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.history-list { padding-left: 20px; }
.history-list li { padding: 8px 0; border-top: 1px solid #edf2f0; }
.history-list p { margin: 4px 0 0; overflow-wrap: anywhere; }
.badge { font-size: 12px; border-radius: 999px; padding: 2px 7px; background: #eef3f2; color: #526b68; }
.badge.good { background: #dff3e9; color: #216a52; }
.credential-list p { margin: 0; line-height: 1.5; overflow-wrap: anywhere; }
.review-panel { border: 1px solid #b9d3cd; border-radius: 8px; background: #f7fbf9; padding: 12px; margin-top: 12px; display: grid; gap: 10px; }
.review-panel h3 { margin: 0; }
.review-form { display: grid; gap: 10px; }
.review-form textarea { border: 1px solid #c9dcd7; border-radius: 6px; padding: 8px; min-height: 72px; font: inherit; }
form { display: grid; gap: 12px; }
label { display: grid; gap: 5px; font-size: 13px; color: #345451; }
input:not([type=checkbox]), select { border: 1px solid #c9dcd7; border-radius: 6px; padding: 8px; background: #fff; min-width: 0; }
.check { display: flex; align-items: flex-start; gap: 8px; }
.check input { margin-top: 3px; }
fieldset { border: 1px solid #dbe8e4; border-radius: 8px; padding: 12px; display: grid; gap: 10px; }
fieldset.form-fields { border: 0; padding: 0; margin: 0; display: grid; gap: 12px; min-width: 0; }
.actions { display: flex; gap: 8px; }
@media (max-width: 1100px) { .layout { grid-template-columns: 1fr 1fr; } .create-panel { grid-column: 1 / -1; } }
@media (max-width: 720px) { .layout { grid-template-columns: 1fr; padding: 12px; } .topbar { padding: 14px; } }
</style>
