<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { createHttpClient, ApiError } from '@/services/http';
import { useSessionStore } from '@/stores/session';
import { createAppointmentApi, type AppointmentChange, type AppointmentRow, type BookingOverview, type ContactTodo, type ManualContact, type PatientCandidate, type PatientSearchResult, type PatientSummary, type QueueTicket, type ReceptionSummary } from '@/services/appointments';

const session = useSessionStore();
const api = createAppointmentApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeoutMs: 30000,
  getCsrfToken: () => session.csrfToken,
}));
const data = ref<BookingOverview | null>(null);
const loading = ref(false);
const error = ref('');
const message = ref('');
const form = ref({ patientId: '', scheduleId: '', itemId: '', time: '09:00', visitType: '初诊', channel: '电话', purpose: '前台代约', consentBasis: '' });
const dateFilter = ref('');
const lookup = ref({ name: '', phone: '', method: 'visit_evidence' as 'visit_evidence' | 'id_document', inPerson: false, resolution: 'single_exact', allergyStatus: '未申报', allergyNotes: '', birthDate: '', sourceChannel: '' });
const patientSummary = ref<PatientSummary | null>(null);
const patientSummaryError = ref('');
const searchResult = ref<PatientSearchResult | null>(null);
const selectedPatient = ref<{ id: string; label: string; token: string; expiresAt: string } | null>(null);
const selectedCandidate = ref<PatientCandidate | null>(null);
const createPatientKey = ref('');
let overviewRequest = 0;
let lookupRequest = 0;
let receptionOperation = 0;
let viewActive = true;
function current(revision: number): boolean { return viewActive && revision === session.sessionRevision; }
watch(() => session.sessionRevision, () => {
  overviewRequest++; lookupRequest++; receptionOperation++;
  data.value = null; searchResult.value = null; selectedPatient.value = null; selectedCandidate.value = null; patientSummary.value = null; patientSummaryError.value = '';
  cardRequest++; cardTarget.value = null; cardSummary.value = null; cardError.value = ''; cardLoading.value = false;
  selectionBranch.value = ''; createPatientKey.value = '';
  form.value = { patientId: '', scheduleId: '', itemId: '', time: '09:00', visitType: '初诊', channel: '电话', purpose: '前台代约', consentBasis: '' };
  lookup.value = { name: '', phone: '', method: 'visit_evidence', inPerson: false, resolution: 'single_exact', allergyStatus: '未申报', allergyNotes: '', birthDate: '', sourceChannel: '' };
  queueRequest++; queueTickets.value = []; queueBranch.value = ''; queueLoading.value = false;
  contactRequest++; contactTarget.value = null; contactHistory.value = []; contactBusy.value = false;
  contactTodoRequest++; contactTodos.value = []; contactTodoCursor.value = null; contactTodoBusy.value = false;
  checkinTarget.value = null; codeRequest++; checkinCodeUrl.value = ''; checkinCodeExpires.value = ''; showCheckinCode.value = false;
  loading.value = false; error.value = ''; message.value = '';
});
const checkinTarget = ref<AppointmentRow | null>(null);
const contactTarget = ref<AppointmentRow | null>(null);
const cardTarget = ref<AppointmentRow | null>(null);
const cardSummary = ref<ReceptionSummary | null>(null);
const cardLoading = ref(false);
const cardError = ref('');
let cardRequest = 0;
const contactChannel = ref<'电话' | '微信登记' | '当面'>('电话');
const contactResult = ref('');
const contactAcknowledgment = ref('');
const contactNext = ref('');
const contactHistory = ref<ManualContact[]>([]);
const contactTodos = ref<ContactTodo[]>([]);
const contactTodoCursor = ref<string | null>(null);
const contactTodoBusy = ref(false);
const contactTodoError = ref('');
let contactTodoRequest = 0;
const contactBusy = ref(false);
const contactError = ref('');
const changeTarget = ref<AppointmentRow | null>(null);
const changeReason = ref('');
const changeScheduleId = ref('');
const changeItemId = ref('');
const changeStart = ref('');
const changeBasis = ref('');
const changeWithdrawReason = ref('');
const changes = ref<AppointmentChange[]>([]);
const changeBusy = ref(false);
const changeError = ref('');
let changeRequest = 0;
watch(() => session.sessionRevision, () => {
  changeRequest++; changes.value=[]; changeTarget.value=null; changeBasis.value=''; changeWithdrawReason.value='';
  changeError.value='';
});
let contactRequest = 0;
const checkinBasis = ref('');
const checkinException = ref('');
const queueBranch = ref('');
const queueDate = ref(shanghaiDate(new Date().toISOString()));
const queueTickets = ref<QueueTicket[]>([]);
const queueLoading = ref(false);
const queueError = ref('');
const checkinCodeUrl = ref('');
const checkinCodeExpires = ref('');
const checkinCodeError = ref('');
const issuingCheckinCode = ref(false);
const showCheckinCode = ref(false);
const wallClock = ref(Date.now());
const codeSeconds = computed(() => Math.max(0, Math.ceil((Date.parse(checkinCodeExpires.value) - wallClock.value) / 1000)));
let queueRequest = 0;
let codeRequest = 0;
let countdownTimer: ReturnType<typeof setInterval> | undefined;

const patientCount = computed(() => data.value?.patient_count ?? 0);
const schedules = computed(() => data.value?.schedules ?? []);
const services = computed(() => data.value?.services ?? []);
const appointments = computed(() => (data.value?.appointments ?? []).filter((a) => !dateFilter.value || shanghaiDate(a.starts_at) === dateFilter.value));
const selectedSchedule = computed(() => schedules.value.find((s) => s.schedule_id === form.value.scheduleId));
const selectedBranchId = computed(() => selectedSchedule.value?.branch_id ?? '');
const selectedService = computed(() => services.value.find((s) => s.item_id === form.value.itemId));

function shanghaiParts(value: string): { date: string; time: string } {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).formatToParts(new Date(value));
  const map = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  return { date: `${map.year}-${map.month}-${map.day}`, time: `${map.hour === '24' ? '00' : map.hour}:${map.minute}` };
}
function shanghaiDate(value: string): string { return shanghaiParts(value).date; }
const todayShanghai = computed(() => shanghaiDate(new Date(wallClock.value).toISOString()));
function displayTime(value: string): string { return `${shanghaiDate(value)} ${shanghaiParts(value).time}`; }
const startOptions = computed(() => {
  const schedule = selectedSchedule.value;
  const service = selectedService.value;
  if (!schedule || !service) return [];
  const available = (data.value?.availability ?? []).find((a) => a.schedule_id === schedule.schedule_id && a.item_id === service.item_id);
  return (available?.slots ?? []).map((slot) => ({ value: slot, label: shanghaiParts(slot).time }));
});
const changeSchedules = computed(() => schedules.value.filter(s => s.branch_id === changeTarget.value?.branch_id
  && (data.value?.availability ?? []).some(a => a.schedule_id === s.schedule_id
    && a.item_id === changeTarget.value?.item_id && a.slots.length > 0)));
const changeSlots = computed(() => (data.value?.availability ?? []).find(a =>
  a.schedule_id === changeScheduleId.value && a.item_id === changeItemId.value)?.slots ?? []);
