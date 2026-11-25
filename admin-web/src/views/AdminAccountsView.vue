<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import { useAccountAdminStore, type AdminMutationBlockedReason } from '@/stores/account-admin';
import { ApiError } from '@/services/http';
import {
  STAFF_ROLE_CODES,
  accountAdminErrorMessage,
  isPositiveIdText,
  isValidStaffLoginName,
  newIdempotencyKey,
  normalizeStaffLoginName,
  type AccountAdminAction,
  type CreatedStaffAccount,
  type RoleScopeDetail,
  type RoleScopeGrant,
  type StaffAccount,
  type StaffRoleCode,
} from '@/services/account-admin';
import { roleLabel } from '@/services/session';

const router = useRouter();
const session = useSessionStore();
const admin = useAccountAdminStore();

interface RoleDraft {
  enabled: boolean;
  branchIds: string;
  approvalBasis: string;
}

const roleDrafts = reactive(
  Object.fromEntries(
    STAFF_ROLE_CODES.map((code) => [code, { enabled: false, branchIds: '', approvalBasis: '' }]),
  ) as Record<StaffRoleCode, RoleDraft>,
);

const pageError = ref('');
const notice = ref('');
const createForm = reactive({ staffId: '', loginName: '' });
const createError = ref('');
const createNotice = ref('');
const lastCreated = ref<CreatedStaffAccount | null>(null);
const rolesConfirm = ref(false);
const rolesError = ref('');
const rolesNotice = ref('');
const roleLoadError = ref('');
const versionConflict = ref(false);
const revokeAllConfirmed = ref(false);
const hasRoleDraft = ref(false);
const disableOpen = ref(false);
const disableReason = ref('');
const disableConfirm = ref(false);
const disableError = ref('');
const refreshing = ref(false);

const identityReady = computed(() => session.status === 'ready');
const selectedAccount = computed<StaffAccount | null>(
  () => admin.accounts.find((account) => account.account_id === admin.selectedAccountId) ?? null,
);
const roleDetail = computed<RoleScopeDetail | null>(() => {
  const detail = admin.roleDetail;
  return detail !== null && detail.account_id === admin.selectedAccountId ? detail : null;
});
const roleLoading = computed(() => admin.roleDetailLoading);
const roleEditingEnabled = computed(
  () => roleDetail.value !== null && !roleLoading.value && roleLoadError.value === '',
);
const roleSummary = computed(() =>
  STAFF_ROLE_CODES.filter((code) => roleDrafts[code].enabled).map((code) => ({
    code,
    label: roleLabel(code),
    branchIds: code === 'sysadmin' ? [] : splitBranchIds(roleDrafts[code].branchIds),
    basis: roleDrafts[code].approvalBasis.trim(),
  })),
);
const revokesAll = computed(() => roleSummary.value.length === 0);

interface RoleDiffItem {
  code: StaffRoleCode;
  label: string;
  before: string;
  after: string;
}

const roleDiff = computed<RoleDiffItem[]>(() => {
  const detail = roleDetail.value;
  if (!detail) return [];
  const original = new Map(detail.roles.map((role) => [role.role_code, role]));
  const result: RoleDiffItem[] = [];
  for (const code of STAFF_ROLE_CODES) {
    const before = original.get(code);
    const draft = roleDrafts[code];
    const afterBranches = code === 'sysadmin' ? [] : splitBranchIds(draft.branchIds);
    const afterBasis = draft.approvalBasis.trim();
    const beforeText = before
      ? `门店：${before.branch_ids.join('、') || '无'}；依据：${before.approval_basis}`
      : '未授予';
    const afterText = draft.enabled
      ? `门店：${afterBranches.join('、') || '无'}；依据：${afterBasis || '（未填写）'}`
      : '撤销';
    const sameBranches =
      before !== undefined &&
      [...before.branch_ids].sort().join(',') === [...afterBranches].sort().join(',');
    const changed = before
      ? !draft.enabled || !sameBranches || before.approval_basis !== afterBasis
      : draft.enabled;
    if (changed) {
      result.push({ code, label: roleLabel(code), before: beforeText, after: afterText });
    }
  }
  return result;
});
const hasRoleChanges = computed(() => roleDiff.value.length > 0);
const canSaveRoles = computed(
  () =>
    roleEditingEnabled.value &&
    !versionConflict.value &&
    hasRoleChanges.value &&
    rolesConfirm.value &&
    (!revokesAll.value || revokeAllConfirmed.value),
);
const isSelfSelected = computed(
  () => selectedAccount.value !== null && selectedAccount.value.account_id === session.me?.account_id,
);

let createKey: string | null = null;
let createKeyFingerprint: string | null = null;
let disableAttemptId = 0;
let draftOwnerAccountId: string | null = null;
let draftSessionRevision: number | null = null;
let detailEditRevision = 0;

function stateLabel(state: StaffAccount['state']): string {
  if (state === 'pending_activation') return '待激活';
  if (state === 'active') return '启用';
  return '停用';
}

function splitBranchIds(text: string): string[] {
  return [...new Set(text.split(/[\s,，]+/).map((part) => part.trim()).filter(Boolean))];
}

