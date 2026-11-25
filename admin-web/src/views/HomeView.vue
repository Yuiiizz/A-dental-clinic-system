<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import { branchScopeText, roleLabel } from '@/services/session';
import { ApiError } from '@/services/http';

const router = useRouter();
const store = useSessionStore();

const logoutError = ref('');

const roles = computed(() => store.me?.roles ?? []);
const hasIdentity = computed(() => store.me !== null);

async function logout(): Promise<void> {
  if (store.logoutSubmitting || store.authSubmitting) return; // 防重复提交与认证变更互斥
  logoutError.value = '';
  try {
    const outcome = await store.logout();
    if (!outcome.executed) {
      logoutError.value = '正在处理其他认证操作，请稍后再试。';
      return;
    }
    if (outcome.superseded) {
      logoutError.value = '本次退出结果已失效，请确认当前状态。';
      return;
    }
    if (!outcome.ok) {
      logoutError.value = '退出未确认，请重试。';
      return;
    }
    await router.replace({ name: 'login' });
  } catch (e) {
    if (e instanceof ApiError && (e.code === 'CSRF_MISSING' || e.status === 403)) {
      logoutError.value = '登录状态异常，请重新登录。';
    } else {
      logoutError.value = '退出未确认，请重试。';
    }
  }
}

async function relogin(): Promise<void> {
  store.clearSession();
  await router.replace({ name: 'login' });
}

function openAccounts(): void {
  void router.push({ name: 'admin-accounts' });
}
</script>

<template>
  <main class="home-page">
    <header class="topbar">
      <span class="brand">A口腔诊所系统 · 管理端</span>
      <div class="topbar-actions">
        <RouterLink v-if="roles.some(r => r.role_code === 'owner')" to="/branches">门店管理</RouterLink>
        <RouterLink v-if="roles.some(r => r.role_code === 'owner')" to="/staff-members">员工管理</RouterLink>
        <button
          v-if="store.isSysadmin"
          class="accounts-entry"
          type="button"
          @click="openAccounts"
        >
          账号管理
        </button>
        <button
          v-if="hasIdentity"
          class="logout"
          type="button"
          :disabled="store.logoutSubmitting || store.authSubmitting"
          @click="logout"
        >
          {{ store.logoutSubmitting ? '退出中…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section class="content">
      <h1 class="heading">欢迎使用</h1>

      <template v-if="hasIdentity">
        <div class="identity-card">
          <div class="identity-row">
            <span class="label">姓名</span>
            <span class="value">{{ store.me?.name }}</span>
          </div>
          <div class="identity-row">
            <span class="label">账号编号</span>
            <span class="value">{{ store.me?.account_id }}</span>
          </div>
          <div class="identity-row">
            <span class="label">角色</span>
            <div class="value">
              <ul v-if="roles.length" class="role-list">
                <li v-for="role in roles" :key="role.role_code">
                  {{ roleLabel(role.role_code) }}（{{ branchScopeText(role.branch_ids) }}）
                </li>
              </ul>
              <span v-else class="muted">无角色</span>
            </div>
          </div>
        </div>
      </template>

      <div v-else class="identity-card">
        <p class="muted">身份信息加载失败，请刷新页面重试。</p>
        <button class="logout" type="button" @click="relogin">重新登录</button>
      </div>

      <p v-if="logoutError" class="error" role="alert">{{ logoutError }}</p>
    </section>
  </main>
</template>

<style scoped>
.home-page {
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
.brand {
  font-weight: 600;
}
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.accounts-entry,
.logout {
  height: 2.25rem;
  padding: 0 1rem;
  font-size: 0.875rem;
  color: #1f3b3a;
  background: #ffffff;
  border: 1px solid #cbd5d4;
  border-radius: 6px;
  cursor: pointer;
}
.accounts-entry:hover:not(:disabled),
.logout:hover:not(:disabled) {
  border-color: #2f9e9a;
  color: #2f9e9a;
}
.accounts-entry:disabled,
.logout:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.content {
  max-width: 640px;
  margin: 2rem auto;
  padding: 0 1rem;
}
.heading {
  font-size: 1.25rem;
  font-weight: 600;
  margin: 0 0 1.25rem;
}
.identity-card {
  border: 1px solid #e2e8e7;
  border-radius: 8px;
  padding: 1rem 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.identity-row {
  display: flex;
  gap: 1rem;
}
.identity-row .label {
  flex: 0 0 5rem;
  color: #6b7b7a;
}
.identity-row .value {
  color: #1f3b3a;
}
.role-list {
  margin: 0;
  padding-left: 1.25rem;
}
.muted {
  color: #6b7b7a;
}
.error {
  margin-top: 1rem;
  font-size: 0.875rem;
  color: #c0392b;
}
</style>