function updateChangeSlots(): void { changeStart.value = changeSlots.value[0] ?? ''; }
function openChange(row: AppointmentRow): void {
  changeTarget.value=row; changeReason.value=''; changeBasis.value=''; changeError.value='';
  changeScheduleId.value=changeSchedules.value[0]?.schedule_id ?? '';
  changeItemId.value=row.item_id ?? ''; updateChangeSlots();
}
function closeChange(): void { if(changeBusy.value) return; changeTarget.value=null; changeError.value=''; }
async function loadChanges(): Promise<void> {
  const branch=queueBranch.value; const request=++changeRequest;
  const revision=session.sessionRevision;
  if(!branch) { changes.value=[]; return; }
  try { const result=await api.changes(branch); if(request===changeRequest && branch===queueBranch.value && revision===session.sessionRevision) changes.value=result; }
  catch(e) { if(request===changeRequest && revision===session.sessionRevision) { changes.value=[]; changeError.value=e instanceof ApiError?e.message:'协调方案读取失败。'; } }
}
async function proposeChange(): Promise<void> {
  const target=changeTarget.value;
  if(!target || changeBusy.value) return;
  const schedule=changeSchedules.value.find(s=>s.schedule_id===changeScheduleId.value);
  const policy=data.value?.policies.find(p=>p.branch_id===schedule?.branch_id);
  if(!schedule || !policy || !target.item_id || changeItemId.value !== target.item_id || !changeStart.value || !changeReason.value.trim()) {
    changeError.value='请填写原因并选择可接诊原项目的当班医生和候选时段。'; return;
  }
  changeBusy.value=true; changeError.value='';
  try {
    await api.proposeChange(target.appointment_id,{scheduleId:schedule.schedule_id,itemId:changeItemId.value,
      startsAt:changeStart.value,policyId:policy.policy_id},changeReason.value);
    message.value='候选方案已记录；待确认不占号，须取得患者明确答复后再落实。';
    changeTarget.value=null; await loadChanges(); await load();
  } catch(e) { changeError.value=e instanceof ApiError?e.message:'方案结果不明；保留内容，刷新列表核对后再操作。'; }
  finally { changeBusy.value=false; }
}
async function respondChange(change: AppointmentChange,accept: boolean): Promise<void> {
  if(changeBusy.value || !changeBasis.value.trim()) { changeError.value='请先填写实际取得患者答复的依据。'; return; }
  changeBusy.value=true; changeError.value='';
  try {
    await api.answerChange(change.change_id,accept,change.version,changeBasis.value);
    changeBasis.value=''; await loadChanges();
    message.value=accept?'同意事实已记录；尚未占用新时段，请点击“落实”。':'拒绝事实已记录；原预约仍保留。';
  } catch(e) { changeError.value=e instanceof ApiError?e.message:'答复结果不明；请刷新核对，勿默认同意。'; }
  finally { changeBusy.value=false; }
}
async function applyChange(change: AppointmentChange): Promise<void> {
  if(changeBusy.value) return;
  changeBusy.value=true; changeError.value='';
  try {
    await api.applyChange(change.change_id,change.version);
    await loadChanges(); await load(); message.value='新时段已确认并占用，原预约已改约。';
  } catch(e) { changeError.value=e instanceof ApiError?e.message:'落实结果不明；请刷新核对原预约与新预约状态。'; await loadChanges(); }
  finally { changeBusy.value=false; }
}
async function withdrawChange(change: AppointmentChange): Promise<void> {
  const reason=changeWithdrawReason.value.trim();
  if(changeBusy.value || !reason) { changeError.value='请填写撤回原因。'; return; }
  changeBusy.value=true; changeError.value='';
  try {
    await api.withdrawChange(change.change_id,change.version,reason);
    changeWithdrawReason.value=''; await loadChanges(); await load();
    message.value='候选方案已撤回；原预约未改变。若患者此前已同意，请人工告知方案变化。';
  } catch(e) {
    changeError.value=e instanceof ApiError?e.message:'撤回结果不明；请刷新核对，勿重复发起新方案。';
    await loadChanges();
  } finally { changeBusy.value=false; }
}
function onSelectionChange(): void {
  form.value.time = startOptions.value[0]?.value ?? '';
  if (selectedBranchId.value !== selectionBranch.value) {
    lookupRequest++; receptionOperation++;
    clearSelection(); searchResult.value = null;
  }
}
const selectionBranch = ref('');
function clearSelection(): void { selectedPatient.value = null; selectedCandidate.value = null; patientSummary.value = null; patientSummaryError.value = ''; selectionBranch.value = ''; form.value.patientId = ''; }
async function loadPatientSummary(revision: number, operation: number, branch: string, patientId: string): Promise<void> {
  patientSummaryError.value = '';
  try {
    const summary = await api.patientSummary(branch, patientId);
    if (current(revision) && operation === receptionOperation && branch === selectedBranchId.value && selectedPatient.value?.id === patientId) patientSummary.value = summary;
  } catch {
    if (current(revision) && operation === receptionOperation && branch === selectedBranchId.value && selectedPatient.value?.id === patientId)
      patientSummaryError.value = '档案摘要暂未读到；可重试，已完成的线下核验仍有效。';
  }
}
function clearLookup(): void { lookupRequest++; receptionOperation++; loading.value = false; clearSelection(); searchResult.value = null; createPatientKey.value = ''; }
function verification() {
  return { branch_id: selectedBranchId.value, name: lookup.value.name.trim(), phone: lookup.value.phone.trim(), verification_method: lookup.value.method, verification_result: 'confirmed' as const, in_person: true as const };
}
async function searchPatient(): Promise<void> {
  const revision = session.sessionRevision;
  const request = ++lookupRequest;
  clearSelection(); searchResult.value = null; error.value = ''; message.value = '';
  if (!selectedBranchId.value || !lookup.value.name.trim() || !lookup.value.phone.trim()) { error.value = '先选择排班，再输入准确姓名和电话。'; return; }
  const branch = selectedBranchId.value;
  const name = lookup.value.name.trim();
  const phone = lookup.value.phone.trim();
  loading.value = true;
  try { const result = await api.searchPatient(branch, name, phone); if (current(revision) && request === lookupRequest && branch === selectedBranchId.value) searchResult.value = result; }
  catch (e) { if (current(revision) && request === lookupRequest && branch === selectedBranchId.value) error.value = e instanceof ApiError ? e.message : '用户检索失败。'; }
  finally { if (current(revision) && request === lookupRequest && branch === selectedBranchId.value) loading.value = false; }
}
async function selectPatient(candidate: PatientCandidate): Promise<void> {
  const revision = session.sessionRevision;
  const operation = ++receptionOperation;
  const branch = selectedBranchId.value;
  error.value = ''; message.value = '';
  if (!lookup.value.inPerson) { error.value = '请先完成线下身份核验并确认。'; return; }
  loading.value = true;
  try {
    const result = await api.selectPatient(candidate.patient_id, { ...verification(), resolution: lookup.value.resolution });
    if (!current(revision) || operation !== receptionOperation || branch !== selectedBranchId.value) return;
    if (!result.selection_token || !result.expires_at) throw new ApiError('INVALID_RESPONSE', 0, '未取得有效选择凭证，请重新核验。');
    selectedPatient.value = { id: candidate.patient_id, label: `${candidate.display_name} · 尾号${candidate.phone_suffix}`, token: result.selection_token, expiresAt: result.expires_at };
    selectionBranch.value = selectedBranchId.value; form.value.patientId = candidate.patient_id; selectedCandidate.value = candidate;
    message.value = '线下核验已记录，请在凭证有效期内提交预约。';
    await loadPatientSummary(revision, operation, branch, candidate.patient_id);
  } catch (e) { if (current(revision) && operation === receptionOperation) error.value = e instanceof ApiError ? e.message : '选择用户失败。'; }
  finally { if (current(revision) && operation === receptionOperation) loading.value = false; }
}
async function createPatient(): Promise<void> {
  const revision = session.sessionRevision;
  const operation = ++receptionOperation;
  const branch = selectedBranchId.value;
  error.value = ''; message.value = '';
  if (!searchResult.value || !lookup.value.inPerson) { error.value = '请先精确检索并完成线下身份核验。'; return; }
  if ((lookup.value.allergyStatus === '有' || lookup.value.allergyStatus === '不确定') && !lookup.value.allergyNotes.trim()) { error.value = '请补充过敏情况说明。'; return; }
  loading.value = true;
  const input = { ...verification(), birth_date: lookup.value.birthDate || undefined, allergy_status: lookup.value.allergyStatus, allergy_notes: lookup.value.allergyNotes.trim() || undefined, source_channel: lookup.value.sourceChannel || undefined, duplicate_resolution: searchResult.value.reason === 'same_phone' ? 'shared_phone' : searchResult.value.reason === 'same_name' ? 'same_name' : undefined };
  const fingerprint = JSON.stringify(input);
  if (!createPatientKey.value.startsWith(`${fingerprint}:`)) createPatientKey.value = `${fingerprint}:${crypto.randomUUID()}`;
  try {
    const result = await api.createPatient(input, createPatientKey.value.slice(fingerprint.length + 1));
    if (!current(revision) || operation !== receptionOperation || branch !== selectedBranchId.value) return;
    if (!result.patient_id) throw new ApiError('INVALID_RESPONSE', 0, '建档结果不完整，请用同一请求重试核对。');
    if (result.token_shown_once || !result.selection_token || !result.expires_at) {
      message.value = `档案 ${result.patient_id} 已建立；请重新检索并核验，取得新的选择凭证。`;
      searchResult.value = null; clearSelection(); return;
    }
    selectedPatient.value = { id: result.patient_id, label: lookup.value.name.trim(), token: result.selection_token, expiresAt: result.expires_at };
    selectionBranch.value = selectedBranchId.value; form.value.patientId = result.patient_id;
    message.value = `档案 ${result.patient_id} 已建立，现可提交预约。`;
    await load();
    if (current(revision) && operation === receptionOperation && branch === selectedBranchId.value) await loadPatientSummary(revision, operation, branch, result.patient_id);
  } catch (e) { if (current(revision) && operation === receptionOperation) error.value = e instanceof ApiError ? e.message : '建档结果不明；保留当前输入与幂等键，请重试核对。'; }
  finally { if (current(revision) && operation === receptionOperation) loading.value = false; }
}
async function load(): Promise<void> {
  const revision = session.sessionRevision;
  const request = ++overviewRequest;
  loading.value = true; error.value = '';
  try {
    const result = await api.overview();
    if (!current(revision) || request !== overviewRequest) return;
    data.value = result;
    if (!form.value.scheduleId) form.value.scheduleId = result.schedules[0]?.schedule_id ?? '';
    if (!form.value.itemId) form.value.itemId = result.services[0]?.item_id ?? '';
    form.value.time = startOptions.value[0]?.value ?? '';
    if (!result.branches.some((branch) => branch.branch_id === queueBranch.value))
      queueBranch.value = result.branches[0]?.branch_id ?? '';
    if (queueBranch.value) void loadQueue();
    if (queueBranch.value) void loadChanges();
    if (queueBranch.value) void loadContactTodos(true);
    else { contactTodoRequest++; contactTodos.value=[]; contactTodoCursor.value=null; contactTodoBusy.value=false; }
  } catch (e) {
    if (!current(revision) || request !== overviewRequest) return;
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      data.value = null; queueTickets.value = []; queueBranch.value = ''; queueLoading.value = false; queueRequest++;
      cardRequest++; cardTarget.value = null; cardSummary.value = null; cardError.value = ''; cardLoading.value = false;
      contactTodoRequest++; contactTodos.value=[]; contactTodoCursor.value=null; contactTodoBusy.value=false;
    }
    error.value = e instanceof ApiError ? e.message : '预约工作台读取失败。';
  }
  finally { if (current(revision) && request === overviewRequest) loading.value = false; }
}
async function loadQueue(): Promise<void> {
  const branch = queueBranch.value;
  const date = queueDate.value;
  const request = ++queueRequest;
  if (!branch || !date) { queueTickets.value = []; return; }
  queueLoading.value = true; queueError.value = '';
  try {
    const tickets = await api.queue(branch, date);
    if (request === queueRequest && branch === queueBranch.value && date === queueDate.value) queueTickets.value = tickets;
  } catch (e) {
    if (request === queueRequest && branch === queueBranch.value && date === queueDate.value) {
      queueTickets.value = [];
      queueError.value = e instanceof ApiError ? e.message : '候诊队列读取失败。';
    }
  } finally { if (request === queueRequest) queueLoading.value = false; }
}
function changeQueueBranch(): void {
  changes.value=[]; void loadChanges();
  closeContact();
  void loadContactTodos(true);
  codeRequest++;
  issuingCheckinCode.value = false;
  showCheckinCode.value = false;
  checkinCodeUrl.value = ''; checkinCodeExpires.value = ''; checkinCodeError.value = '';
  void loadQueue();
}
async function loadContactTodos(reset: boolean): Promise<void> {
  const branch=queueBranch.value;
  const revision=session.sessionRevision;
  const cursor=reset?undefined:contactTodoCursor.value ?? undefined;
  const request=++contactTodoRequest;
  if(reset) { contactTodos.value=[]; contactTodoCursor.value=null; }
  if(!branch || !reset && !cursor) { contactTodoBusy.value=false; return; }
  contactTodoBusy.value=true; contactTodoError.value='';
  try {
    const page=await api.contactTodos(branch,cursor);
    if(request!==contactTodoRequest || branch!==queueBranch.value || revision!==session.sessionRevision) return;
    contactTodos.value=reset?page.items:[...contactTodos.value,...page.items];
    contactTodoCursor.value=page.nextCursor;
  } catch(e) {
    if(request===contactTodoRequest && revision===session.sessionRevision)
      contactTodoError.value=e instanceof ApiError?e.message:'待联系清单读取失败。';
  } finally { if(request===contactTodoRequest) contactTodoBusy.value=false; }
}
async function openContactTodo(todo: ContactTodo): Promise<void> {
  const row=data.value?.appointments.find(a=>a.appointment_id===todo.appointment_id);
  if(!row) { contactTodoError.value='预约列表尚未包含这笔预约，请刷新后重试。'; return; }
  dateFilter.value=shanghaiDate(row.starts_at);
  await openContact(row);
  if(contactTarget.value?.appointment_id===row.appointment_id)
    document.getElementById('contact-form')?.scrollIntoView({behavior:'smooth',block:'start'});
}
async function issueCheckinCode(): Promise<void> {
  const branch = queueBranch.value;
  if (!branch || issuingCheckinCode.value) return;
  const request = ++codeRequest;
  issuingCheckinCode.value = true; checkinCodeError.value = '';
  showCheckinCode.value = false;
  checkinCodeUrl.value = ''; checkinCodeExpires.value = '';
  try {
    const issued = await api.issueCheckinCode(branch);
    const { toDataURL } = await import('qrcode');
    const url = await toDataURL(issued.code, { errorCorrectionLevel: 'M', margin: 3, width: 260 });
    if (request !== codeRequest || branch !== queueBranch.value) return;
    checkinCodeUrl.value = url;
    checkinCodeExpires.value = issued.expires_at;
    wallClock.value = Date.now();
    showCheckinCode.value = true;
  } catch (e) {
    if (request === codeRequest) checkinCodeError.value = e instanceof ApiError ? e.message : '签到码生成失败，请重试。';
  } finally { if (request === codeRequest) issuingCheckinCode.value = false; }
}
async function create(): Promise<void> {
  if (loading.value) return;
  const revision = session.sessionRevision;
  const operation = ++receptionOperation;
  error.value = ''; message.value = '';
  const schedule = selectedSchedule.value;
  if (!schedule || !selectedPatient.value || !form.value.itemId || !form.value.time) { error.value = '请先完成用户检索与线下核验，再选择排班、项目和时段。'; return; }
  if (selectedPatient.value.expiresAt <= new Date().toISOString() || selectionBranch.value !== selectedBranchId.value) { clearSelection(); error.value = '用户选择凭证已失效，请重新核验。'; return; }
  if (!form.value.consentBasis.trim()) { error.value="请填写患者确认预约的依据。"; return; }
  if (!form.value.purpose.trim()) { error.value = '请填写预约目的。'; return; }
  const date = shanghaiDate(form.value.time);
  loading.value = true;
  try {
    const created = await api.create({
      patientId: form.value.patientId, scheduleId: form.value.scheduleId, itemId: form.value.itemId,
      startsAt: form.value.time, visitType: form.value.visitType,
      channel: form.value.channel, purpose: form.value.purpose.trim(), consentBasis: form.value.consentBasis.trim(),
      patientSelectionToken: selectedPatient.value.token,
    });
    if (!current(revision) || operation !== receptionOperation) return;
    clearSelection(); searchResult.value = null;
    message.value = `预约 ${created.appointment_id} 已创建并占用真实排班时段。`;
    dateFilter.value = date;
    await load();
  } catch (e) {
    if (!current(revision) || operation !== receptionOperation) return;
    const text = e instanceof ApiError ? e.message : '预约创建失败。';
    if (e instanceof ApiError && e.status === 409) {
      await load();
      if (current(revision) && operation === receptionOperation) error.value = text;
    } else {
      error.value = text;
    }
  }
  finally { if (current(revision) && operation === receptionOperation) loading.value = false; }
}
async function cancel(row: AppointmentRow): Promise<void> {
  error.value = ''; message.value = '';
  loading.value = true;
  try { await api.cancel(row.appointment_id, '前台取消预约'); message.value = `预约 ${row.appointment_id} 已取消。`; await load(); }
  catch (e) { error.value = e instanceof ApiError ? e.message : '取消失败。'; }
  finally { loading.value = false; }
}
function openCheckin(row: AppointmentRow): void {
  if (shanghaiDate(row.starts_at) !== todayShanghai.value) {
    error.value = '只能在预约当天签到；请核对预约日期。';
    return;
  }
  checkinTarget.value = row;
  checkinBasis.value = '';
  checkinException.value = '';
  error.value = ''; message.value = '';
}
async function checkin(): Promise<void> {
  const row = checkinTarget.value;
  if (!row || loading.value) return;
  error.value = ''; message.value = '';
  if (shanghaiDate(row.starts_at) !== shanghaiDate(new Date().toISOString())) {
    error.value = '只能在预约当天签到；尚未写入候诊记录。'; return;
  }
  if (!checkinBasis.value.trim()) { error.value = '请填写现场核验依据。'; return; }
  loading.value = true;
  try {
    const ticket = await api.checkin({ id: row.appointment_id, expectedVersion: row.version,
      verificationBasis: checkinBasis.value, exceptionReason: checkinException.value });
    checkinTarget.value = null;
    await load();
    message.value = `预约 ${row.appointment_id} 已签到，候诊号码 ${ticket.queue_no}。`;
  }
  catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      checkinTarget.value = null;
      await load();
      error.value = `${e.message}；预约已刷新，请重新选择。`;
    } else if (e instanceof ApiError && e.code === 'BUSINESS_BASIS_REQUIRED') {
      error.value = '该门店未配置签到窗口或预约已超窗；请填写“超窗/未配置窗口理由”后重试。';
    } else if (!(e instanceof ApiError) || e.status === 0 || e.status >= 500 || e.code === 'INVALID_RESPONSE') {
      error.value = '签到结果暂不明确；请刷新预约核对候诊状态，必要时手动重试同一请求。';
    } else {
      error.value = e.message;
    }
  }
  finally { loading.value = false; }
}
function badge(state: string): string {
  if (state.includes('取消') || state === '未到') return 'bad';
  if (state === '已确认' || state === '候诊中' || state === '接诊中') return 'good';
  return 'warn';
}
async function openCard(row: AppointmentRow): Promise<void> {
  const revision = session.sessionRevision;
  const request = ++cardRequest;
  cardTarget.value = row; cardSummary.value = null; cardError.value = ''; cardLoading.value = true;
  try {
    const summary = await api.receptionSummary(row.branch_id, row.patient_id);
    if (current(revision) && request === cardRequest && cardTarget.value?.appointment_id === row.appointment_id)
      cardSummary.value = summary;
  } catch (e) {
    if (current(revision) && request === cardRequest)
      cardError.value = e instanceof ApiError ? e.message : '接待资料读取失败，请重试。';
  } finally {
    if (current(revision) && request === cardRequest) cardLoading.value = false;
  }
}
function closeCard(): void { cardRequest++; cardTarget.value = null; cardSummary.value = null; cardError.value = ''; cardLoading.value = false; }
async function openContact(row: AppointmentRow): Promise<void> {
  if (!row.unavailability_alert) return;
  const request = ++contactRequest;
  contactTarget.value = row;
  contactBusy.value = false;
  contactChannel.value = '电话'; contactResult.value = '';
  contactAcknowledgment.value = ''; contactNext.value = '';
  contactHistory.value = []; contactError.value = '';
  try {
    const contacts = await api.contacts(row.branch_id);
    if (request === contactRequest && contactTarget.value?.appointment_id === row.appointment_id)
      contactHistory.value = contacts.filter((item) => item.appointment_id === row.appointment_id);
  } catch (e) {
    if (request === contactRequest) contactError.value = e instanceof ApiError ? e.message : '联系历史读取失败。';
  }
}
function closeContact(): void { contactRequest++; contactTarget.value = null; contactHistory.value = []; contactError.value = ''; contactBusy.value = false; }
async function saveContact(): Promise<void> {
  const row = contactTarget.value;
  if (!row || contactBusy.value) return;
  if (!contactResult.value.trim()) { contactError.value = '请填写实际联系结果；未接通也要如实记录。'; return; }
  const request = contactRequest;
  contactBusy.value = true; contactError.value = '';
  try {
    const saved = await api.recordContact({
      appointmentId: row.appointment_id, channel: contactChannel.value,
      result: contactResult.value,
      acknowledgmentBasis: contactAcknowledgment.value || undefined,
      nextContactAt: contactNext.value ? `${contactNext.value}:00+08:00` : undefined,
    });
    if (request !== contactRequest || contactTarget.value?.appointment_id !== row.appointment_id) return;
    contactHistory.value = [saved, ...contactHistory.value.filter((item) => item.contact_id !== saved.contact_id)];
    contactResult.value = ''; contactAcknowledgment.value = ''; contactNext.value = '';
    message.value = saved.acknowledged ? '人工联系及患者知晓依据已记录；预约仍待正式协调。' : '联系尝试已记录，患者知晓尚未确认；预约仍待正式协调。';
    await load();
  } catch (e) {
    if (request === contactRequest) contactError.value = e instanceof ApiError ? e.message : '登记结果不明，请保留内容并用原请求重试核对。';
  } finally { if (request === contactRequest) contactBusy.value = false; }
}