function clearCreateKey(): void {
  createKey = null;
  createKeyFingerprint = null;
}

function resetRoleDrafts(): void {
  rolesConfirm.value = false;
  rolesError.value = '';
  rolesNotice.value = '';
  hasRoleDraft.value = false;
  draftOwnerAccountId = null;
  draftSessionRevision = null;
  for (const code of STAFF_ROLE_CODES) {
    roleDrafts[code].enabled = false;
    roleDrafts[code].branchIds = '';
    roleDrafts[code].approvalBasis = '';
  }
}

function markRoleDraft(): void {
  if (!roleEditingEnabled.value || selectedAccount.value === null) return;
  hasRoleDraft.value = true;
  draftOwnerAccountId = selectedAccount.value.account_id;
  draftSessionRevision = session.sessionRevision;
}

/** 身份/权限变化时清空列表无关的页面草稿，避免旧身份内容残留。 */
function clearLocalEditor(): void {
  detailEditRevision += 1; // 使在途详情/保存回调全部失效
  resetRoleDrafts();
  roleLoadError.value = '';
  versionConflict.value = false;
  revokeAllConfirmed.value = false;
  cancelDisable();
  createForm.staffId = '';
  createForm.loginName = '';
  createError.value = '';
  createNotice.value = '';
  lastCreated.value = null;
  clearCreateKey();
}

watch(() => admin.cacheRevision, clearLocalEditor, { flush: 'sync' });
watch(
  roleDrafts,
  () => {
    rolesConfirm.value = false;
  },
  { deep: true },
);
watch(revokesAll, (value) => {
  if (!value) revokeAllConfirmed.value = false;
});

function blockedReasonMessage(reason?: AdminMutationBlockedReason): string {
  if (reason === 'busy') return '正在处理其他账号操作，请稍后再试。';
  if (reason === 'auth-changing') return '认证状态正在变更，请稍后再试。';
  if (reason === 'not-admin') return '当前身份不是系统管理员，不能执行该操作。';
  return '当前身份未确认或会话不可用，请重试。';
}

async function handle401(error: unknown): Promise<boolean> {
  if (!(error instanceof ApiError) || error.status !== 401) return false;
  // 受代次保护的权威复核；不在这里直接清空会话，也不读取 Cookie。
  await session.fetchMe();
  if (session.status === 'guest') {
    await router.replace({ name: 'login' });
  } else if (session.status === 'mustChangePassword') {
    await router.replace({ name: 'change-password' });
  }
  return true;
}

function afterAuthFailure(): string {
  if (session.status === 'ready') {
    return '登录状态已复核，但本次请求未授权，请重试。';
  }
  if (session.status === 'unverified') {
    return session.verificationMessage || '身份确认未完成，请重试。';
  }
  return '登录已失效，请重新登录。';
}

/** 403 不登出，但必须停止展示上一身份的账号缓存。 */
function handlePermissionDenial(error: unknown, action: AccountAdminAction): boolean {
  if (!(error instanceof ApiError) || error.status !== 403) return false;
  admin.clearCache();
  pageError.value = accountAdminErrorMessage(error, action);
  return true;
}

async function loadList(reset = true): Promise<void> {
  pageError.value = '';
  try {
    await admin.load(reset);
  } catch (e) {
    if (await handle401(e)) {
      pageError.value = afterAuthFailure();
      return;
    }
    if (handlePermissionDenial(e, 'list')) return;
    pageError.value = accountAdminErrorMessage(e, 'list');
  }
}

/** 重新从服务端取本人身份，再取账号列表；不依赖本地角色决定是否查询。 */
async function refreshFromServer(): Promise<void> {
  if (refreshing.value) return;
  refreshing.value = true;
  pageError.value = '';
  notice.value = '';
  try {
    const verified = await session.fetchMe();
    if (!verified) {
      if (session.status === 'guest') {
        await router.replace({ name: 'login' });
        return;
      }
      if (session.status === 'mustChangePassword') {
        await router.replace({ name: 'change-password' });
        return;
      }
      pageError.value = session.verificationMessage || '身份确认未完成，请重试。';
      return;
    }
    if (session.status === 'mustChangePassword') {
      await router.replace({ name: 'change-password' });
      return;
    }
    await loadList(true);
  } finally {
    refreshing.value = false;
  }
}

async function loadMore(): Promise<void> {
  if (!admin.hasMore || admin.listLoading) return;
  await loadList(false);
}

function applyDetailToDrafts(detail: RoleScopeDetail): void {
  for (const code of STAFF_ROLE_CODES) {
    const granted = detail.roles.find((role) => role.role_code === code);
    roleDrafts[code].enabled = granted !== undefined;
    roleDrafts[code].branchIds = granted ? granted.branch_ids.join(', ') : '';
    roleDrafts[code].approvalBasis = granted ? granted.approval_basis : '';
  }
  hasRoleDraft.value = false;
  draftOwnerAccountId = detail.account_id;
  draftSessionRevision = session.sessionRevision;
  rolesConfirm.value = false;
  revokeAllConfirmed.value = false;
}

function roleDetailBlockedMessage(reason?: string): string {
  if (reason === 'not-selected') return '当前已切换账号，未读取该账号的授权。';
  return blockedReasonMessage(reason as 'busy' | 'auth-changing' | 'not-ready' | 'not-admin' | undefined);
}

async function loadRoleDetail(accountId: string, preserveDrafts: boolean): Promise<boolean> {
  const editRevision = ++detailEditRevision;
  const sessionRevision = session.sessionRevision;
  const preserved =
    preserveDrafts &&
    hasRoleDraft.value &&
    draftOwnerAccountId === accountId &&
    draftSessionRevision === sessionRevision;
  roleLoadError.value = '';
  rolesError.value = '';
  rolesNotice.value = '';
  try {
    const outcome = await admin.loadRoleScopes(accountId);
    if (editRevision !== detailEditRevision) return false;
    if (session.sessionRevision !== sessionRevision) return false;
    if (admin.selectedAccountId !== accountId) return false;
    if (!outcome.executed) {
      roleLoadError.value = roleDetailBlockedMessage(outcome.reason);
      return false;
    }
    if (outcome.stale) return false;
    if (outcome.accountId !== accountId || !outcome.data) {
      roleLoadError.value = '未能读取当前授权，请重试。';
      return false;
    }
    const detail = outcome.data;
    versionConflict.value = false;
    // 重新读取后必须重新确认差异，不能沿用冲突前的确认。
    rolesConfirm.value = false;
    revokeAllConfirmed.value = false;
    if (preserved) {
      draftOwnerAccountId = accountId;
      draftSessionRevision = sessionRevision;
      hasRoleDraft.value = true;
    } else {
      applyDetailToDrafts(detail);
    }
    return true;
  } catch (e) {
    if (editRevision !== detailEditRevision) return false;
    if (session.sessionRevision !== sessionRevision || admin.selectedAccountId !== accountId) {
      return false;
    }
    if (await handle401(e)) {
      roleLoadError.value = afterAuthFailure();
      return false;
    }
    if (handlePermissionDenial(e, 'roles')) {
      roleLoadError.value = pageError.value;
      return false;
    }
    roleLoadError.value = accountAdminErrorMessage(e, 'roles');
    return false;
  }
}

async function selectAccount(account: StaffAccount): Promise<void> {
  const sameTarget = admin.selectedAccountId === account.account_id;
  admin.selectAccount(account.account_id);
  if (!sameTarget) {
    resetRoleDrafts();
    roleLoadError.value = '';
    versionConflict.value = false;
    revokeAllConfirmed.value = false;
  }
  cancelDisable();
  // 同目标重选不主动丢弃草稿；新详情成功后才按草稿是否存在决定回填。
  await loadRoleDetail(account.account_id, sameTarget && hasRoleDraft.value);
}

async function reloadRoles(): Promise<void> {
  if (!selectedAccount.value) return;
  await loadRoleDetail(selectedAccount.value.account_id, hasRoleDraft.value);
}

async function discardDraftAndReload(): Promise<void> {
  if (!selectedAccount.value) return;
  hasRoleDraft.value = false;
  resetRoleDrafts();
  versionConflict.value = false;
  await loadRoleDetail(selectedAccount.value.account_id, false);
}

function onRoleToggle(code: StaffRoleCode): void {
  if (code === 'sysadmin' && roleDrafts[code].enabled) {
    roleDrafts[code].branchIds = '';
  }
  markRoleDraft();
}

interface GrantBuildOk {
  ok: true;
  grants: RoleScopeGrant[];
}
interface GrantBuildError {
  ok: false;
  error: string;
}

function buildRoleGrants(): GrantBuildOk | GrantBuildError {
  const grants: RoleScopeGrant[] = [];
  for (const code of STAFF_ROLE_CODES) {
    const draft = roleDrafts[code];
    if (!draft.enabled) continue;
    const branchIds = code === 'sysadmin' ? [] : splitBranchIds(draft.branchIds);
    if (code === 'sysadmin' && draft.branchIds.trim() !== '') {
      return { ok: false, error: '系统管理员不能绑定门店范围，请清空该角色的门店编号。' };
    }
    if (branchIds.some((branchId) => !isPositiveIdText(branchId))) {
      return { ok: false, error: '门店编号必须是用逗号分隔的正整数编号。' };
    }
    const basis = draft.approvalBasis.trim();
    if (!basis) return { ok: false, error: `请填写“${roleLabel(code)}”的批准依据。` };
    if (basis.length > 300) return { ok: false, error: '批准依据不能超过 300 个字符。' };
    grants.push({ role_code: code, branch_ids: branchIds, approval_basis: basis });
  }
  return { ok: true, grants };
}