function onCodeKeydown(event: KeyboardEvent): void { if (event.key === 'Escape') showCheckinCode.value = false; }
onMounted(() => { void load(); countdownTimer = setInterval(() => { wallClock.value = Date.now(); }, 1000); document.addEventListener('keydown', onCodeKeydown); });
onUnmounted(() => { viewActive = false; overviewRequest++; lookupRequest++; receptionOperation++; if (countdownTimer) clearInterval(countdownTimer); document.removeEventListener('keydown', onCodeKeydown); codeRequest++; contactRequest++; contactTodoRequest++; changeRequest++; cardRequest++; });
</script>

<template>
  <main class="booking-page">
    <header>
      <div><RouterLink to="/">← 工作台</RouterLink><h1>预约工作台</h1><p>查看预约、协助挂号与办理到店签到。</p><RouterLink to="/frontdesk-schedule">查看医生排班表 →</RouterLink></div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="notice" role="status">{{ message }}</p>
    <p v-if="loading" class="muted">正在读取预约数据…</p>
    <template v-if="data">
      <div class="summary">
        <div><span>今日排班</span><strong>{{ schedules.length }}</strong></div>
        <div><span>本店关联患者</span><strong>{{ patientCount }}</strong></div>
        <div><span>已确认/候诊</span><strong>{{ appointments.filter(a => a.state === '已确认' || a.state === '候诊中').length }}</strong></div>
        <div><span>已取消</span><strong>{{ appointments.filter(a => a.state.includes('取消')).length }}</strong></div>
      </div>

      <section class="panel">
        <div class="panel-head"><div><h2>前台代预约</h2><p>提交时服务端会再次校验排班、医生和患者时间冲突。</p></div></div>
        <div class="reception-box">
          <h3>第一步 · 定位并核验就诊用户</h3>
          <p class="muted">姓名和电话仅用于查找档案，不能单独证明本人身份。请在现场核对就诊凭据或身份证件；不记录证件号码。</p>
          <div class="form-grid">
            <label>姓名<input v-model="lookup.name" maxlength="60" :disabled="loading" @input="clearLookup" /></label>
            <label>电话<input v-model="lookup.phone" maxlength="30" :disabled="loading" @input="clearLookup" /></label>
            <label>核验依据<select v-model="lookup.method" :disabled="loading"><option value="visit_evidence">到店就诊凭据</option><option value="id_document">现场身份证件</option></select></label>
          </div>
          <label class="verify-check"><input v-model="lookup.inPerson" type="checkbox" :disabled="loading" /> 已在现场核验本人及本次就诊意愿</label>
          <button type="button" :disabled="loading || !selectedBranchId" @click="searchPatient">精确检索档案</button>
          <p v-if="!selectedBranchId" class="muted">请先在下方选择目标门店的医生排班。</p>
          <template v-if="searchResult">
            <p class="muted">找到 {{ searchResult.matches.length }} 个候选；请人工判断，不能按姓名或电话自动合并。</p>
            <div v-for="candidate in searchResult.matches" :key="candidate.patient_id" class="candidate-row">
              <span>{{ candidate.display_name }} · 尾号 {{ candidate.phone_suffix }} · {{ candidate.birth_year ?? '出生年未填' }} · {{ candidate.match_type }}</span>
              <button type="button" :disabled="loading || !lookup.inPerson" @click="selectPatient(candidate)">核验并选择</button>
            </div>
            <label v-if="searchResult.matches.length" class="resolution-label">匹配情况
              <select v-model="lookup.resolution" :disabled="loading"><option value="single_exact">唯一精确匹配</option><option value="manual_candidate">多个候选，人工确认</option><option v-if="searchResult.reason === 'same_phone'" value="shared_phone">共用电话，人工确认</option><option v-if="searchResult.reason === 'same_name'" value="same_name">同名不同电话，人工确认</option></select>
            </label>
            <div v-if="searchResult.can_create || !searchResult.matches.some(x => x.match_type === 'exact_name_phone')" class="create-patient">
              <h3>未找到对应档案 · 建立用户档案</h3>
              <p class="muted">仅创建就诊档案，不创建微信账号；同名或共用电话必须使用身份证件核验。</p>
              <div class="form-grid">
                <label>出生日期（可选）<input v-model="lookup.birthDate" type="date" :disabled="loading" /></label>
                <label>过敏情况<select v-model="lookup.allergyStatus" :disabled="loading"><option>未申报</option><option>无已知</option><option>有</option><option>不确定</option></select></label>
                <label>获知渠道（不确定可留空）<select v-model="lookup.sourceChannel" :disabled="loading"><option value="">未采集</option><option>现场</option><option>电话</option><option>转介绍</option><option>微信小程序</option><option>其他</option></select></label>
                <label class="wide">过敏说明<input v-model="lookup.allergyNotes" maxlength="1000" :disabled="loading" /></label>
              </div>
              <button type="button" :disabled="loading || !lookup.inPerson" @click="createPatient">核验并建档</button>
            </div>
          </template>
          <p v-if="selectedPatient" class="notice">已选择：{{ selectedPatient.label }}。凭证仅用于本次前台代预约，过期需重新核验。</p>
          <div v-if="patientSummary" class="notice">本店档案摘要：获知渠道 {{ patientSummary.source_channel || '未采集' }}；本店上次实际接诊医师 {{ patientSummary.last_visit_dentist || '暂无已完成就诊' }}。以上来自真实档案与已完成就诊记录。</div>
          <p v-if="patientSummaryError && selectedPatient" class="muted">{{ patientSummaryError }} <button type="button" @click="loadPatientSummary(session.sessionRevision, receptionOperation, selectionBranch, selectedPatient.id)">重读摘要</button></p>
        </div>
        <h3>第二步 · 确认排班与项目</h3>
        <div class="form-grid">
          <label>就诊用户<input :value="selectedPatient?.label ?? '请先检索并核验'" readonly /></label>
          <label>排班<select v-model="form.scheduleId" :disabled="loading" @change="onSelectionChange"><option v-for="s in schedules" :key="s.schedule_id" :value="s.schedule_id">{{ s.dentist_name }} · {{ s.branch_name }} · {{ displayTime(s.starts_at) }}</option></select></label>
          <label>项目<select v-model="form.itemId" :disabled="loading" @change="onSelectionChange"><option v-for="s in services" :key="s.item_id" :value="s.item_id">{{ s.name }} · {{ s.minutes }}分钟 · ¥{{ s.price }}</option></select></label>
          <label>时段<select v-model="form.time" :disabled="loading"><option v-for="t in startOptions" :key="t.value" :value="t.value">{{ t.label }}</option></select></label>
          <label>类型<select v-model="form.visitType"><option value="初诊">初诊</option><option value="复诊">复诊</option></select></label>
          <label>渠道<select v-model="form.channel"><option value="电话">电话</option><option value="现场">现场</option><option value="微信登记">微信登记</option></select></label>
          <label class="wide">患者确认依据<input v-model="form.consentBasis" maxlength="300" placeholder="记录本次电话、现场等确认事实，不填默认同意" /></label>
          <label class="wide">目的<input v-model="form.purpose" maxlength="200" /></label>
        </div>
        <p v-if="startOptions.length === 0" class="slot-empty">后台当前没有满足门店营业、医生资格/任职、服务范围和排班的可约时段。</p>
        <button class="primary" :disabled="loading || !selectedPatient" @click="create">确认预约</button>
      </section>

      <section class="panel">
        <div class="panel-head"><div><h2>待联系事项</h2><p>仅列出仍受停诊或设备禁用影响、尚未联系或已到下次联系时间的预约；此处不表示通知已送达。</p></div><button type="button" :disabled="contactTodoBusy" @click="loadContactTodos(true)">刷新</button></div>
        <label>门店<select v-model="queueBranch" @change="changeQueueBranch"><option v-for="branch in data?.branches ?? []" :key="branch.branch_id" :value="branch.branch_id">{{ branch.name }}</option></select></label>
        <p v-if="contactTodoError" role="alert" class="error">{{ contactTodoError }}</p>
        <div class="table-wrap"><table><thead><tr><th>预约</th><th>就诊时间</th><th>上次联系</th><th>下次联系</th><th>操作</th></tr></thead><tbody>
          <tr v-for="todo in contactTodos" :key="todo.appointment_id"><td>{{ todo.appointment_id }}</td><td>{{ displayTime(todo.starts_at) }}</td>
            <td>{{ todo.last_contacted_at ? displayTime(todo.last_contacted_at) : '尚未联系' }}</td>
            <td>{{ todo.next_contact_at ? displayTime(todo.next_contact_at) : '需尽快安排' }}</td>
            <td><button type="button" :disabled="contactTodoBusy" @click="openContactTodo(todo)">记录联系</button></td></tr>
          <tr v-if="!contactTodoBusy && contactTodos.length===0"><td colspan="5" class="empty">当前没有到期的待联系事项。</td></tr>
        </tbody></table></div>
        <button v-if="contactTodoCursor" type="button" :disabled="contactTodoBusy" @click="loadContactTodos(false)">加载更多</button>
      </section>

      <section class="panel list-panel">
        <div class="panel-head"><div><h2>预约列表</h2><p>状态操作会写入真实 appointment / queue_ticket。</p></div><label class="filter">日期<input v-model="dateFilter" type="date" /></label></div>
        <div v-if="cardTarget" class="reception-box" aria-live="polite">
          <div class="panel-head"><div><h3>患者接待摘要 · {{ cardTarget.branch_name }}</h3><p>仅供本店预约接待；不含诊断和诊疗笔记。</p></div><button type="button" @click="closeCard">关闭资料</button></div>
          <p v-if="cardLoading" class="muted">正在读取接待资料…</p>
          <p v-if="cardError" class="error" role="alert">{{ cardError }} <button type="button" @click="openCard(cardTarget)">重试</button></p>
          <div v-if="cardSummary" class="reception-summary">
            <p><strong>{{ cardSummary.name }}</strong> · 联系电话 <strong>{{ cardSummary.phone }}</strong></p>
            <p v-if="cardSummary.visits.length">本店上次接诊：{{ cardSummary.visits[0]?.dentist_name }} · {{ displayTime(cardSummary.visits[0]?.completed_at ?? '') }}</p>
            <h4>本店预约记录</h4>
            <p v-if="cardSummary.appointments.length === 0" class="muted">暂无本店预约。</p>
            <ul v-else><li v-for="item in cardSummary.appointments" :key="item.appointment_id">{{ displayTime(item.starts_at) }} · {{ item.item_name || '项目未登记' }} · {{ item.dentist_name || '医生待确认' }} · {{ item.state }}</li></ul>
            <h4>已完成就诊</h4>
            <p v-if="cardSummary.visits.length === 0" class="muted">暂无本店已完成就诊记录。</p>
            <ul v-else><li v-for="item in cardSummary.visits" :key="item.completed_at + item.dentist_name">{{ displayTime(item.completed_at) }} · {{ item.item_name || '项目未登记' }} · {{ item.dentist_name }}</li></ul>
          </div>
        </div>
        <div v-if="checkinTarget" class="checkin-box">
          <h3>前台签到 · 预约 {{ checkinTarget.appointment_id }}</h3>
          <p class="muted">请在现场核对就诊用户与本次预约；不要填写证件号码。超出签到窗口或门店未配置窗口时须说明人工办理理由。</p>
          <div class="form-grid">
            <label>现场核验依据<input v-model="checkinBasis" maxlength="300" placeholder="例如：现场核对就诊凭据" /></label>
            <label class="wide">超窗或未配置窗口理由（无窗口时必填）<input v-model="checkinException" maxlength="300" placeholder="例如：门店未配置窗口，前台现场核实" /></label>
          </div>
          <button type="button" :disabled="loading" @click="checkin">确认签到并领取候诊号</button>
          <button type="button" :disabled="loading" @click="checkinTarget = null">返回列表</button>
        </div>
        <div v-if="contactTarget" id="contact-form" class="checkin-box">
          <h3>人工联系 · 预约 {{ contactTarget.appointment_id }}</h3>
          <p>仅登记实际联系尝试。未取得患者明确答复时，留空“知晓依据”；登记不会自动取消、改约或发送通知。</p>
          <fieldset :disabled="contactBusy" class="form-grid">
            <label>渠道<select v-model="contactChannel"><option value="电话">电话</option><option value="微信登记">微信登记</option><option value="当面">当面</option></select></label>
            <label class="wide">实际结果<input v-model="contactResult" maxlength="300" placeholder="例如：拨打电话未接通；不要填写证件号码" /></label>
            <label class="wide">患者已知晓的依据（确实取得确认后才填）<input v-model="contactAcknowledgment" maxlength="300" placeholder="未确认请留空" /></label>
            <label>下次联系时间（上海时间）<input v-model="contactNext" type="datetime-local" /></label>
          </fieldset>
          <p v-if="contactError" role="alert">{{ contactError }}</p>
          <button type="button" :disabled="contactBusy" @click="saveContact">保存联系记录</button>
          <button type="button" :disabled="contactBusy" @click="closeContact">返回列表</button>
          <div v-if="contactHistory.length" class="contact-history">
            <h4>最近联系记录</h4>
            <p v-for="item in contactHistory" :key="item.contact_id">{{ displayTime(item.contacted_at) }} · {{ item.channel }} · {{ item.result }} · {{ item.acknowledged ? '已记录知晓依据' : '尚未确认知晓' }}{{ item.next_contact_at ? ` · 下次联系 ${displayTime(item.next_contact_at)}` : '' }}</p>
          </div>
        </div>
        <div v-if="changeTarget" class="checkin-box">
          <h3>拟协调方案 · 原预约 {{ changeTarget.appointment_id }}</h3>
          <p class="muted">这里只拟候选，不释放原预约，也不占新号源。请先与用户沟通；答复和落实是后续独立步骤。</p>
          <p v-if="!changeTarget.item_id" class="error" role="alert">原预约缺少项目编号，请刷新列表后再试。</p>
          <p v-else-if="changeSchedules.length===0" class="muted">本店当前没有能接诊“{{ changeTarget.item_name }}”且有可约时段的医生。请查看其他日期，或由负责人调整排班后刷新。</p>
          <fieldset :disabled="changeBusy" class="form-grid">
            <label>候选排班<select v-model="changeScheduleId" @change="updateChangeSlots"><option v-for="s in changeSchedules" :key="s.schedule_id" :value="s.schedule_id">{{ s.dentist_name }} · {{ displayTime(s.starts_at) }}</option></select></label>
            <p class="muted">原预约项目：{{ changeTarget.item_name || '未记录' }}。候选医生须能接诊同一项目。</p>
            <label>候选时段<select v-model="changeStart"><option v-for="slot in changeSlots" :key="slot" :value="slot">{{ displayTime(slot) }}</option></select></label>
            <label class="wide">协调原因<input v-model="changeReason" maxlength="300" placeholder="例如：原医生临时停诊；不要填写证件号" /></label>
          </fieldset>
          <p v-if="changeError" class="error" role="alert">{{ changeError }}</p>
          <button type="button" :disabled="changeBusy" @click="proposeChange">保存候选方案</button>
          <button type="button" :disabled="changeBusy" @click="closeChange">返回列表</button>
        </div>
        <div class="table-wrap">
          <table><thead><tr><th>时间</th><th>患者</th><th>医生/门店</th><th>项目</th><th>渠道</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="a in appointments" :key="a.appointment_id">
                <td>{{ displayTime(a.starts_at) }}<div v-if="a.previous_id" class="previous">由预约 {{ a.previous_id }} 改约</div></td><td>{{ a.patient_name }}</td><td>{{ a.dentist_name }} · {{ a.branch_name }}</td><td>{{ a.item_name }}</td><td>{{ a.channel }}</td>
                <td><span :class="['badge', badge(a.state)]">{{ a.state }}</span><div v-if="a.unavailability_alert" class="previous" role="status">停诊或设备影响 · 待人工协调，尚未通知</div></td><td>v{{ a.version }}</td>
                <td class="actions">
                  <button type="button" :disabled="loading" @click="openCard(a)">接待资料</button>
                  <button v-if="a.state === '已确认'" :disabled="loading || a.unavailability_alert || shanghaiDate(a.starts_at) !== todayShanghai" @click="openCheckin(a)">签到</button>
                  <span v-if="a.state === '已确认' && shanghaiDate(a.starts_at) !== todayShanghai" class="muted">预约当天可签到</span>
                  <button v-if="a.unavailability_alert" :disabled="loading" @click="openContact(a)">记录人工联系</button>
                  <button v-if="a.unavailability_alert && a.state === '已确认'" :disabled="loading" @click="openChange(a)">拟协调方案</button>
                  <button v-if="a.state !== '已取消' && a.state !== '已完成' && a.state !== '候诊中' && a.state !== '接诊中'" :disabled="loading" @click="cancel(a)">取消</button>
                  <span v-if="a.state === '候诊中' || a.state === '已完成' || a.state === '已取消'" class="muted">—</span>
                </td>
              </tr>
              <tr v-if="appointments.length === 0"><td colspan="8" class="empty">该日期暂无预约。</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head"><div><h2>本店协调方案</h2><p>电话答复须有实际依据；同意不等于落实。只有“落实”成功才占新号源并释放旧预约。</p></div><button type="button" :disabled="changeBusy" @click="loadChanges">刷新方案</button></div>
        <p v-if="changeError" class="error" role="alert">{{ changeError }}</p>
        <label>患者答复依据<input v-model="changeBasis" maxlength="300" placeholder="例如：患者来电明确同意／拒绝候选时间" /></label>
        <label>撤回原因<input v-model="changeWithdrawReason" maxlength="300" placeholder="例如：候选医生临时无法接诊；已同意时须另行告知患者" /></label>
        <div class="table-wrap"><table><thead><tr><th>原预约</th><th>候选</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody>
          <tr v-for="change in changes" :key="change.change_id"><td>{{ change.original_id }}</td><td>{{ displayTime(change.starts_at) }} · 医生 {{ change.dentist_id }}</td><td>{{ change.reason }}</td><td>{{ change.state }}{{ change.applied ? ' · 已落实' : '' }}</td><td class="actions">
            <button v-if="change.state === '待答复'" :disabled="changeBusy || !changeBasis.trim()" @click="respondChange(change,true)">登记同意</button>
            <button v-if="change.state === '待答复'" :disabled="changeBusy || !changeBasis.trim()" @click="respondChange(change,false)">登记拒绝</button>
            <button v-if="change.state === '已同意'" :disabled="changeBusy" @click="applyChange(change)">落实改约</button>
            <button v-if="change.state === '待答复' || change.state === '已同意'" :disabled="changeBusy || !changeWithdrawReason.trim()" @click="withdrawChange(change)">撤回方案</button>
          </td></tr>
          <tr v-if="changes.length===0"><td colspan="5" class="empty">本店暂无协调方案。</td></tr>
        </tbody></table></div>
      </section>

      <section class="panel">
        <div class="panel-head"><div><h2>本店候诊队列</h2><p>真实候诊票；仅展示最小编号与状态，不在公开屏展示用户身份。</p></div></div>
        <div class="queue-controls">
          <label>门店<select v-model="queueBranch" @change="changeQueueBranch"><option v-for="branch in data.branches" :key="branch.branch_id" :value="branch.branch_id">{{ branch.name }}</option></select></label>
          <label>日期<input v-model="queueDate" type="date" @change="loadQueue" /></label>
          <button type="button" :disabled="queueLoading || !queueBranch" @click="loadQueue">刷新队列</button>
        </div>
        <div class="scan-box">
          <div><h3>到店自助扫码签到</h3><p class="muted">前台生成本店短效码，让已确认预约的用户在小程序“我的预约”页扫码。码约 60 秒有效，不含个人资料。</p></div>
          <button type="button" :disabled="!queueBranch || issuingCheckinCode" @click="issueCheckinCode">{{ issuingCheckinCode ? '生成中…' : '生成／刷新签到码' }}</button>
          <p v-if="checkinCodeError" class="error" role="alert">{{ checkinCodeError }}</p>
          <div v-if="checkinCodeUrl && codeSeconds > 0" class="scan-code"><img :src="checkinCodeUrl" alt="本店短效签到二维码" /><span>剩余 {{ codeSeconds }} 秒</span><button type="button" @click="showCheckinCode = true">放大给用户看</button></div>
          <p v-else-if="checkinCodeExpires" class="muted">本码已过期，请点击刷新。</p>
        </div>
        <div v-if="showCheckinCode" class="code-overlay" role="presentation" @click.self="showCheckinCode = false">
          <div class="code-dialog" role="dialog" aria-modal="true" aria-labelledby="code-dialog-title">
            <div class="code-dialog-head"><h2 id="code-dialog-title">本店扫码签到</h2><button type="button" aria-label="关闭签到码弹窗" @click="showCheckinCode = false">关闭</button></div>
            <p class="muted">请让已确认预约的用户在小程序内扫码。此码不包含个人资料。</p>
            <div v-if="codeSeconds > 0" class="code-dialog-image"><img :src="checkinCodeUrl" alt="本店短效签到二维码" /><strong>剩余 {{ codeSeconds }} 秒</strong></div>
            <p v-else class="code-expired">签到码已过期，请重新生成。</p>
            <button type="button" :disabled="issuingCheckinCode" @click="issueCheckinCode">{{ issuingCheckinCode ? '生成中…' : '重新生成签到码' }}</button>
          </div>
        </div>
        <p v-if="queueError" class="error" role="alert">{{ queueError }}</p>
        <p v-if="queueLoading" class="muted">正在读取候诊队列…</p>
        <div class="table-wrap"><table><thead><tr><th>候诊号</th><th>预约编号</th><th>状态</th><th>签到渠道</th></tr></thead>
          <tbody><tr v-for="ticket in queueTickets" :key="ticket.ticket_id"><td>{{ ticket.queue_no }}</td><td>{{ ticket.appointment_id }}</td><td>{{ ticket.state }}</td><td>{{ ticket.checkin_channel }}</td></tr>
            <tr v-if="!queueLoading && queueTickets.length === 0"><td colspan="4" class="empty">该门店当日暂无候诊号码。</td></tr></tbody></table></div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.booking-page { min-height: 100vh; background: #f2f8f7; padding: 22px 26px 40px; color: #193d39; }
header { display: flex; justify-content: space-between; align-items: start; gap: 14px; margin-bottom: 14px; } header a { color: #256e60; text-decoration: none; font-size: 13px; } h1 { margin: 7px 0 3px; font-size: 24px; } header p { margin: 0; color: #68847f; font-size: 13px; }
.real-badge { background: #dff4e9; color: #1c6c52; border-radius: 999px; padding: 5px 9px; font-size: 11px; }
.summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }.summary div { background: #fff; border: 1px solid #dbeae6; border-radius: 9px; padding: 13px; display: grid; gap: 5px; }.summary span { color: #728d87; font-size: 12px; }.summary strong { font-size: 23px; color: #15594e; }
.panel { background: #fff; border: 1px solid #dbeae6; border-radius: 10px; padding: 17px; margin-bottom: 14px; }.panel-head { display: flex; justify-content: space-between; align-items: start; gap: 10px; }.panel-head h2 { margin: 0; font-size: 16px; }.panel-head p { margin: 4px 0 12px; color: #718b85; font-size: 12px; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 12px; }label { display: grid; gap: 5px; color: #466a63; font-size: 12px; }.wide { grid-column: span 2; }input, select { border: 1px solid #cfe2dd; border-radius: 6px; padding: 8px; font: inherit; font-size: 13px; background: #fff; }
button { border: 1px solid #c8ded8; background: #fff; color: #246558; border-radius: 6px; padding: 7px 11px; font: inherit; font-size: 12px; cursor: pointer; }button.primary { background: #197c6b; color: #fff; border-color: #197c6b; }button:disabled { opacity: .5; cursor: not-allowed; }
.table-wrap { overflow: auto; }table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 14px; }th { text-align: left; color: #6c8983; font-weight: 500; padding: 8px; border-bottom: 1px solid #e6f0ed; white-space: nowrap; }td { padding: 9px 8px; border-bottom: 1px solid #edf4f2; }.badge { padding: 3px 7px; border-radius: 999px; background: #edf4f2; font-size: 11px; }.badge.good { background: #dff4e9; color: #1c6c52; }.badge.warn { background: #fff2d7; color: #875b12; }.badge.bad { background: #ffebe5; color: #9a3d2e; }.actions { display: flex; gap: 5px; flex-wrap: wrap; }.filter { display: flex; align-items: center; gap: 6px; font-size: 12px; }.muted { color: #819791; font-size: 12px; }.previous { color: #6d8983; font-size: 11px; margin-top: 3px; }.error, .notice { padding: 10px 12px; border-radius: 7px; font-size: 12px; }.error { background: #fff0eb; color: #963d2e; }.notice { background: #e6f6ef; color: #1e6752; }.empty { text-align: center; color: #8ca19c; padding: 20px; }.slot-empty { margin: 0 0 10px; padding: 8px 10px; border-radius: 6px; background: #fff7e7; color: #805817; font-size: 12px; }
.reception-box { background: #f7fcfa; border: 1px solid #d8eee5; border-radius: 12px; padding: 16px; margin-bottom: 18px; }.reception-box h3, .panel h3 { font-size: 14px; margin: 0 0 9px; }.reception-box .muted { margin: 4px 0 12px; }.verify-check { display: flex; align-items: center; gap: 7px; margin: 10px 0; }.candidate-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 8px 0; border-bottom: 1px solid #e2eee9; font-size: 12px; }.resolution-label { margin: 12px 0; max-width: 280px; }.create-patient { border-top: 1px solid #d8eee5; padding-top: 14px; margin-top: 14px; }
.reception-summary { font-size: 13px; line-height: 1.6; }.reception-summary h4 { margin: 16px 0 6px; font-size: 13px; }.reception-summary ul { margin: 0; padding-left: 20px; }.reception-summary li { padding: 4px 0; overflow-wrap: anywhere; }
.checkin-box { background: #f7fcfa; border: 1px solid #b9dfd2; border-radius: 12px; padding: 16px; margin: 12px 0; }.checkin-box button { margin-right: 8px; }
.queue-controls { display: flex; gap: 10px; align-items: end; flex-wrap: wrap; }.queue-controls label { min-width: 160px; }.queue-controls button { margin-bottom: 1px; }
.scan-box { margin: 14px 0; padding: 14px; border: 1px solid #d8eee5; border-radius: 12px; background: #f7fcfa; }.scan-box h3 { margin: 0 0 5px; }.scan-code { display: grid; justify-content: start; gap: 5px; color: #386c61; font-size: 12px; }.scan-code img { width: 260px; height: 260px; background: #fff; }
.code-overlay { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; padding: 16px; background: rgba(14, 45, 39, .66); }
.code-dialog { width: min(100%, 420px); max-height: calc(100vh - 32px); overflow: auto; padding: 22px; border-radius: 18px; background: #fff; box-shadow: 0 24px 70px rgba(9, 39, 31, .25); text-align: center; }
.code-dialog-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.code-dialog-head h2 { margin: 0; font-size: 20px; }.code-dialog .muted { margin: 12px 0 16px; line-height: 1.6; }.code-dialog-image { display: grid; justify-items: center; gap: 10px; }.code-dialog-image img { display: block; width: min(100%, 320px); aspect-ratio: 1; background: #fff; }.code-dialog-image strong { font-size: 16px; color: #197c6b; }.code-expired { padding: 55px 10px; color: #805817; }
@media (max-width: 800px) { .form-grid { grid-template-columns: 1fr; }.wide { grid-column: auto; }.summary { grid-template-columns: 1fr 1fr; }.booking-page { padding: 15px; } }
</style>