async function submitCreate(): Promise<void> {
  if (admin.createSubmitting) return;
  createError.value = '';
  createNotice.value = '';
  lastCreated.value = null;

  const staffId = createForm.staffId.trim();
  const loginName = normalizeStaffLoginName(createForm.loginName);
  if (!isPositiveIdText(staffId)) {
    createError.value = '员工编号必须为正整数编号。';
    return;
  }
  if (!isValidStaffLoginName(loginName)) {
    createError.value = '登录名需为 3-100 位小写字母、数字、点、下划线或连字符，且以字母或数字开头。';
    return;
  }
  createForm.loginName = loginName;

  // 同一逻辑内容沿用同一个幂等键；只有内容变化才开启新操作。
  const fingerprint = `${staffId}\u0000${loginName}`;
  if (!createKey || createKeyFingerprint !== fingerprint) {
    createKey = newIdempotencyKey();
    createKeyFingerprint = fingerprint;
  }

  try {
    const outcome = await admin.create({ staff_id: staffId, login_name: loginName }, createKey);
    if (!outcome.executed) {
      createError.value = blockedReasonMessage(outcome.reason);
      return;
    }
    if (outcome.stale) {
      createNotice.value = '请求可能已提交，但登录身份已变化，结果未确认；请重新进入页面核对。';
      return;
    }
    const created = outcome.data;
    if (!created) {
      createError.value = '创建结果缺少账号信息，请刷新列表核对。';
      return;
    }
    lastCreated.value = created;
    if (created.state === 'pending_activation') {
      createNotice.value =
        '已创建待激活账号。系统不提供默认密码或网页激活，请按现有本地激活流程处理。';
    } else if (created.state === 'active') {
      createNotice.value = '请求已重放；该账号当前为启用状态。';
    } else {
      createNotice.value = '请求已重放；该账号当前为停用状态。';
    }
    const currentFingerprint = `${createForm.staffId.trim()}\u0000${normalizeStaffLoginName(createForm.loginName)}`;
    if (currentFingerprint === fingerprint) {
      createForm.staffId = '';
      createForm.loginName = '';
    } else {
      createNotice.value += ' 当前表单内容已变化，未清空新输入。';
    }
    clearCreateKey();
    await loadList(true);
  } catch (e) {
    if (await handle401(e)) {
      createError.value = afterAuthFailure();
      return;
    }
    if (handlePermissionDenial(e, 'create')) return;
    // 保留输入和幂等键；超时/网络失败不自动重发，内容不变时重试复用原键。
    createError.value = accountAdminErrorMessage(e, 'create');
  }
}

async function submitRoles(): Promise<void> {
  if (admin.rolesSubmitting || !selectedAccount.value) return;
  if (!roleEditingEnabled.value || roleDetail.value === null) {
    rolesError.value = '请先成功读取该账号当前授权，再编辑角色门店。';
    return;
  }
  if (versionConflict.value) {
    rolesError.value = '授权已被其他操作更新，请先重新读取并比较后再保存。';
    return;
  }
  const targetId = selectedAccount.value.account_id;
  const targetName = selectedAccount.value.login_name;
  const expectedVersion = roleDetail.value.version;
  const editRevision = detailEditRevision;
  const sessionRevision = session.sessionRevision;
  rolesError.value = '';
  rolesNotice.value = '';
  if (!hasRoleChanges.value) {
    rolesError.value = '当前没有需要保存的角色门店变更。';
    return;
  }
  if (!rolesConfirm.value) {
    rolesError.value = '请先勾选“确认全量替换角色门店范围”。';
    return;
  }
  if (revokesAll.value && !revokeAllConfirmed.value) {
    rolesError.value = '撤销全部角色需要单独确认。';
    return;
  }
  const built = buildRoleGrants();
  if (!built.ok) {
    rolesError.value = built.error;
    return;
  }
  try {
    const outcome = await admin.setRoles(targetId, expectedVersion, built.grants);
    if (
      editRevision !== detailEditRevision ||
      session.sessionRevision !== sessionRevision ||
      admin.selectedAccountId !== targetId
    ) {
      notice.value = `账号 ${targetName} 的角色门店请求已返回；当前编辑或账号已变化，请重新核对。`;
      return;
    }
    if (!outcome.executed) {
      rolesError.value = blockedReasonMessage(outcome.reason);
      return;
    }
    if (outcome.stale) {
      rolesNotice.value = '请求可能已提交，但登录身份或当前账号已变化，结果未确认；请重新读取核对。';
      return;
    }
    rolesNotice.value = '已保存角色门店范围；目标账号旧会话已撤销。正在重新读取当前授权…';
    rolesConfirm.value = false;
    revokeAllConfirmed.value = false;
    const applied = await loadRoleDetail(targetId, false);
    if (applied) {
      rolesNotice.value = '已保存并重新读取当前授权；目标账号旧会话已撤销。';
    } else if (roleLoadError.value !== '') {
      rolesError.value = '角色门店已保存，但重新读取当前授权失败；请重试读取核对。';
    }
  } catch (e) {
    if (
      editRevision !== detailEditRevision ||
      session.sessionRevision !== sessionRevision ||
      admin.selectedAccountId !== targetId
    ) {
      return;
    }
    if (await handle401(e)) {
      rolesError.value = afterAuthFailure();
      return;
    }
    if (handlePermissionDenial(e, 'roles')) return;
    if (e instanceof ApiError && e.code === 'VERSION_CONFLICT') {
      versionConflict.value = true;
      rolesError.value = '授权已被其他操作更新，草稿已保留。请重新读取当前授权并比较后再保存。';
      return;
    }
    if (e instanceof ApiError && (e.code === 'TIMEOUT' || e.code === 'NETWORK_ERROR')) {
      // 超时/网络失败不能盲目重发旧全量覆盖；先 GET 当前授权核对，草稿保留。
      rolesError.value = '保存结果未确认，正在重新读取当前授权核对…';
      const applied = await loadRoleDetail(targetId, true);
      if (!applied) return;
      if (roleLoadError.value === '') {
        rolesError.value = '保存结果未确认；已重新读取当前授权，请比较草稿后再决定是否保存。';
      } else {
        rolesError.value = '保存结果未确认，且重新读取当前授权失败；请稍后重试读取。';
      }
      return;
    }
    rolesError.value = accountAdminErrorMessage(e, 'roles');
  }
}

function openDisable(): void {
  disableAttemptId += 1;
  disableOpen.value = true;
  disableError.value = '';
  disableReason.value = '';
  disableConfirm.value = false;
}

function cancelDisable(): void {
  disableAttemptId += 1;
  disableOpen.value = false;
  disableError.value = '';
  disableReason.value = '';
  disableConfirm.value = false;
}

async function submitDisable(): Promise<void> {
  if (admin.disableSubmitting || !selectedAccount.value) return;
  const target = selectedAccount.value;
  const targetId = target.account_id;
  const targetName = target.login_name;
  disableError.value = '';
  if (target.state === 'pending_activation') {
    disableError.value = '待激活账号不支持停用；请按现有本地激活流程处理。';
    return;
  }
  if (!disableReason.value.trim()) {
    disableError.value = '请填写停用原因。';
    return;
  }
  if (disableReason.value.trim().length > 300) {
    disableError.value = '停用原因不能超过 300 个字符。';
    return;
  }
  if (!disableConfirm.value) {
    disableError.value = '请勾选确认停用。';
    return;
  }
  const attempt = disableAttemptId;
  try {
    const outcome = await admin.disable(targetId, disableReason.value);
    if (!outcome.executed) {
      disableError.value = blockedReasonMessage(outcome.reason);
      return;
    }
    if (outcome.stale) {
      disableError.value = '请求可能已提交，但登录身份已变化，结果未确认；请重新进入页面核对。';
      return;
    }
    const samePanel = admin.selectedAccountId === targetId && disableAttemptId === attempt;
    if (samePanel) {
      notice.value = `已停用账号 ${targetName}，其会话已撤销。`;
      cancelDisable();
    } else {
      // 等待期间已切换账号、关闭或重新打开待办：只提示原目标，不关闭/清空新面板与草稿。
      notice.value = `账号 ${targetName} 的停用请求已返回；当前已切换账号或已关闭停用面板，请重新核对。`;
    }
    await loadList(true);
  } catch (e) {
    if (await handle401(e)) {
      disableError.value = afterAuthFailure();
      return;
    }
    if (handlePermissionDenial(e, 'disable')) return;
    disableError.value = accountAdminErrorMessage(e, 'disable');
  }
}

function goHome(): void {
  void router.push({ name: 'home' });
}

onMounted(() => {
  void refreshFromServer();
});
</script>

<template>
  <main class="admin-page">
    <header class="topbar">
      <div class="topbar-left">
        <button class="back-home" type="button" @click="goHome">返回首页</button>
        <span class="brand">账号与角色门店</span>
      </div>
      <button class="refresh-accounts" type="button" :disabled="refreshing" @click="refreshFromServer">
        {{ refreshing ? '刷新中…' : '刷新' }}
      </button>
    </header>

    <section class="content">
      <p v-if="pageError" class="error page-error" role="alert">{{ pageError }}</p>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>

      <section v-if="!identityReady" class="panel identity-panel">
        <h2>身份确认未完成</h2>
        <p class="warning">
          {{ pageError || session.verificationMessage || '身份确认未完成，请检查网络后重试。' }}
        </p>
        <p class="hint">为保护账号信息，身份未确认期间不显示任何缓存的账号列表或操作入口。</p>
        <button class="retry-identity" type="button" :disabled="refreshing" @click="refreshFromServer">
          {{ refreshing ? '核验中…' : '重试身份确认' }}
        </button>
      </section>

      <template v-else>
        <section class="panel">
          <h1>新建待激活员工账号</h1>
        <p class="hint">
          当前没有员工选择接口，请填写员工编号，由服务端核验在岗状态与门店任职。本系统不生成默认密码，也不提供网页激活。
        </p>
        <form class="form-grid" @submit.prevent="submitCreate">
          <label for="create-staff-id">员工编号</label>
          <input
            id="create-staff-id"
            v-model="createForm.staffId"
            inputmode="numeric"
            autocomplete="off"
            placeholder="例如 1001"
          />
          <label for="create-login-name">登录名</label>
          <input
            id="create-login-name"
            v-model="createForm.loginName"
            maxlength="100"
            autocomplete="off"
            placeholder="3-100 位小写字母/数字/._-"
          />
          <button class="create-submit" type="submit" :disabled="admin.createSubmitting">
            {{ admin.createSubmitting ? '创建中…' : '创建待激活账号' }}
          </button>
        </form>
        <p v-if="createError" class="error create-error" role="alert">{{ createError }}</p>
        <p v-if="createNotice" class="notice create-notice" role="status">{{ createNotice }}</p>
        <div v-if="lastCreated" class="created-result">
          <p>
            账号编号：{{ lastCreated.account_id }}；员工编号：{{ lastCreated.staff_id }}；状态：{{
              stateLabel(lastCreated.state)
            }}
          </p>
          <p v-if="lastCreated.state === 'pending_activation'" class="hint">
            该账号尚无密码，不能登录。请按现有本地激活流程处理；不要向操作员承诺默认密码或网页激活。
          </p>
        </div>
      </section>

      <section class="panel">
        <div class="panel-title-row">
          <h2>员工账号</h2>
          <button class="refresh-list" type="button" :disabled="admin.listLoading" @click="refreshFromServer">
            {{ admin.listLoading ? '加载中…' : '刷新列表' }}
          </button>
        </div>
        <p v-if="admin.accounts.length === 0 && !admin.listLoading" class="hint">暂无可见员工账号。</p>
        <table v-else class="account-table">
          <thead>
            <tr>
              <th>账号编号</th>
              <th>登录名</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="account in admin.accounts" :key="account.account_id" class="account-row">
              <td>{{ account.account_id }}</td>
              <td>{{ account.login_name }}</td>
              <td>
                <span class="state-badge" :data-state="account.state">{{ stateLabel(account.state) }}</span>
              </td>
              <td>
                <button class="manage-account" type="button" @click="selectAccount(account)">管理</button>
              </td>
            </tr>
          </tbody>
        </table>
        <button v-if="admin.hasMore" class="load-more" type="button" :disabled="admin.listLoading" @click="loadMore">
          {{ admin.listLoading ? '加载中…' : '加载更多' }}
        </button>
      </section>

      <section v-if="selectedAccount" class="panel role-panel">
        <h2>角色与门店：{{ selectedAccount.login_name }}（{{ selectedAccount.account_id }}）</h2>
        <p class="warning">
          先读取服务端当前授权，成功后开放编辑。保存为全量替换，必须携带读取到的版本；若其他管理员已修改，服务端返回 409，草稿会保留，需重新读取并比较后再保存。
        </p>
        <p v-if="isSelfSelected" class="warning">
          不能修改自己的角色；如提交，服务端会拒绝并保留当前状态。
        </p>
        <p v-if="roleLoading" class="hint role-loading">正在读取当前授权…</p>
        <p v-if="roleLoadError" class="error role-load-error" role="alert">{{ roleLoadError }}</p>
        <button v-if="roleLoadError" class="reload-roles" type="button" :disabled="roleLoading" @click="reloadRoles">
          重试读取当前授权
        </button>
        <button
          v-if="hasRoleDraft && roleLoadError"
          class="discard-draft"
          type="button"
          :disabled="roleLoading"
          @click="discardDraftAndReload"
        >
          放弃草稿并重新读取
        </button>
        <div v-if="versionConflict" class="warning version-conflict" role="alert">
          <p>授权已被其他操作更新，当前草稿已保留；请重新读取最新授权并比较后再保存。</p>
          <div class="draft-actions">
            <button class="reload-roles" type="button" :disabled="roleLoading" @click="reloadRoles">
              重新读取当前授权
            </button>
            <button
              class="discard-draft"
              type="button"
              :disabled="roleLoading"
              @click="discardDraftAndReload"
            >
              放弃草稿并重新读取
            </button>
          </div>
        </div>

        <form class="role-form" @submit.prevent="submitRoles">
          <fieldset v-for="code in STAFF_ROLE_CODES" :key="code" class="role-fieldset">
            <legend>
              <label>
                <input
                  :id="`role-${code}-enabled`"
                  v-model="roleDrafts[code].enabled"
                  type="checkbox"
                  :disabled="!roleEditingEnabled || admin.rolesSubmitting"
                  @change="onRoleToggle(code)"
                />
                {{ roleLabel(code) }}（{{ code }}）
              </label>
            </legend>
            <div v-if="roleDrafts[code].enabled" class="role-fields">
              <label :for="`role-${code}-branches`">门店编号（逗号分隔）</label>
              <input
                :id="`role-${code}-branches`"
                v-model="roleDrafts[code].branchIds"
                :disabled="!roleEditingEnabled || admin.rolesSubmitting || code === 'sysadmin'"
                :placeholder="code === 'sysadmin' ? '系统管理员不绑定门店' : '例如 1,2'"
                @input="markRoleDraft"
              />
              <label :for="`role-${code}-basis`">批准依据</label>
              <input
                :id="`role-${code}-basis`"
                v-model="roleDrafts[code].approvalBasis"
                :disabled="!roleEditingEnabled || admin.rolesSubmitting"
                maxlength="300"
                placeholder="记录负责人批准依据"
                @input="markRoleDraft"
              />
            </div>
          </fieldset>

          <div class="role-summary">
            <h3>提交摘要</h3>
            <p v-if="roleSummary.length === 0">当前草稿未授予任何角色，保存将撤销全部角色。</p>
            <ul v-else>
              <li v-for="item in roleSummary" :key="item.code">
                {{ item.label }}：{{ item.branchIds.length ? item.branchIds.join('、') : '不绑定门店' }}；依据：{{
                  item.basis || '（未填写）'
                }}
              </li>
            </ul>
          </div>

          <div class="role-diff">
            <h3>逐角色差异（原始 → 修改后）</h3>
            <p v-if="roleDiff.length === 0" class="hint">无变更。</p>
            <ul v-else>
              <li v-for="item in roleDiff" :key="item.code" class="role-diff-item">
                <strong>{{ item.label }}</strong>：{{ item.before }} → {{ item.after }}
              </li>
            </ul>
          </div>

          <label v-if="revokesAll" class="confirm-row revoke-all-confirm">
            <input
              id="revoke-all-confirm"
              v-model="revokeAllConfirmed"
              type="checkbox"
              :disabled="!roleEditingEnabled || admin.rolesSubmitting"
            />
            我确认撤销该账号的全部角色
          </label>
          <label class="confirm-row">
            <input
              id="roles-confirm"
              v-model="rolesConfirm"
              type="checkbox"
              :disabled="!roleEditingEnabled || !hasRoleChanges || admin.rolesSubmitting"
            />
            我确认按上述内容全量替换角色门店范围
          </label>
          <p v-if="rolesError" class="error roles-error" role="alert">{{ rolesError }}</p>
          <p v-if="rolesNotice" class="notice roles-notice" role="status">{{ rolesNotice }}</p>
          <button class="roles-submit" type="submit" :disabled="!canSaveRoles || admin.rolesSubmitting">
            {{ admin.rolesSubmitting ? '提交中…' : '保存角色门店范围' }}
          </button>
        </form>

        <section class="danger-zone">
          <h3>停用账号</h3>
          <p v-if="selectedAccount.state === 'pending_activation'" class="hint pending-disable-note">
            待激活账号不支持停用；请按现有本地激活流程处理。
          </p>
          <p v-else-if="selectedAccount.state === 'disabled'" class="hint">该账号已停用。</p>
          <template v-else>
            <button class="disable-open" type="button" @click="openDisable">停用账号…</button>
            <div v-if="disableOpen" class="disable-panel">
              <p class="warning">
                停用会立即撤销该账号全部会话并写入审计。请确认这是目标账号。
              </p>
              <label for="disable-reason">停用原因</label>
              <input id="disable-reason" v-model="disableReason" maxlength="300" placeholder="1-300 个字符" />
              <label class="confirm-row">
                <input id="disable-confirm" v-model="disableConfirm" type="checkbox" />
                我确认停用 {{ selectedAccount.login_name }}（{{ selectedAccount.account_id }}）
              </label>
              <p v-if="disableError" class="error disable-error" role="alert">{{ disableError }}</p>
              <div class="danger-actions">
                <button class="disable-submit" type="button" :disabled="admin.disableSubmitting" @click="submitDisable">
                  {{ admin.disableSubmitting ? '停用中…' : '确认停用' }}
                </button>
                <button class="disable-cancel" type="button" @click="cancelDisable">取消</button>
              </div>
            </div>
          </template>
        </section>
      </section>
      </template>
    </section>
  </main>
</template>

<style scoped>
.admin-page {
  min-height: 100vh;
  background: #ffffff;
  color: #1f3b3a;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1.5rem;
  border-bottom: 1px solid #e2e8e7;
}
.topbar-left {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.brand {
  font-weight: 600;
}
.back-home,
.refresh-accounts,
.refresh-list,
.manage-account,
.load-more,
.create-submit,
.roles-submit,
.disable-open,
.disable-submit,
.disable-cancel,
.retry-identity,
.reload-roles,
.discard-draft {
  min-height: 2.25rem;
  padding: 0 0.9rem;
  font-size: 0.875rem;
  color: #1f3b3a;
  background: #ffffff;
  border: 1px solid #cbd5d4;
  border-radius: 6px;
  cursor: pointer;
}
.back-home:hover:not(:disabled),
.refresh-accounts:hover:not(:disabled),
.refresh-list:hover:not(:disabled),
.manage-account:hover:not(:disabled),
.load-more:hover:not(:disabled),
.disable-cancel:hover:not(:disabled),
.retry-identity:hover:not(:disabled),
.reload-roles:hover:not(:disabled),
.discard-draft:hover:not(:disabled) {
  border-color: #2f9e9a;
  color: #2f9e9a;
}
.create-submit,
.roles-submit,
.disable-submit {
  color: #ffffff;
  background: #2f9e9a;
  border-color: #2f9e9a;
  font-weight: 600;
}
.create-submit:hover:not(:disabled),
.roles-submit:hover:not(:disabled),
.disable-submit:hover:not(:disabled) {
  background: #278884;
}
.disable-open,
.disable-submit {
  color: #a12a20;
  border-color: #d9a29c;
}
.disable-open:hover:not(:disabled) {
  color: #c0392b;
  border-color: #c0392b;
}
.disable-submit {
  color: #ffffff;
  background: #c0392b;
  border-color: #c0392b;
}
.disable-submit:hover:not(:disabled) {
  background: #a93226;
}
button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.content {
  max-width: 980px;
  margin: 1.5rem auto 3rem;
  padding: 0 1rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}
.panel {
  border: 1px solid #e2e8e7;
  border-radius: 8px;
  padding: 1rem 1.25rem;
}
.panel h1,
.panel h2 {
  font-size: 1.1rem;
  margin: 0 0 0.75rem;
}
.panel h3 {
  font-size: 0.95rem;
  margin: 0.75rem 0 0.5rem;
}
.panel-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.hint {
  color: #6b7b7a;
  font-size: 0.85rem;
  margin: 0 0 0.75rem;
}
.warning {
  color: #8a5a00;
  background: #fffaf0;
  border: 1px solid #e2c08d;
  border-radius: 6px;
  padding: 0.6rem 0.75rem;
  font-size: 0.85rem;
  margin: 0 0 0.75rem;
}
.error {
  color: #c0392b;
  font-size: 0.875rem;
  margin: 0.5rem 0;
}
.notice {
  color: #1f6f5c;
  font-size: 0.875rem;
  margin: 0.5rem 0;
}
.form-grid {
  display: grid;
  grid-template-columns: 7rem minmax(0, 1fr);
  gap: 0.6rem 0.75rem;
  align-items: center;
}
.form-grid label {
  font-size: 0.875rem;
  color: #2c3e3d;
}
.form-grid input,
.role-fields input,
.disable-panel input {
  min-height: 2.25rem;
  padding: 0 0.6rem;
  font-size: 0.9rem;
  border: 1px solid #cbd5d4;
  border-radius: 6px;
  color: #1f3b3a;
  background: #ffffff;
}
.form-grid input:focus,
.role-fields input:focus,
.disable-panel input:focus {
  outline: none;
  border-color: #2f9e9a;
  box-shadow: 0 0 0 2px rgba(47, 158, 154, 0.18);
}
.create-submit,
.roles-submit {
  grid-column: 2;
  justify-self: start;
  margin-top: 0.25rem;
}
.created-result {
  border-top: 1px solid #e2e8e7;
  margin-top: 0.75rem;
  padding-top: 0.75rem;
  font-size: 0.875rem;
}
.created-result p {
  margin: 0 0 0.4rem;
}
.account-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}
.account-table th,
.account-table td {
  text-align: left;
  border-bottom: 1px solid #e2e8e7;
  padding: 0.55rem 0.4rem;
}
.account-table th {
  color: #6b7b7a;
  font-weight: 500;
}
.state-badge {
  display: inline-block;
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  background: #eef4f3;
  font-size: 0.8rem;
}
.state-badge[data-state='active'] {
  background: #e6f4ec;
  color: #1f6f4a;
}
.state-badge[data-state='pending_activation'] {
  background: #fff4df;
  color: #8a5a00;
}
.state-badge[data-state='disabled'] {
  background: #f4e8e7;
  color: #a12a20;
}
.load-more {
  margin-top: 0.75rem;
}
.role-fieldset {
  border: 1px solid #e2e8e7;
  border-radius: 6px;
  margin: 0 0 0.75rem;
  padding: 0.5rem 0.75rem 0.75rem;
}
.role-fieldset legend {
  font-size: 0.875rem;
  padding: 0 0.25rem;
}
.role-fields {
  display: grid;
  grid-template-columns: 12rem minmax(0, 1fr);
  gap: 0.5rem 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
.role-fields label {
  font-size: 0.85rem;
  color: #2c3e3d;
}
.role-summary {
  border: 1px solid #e2e8e7;
  border-radius: 6px;
  padding: 0.6rem 0.75rem;
  margin: 0.75rem 0;
  font-size: 0.875rem;
}
.role-summary h3 {
  margin-top: 0;
}
.role-summary ul {
  margin: 0;
  padding-left: 1.25rem;
}
.role-diff {
  border: 1px solid #e2e8e7;
  border-radius: 6px;
  padding: 0.6rem 0.75rem;
  margin: 0.75rem 0;
  font-size: 0.875rem;
}
.role-diff h3 {
  margin-top: 0;
}
.role-diff ul {
  margin: 0;
  padding-left: 1.25rem;
}
.role-diff-item {
  margin: 0.25rem 0;
}
.version-conflict {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.version-conflict p {
  margin: 0;
}
.draft-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.reload-roles,
.discard-draft {
  align-self: flex-start;
}
.confirm-row {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.875rem;
  margin: 0.6rem 0;
}
.danger-zone {
  border-top: 1px solid #e2e8e7;
  margin-top: 1rem;
  padding-top: 0.5rem;
}
.disable-panel {
  border: 1px solid #d9a29c;
  border-radius: 6px;
  padding: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
.disable-panel label {
  font-size: 0.875rem;
}
.danger-actions {
  display: flex;
  gap: 0.5rem;
}
.pending-disable-note {
  margin-bottom: 0;
}
@media (max-width: 640px) {
  .form-grid,
  .role-fields {
    grid-template-columns: 1fr;
  }
  .create-submit,
  .roles-submit {
    grid-column: 1;
  }
}
</style>
