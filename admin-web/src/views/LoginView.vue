<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import { authErrorMessage } from '@/services/auth-error';

const router = useRouter();
const store = useSessionStore();

const form = reactive({ loginName: '', password: '' });
const errorMessage = ref('');

const submitDisabled = computed(() => store.loginSubmitting || store.authSubmitting);
// 登录凭据通过但身份核验失败、或刷新恢复失败：必须是可见状态，不能悄悄进首页。
// 用普通函数而非 computed：store 状态来自 Pinia，模板重渲染时重新读取即可。
function verificationNotice(): string {
  if (store.status === 'unverified') {
    return store.verificationMessage || '身份确认未完成，请检查网络后重试。';
  }
  if (store.verificationIssue === 'non-staff') {
    return store.verificationMessage || '当前登录身份不是员工账号，请使用员工账号登录。';
  }
  return '';
}

function canRetryVerify(): boolean {
  return store.status === 'unverified';
}

function clearPassword(): void {
  form.password = '';
}

// 离开页面（含路由跳转、组件卸载）也清空密码，不在内存表单里残留。
onBeforeUnmount(clearPassword);

/** 根据本次操作返回的已确认状态跳转；页面自身不臆造成功状态。 */
async function goByStatus(status: string): Promise<void> {
  if (status === 'mustChangePassword') {
    await router.replace({ name: 'change-password' });
  } else if (status === 'ready') {
    await router.replace({ name: 'home' });
  } else if (store.verificationIssue === 'non-staff') {
    // 由上方 verificationNotice 面板展示“非员工身份”，不冒充登录成功。
  } else if (status !== 'unverified') {
    // 罕见：登录成功但会话未生效（如立刻失效）。
    errorMessage.value = '登录未生效，请重试。';
  }
}

async function submit(): Promise<void> {
  if (submitDisabled.value) return; // 认证变更互斥（按钮禁用之外的第二层）
  errorMessage.value = '';
  const loginName = form.loginName.trim();
  if (!loginName || !form.password) {
    errorMessage.value = '请输入用户名和密码。';
    return;
  }
  try {
    const outcome = await store.login(loginName, form.password);
    clearPassword();
    if (!outcome.executed) {
      errorMessage.value = '正在处理其他认证操作，请稍后再试。';
      return;
    }
    if (outcome.superseded) {
      // 迟到的旧结果不得按新会话状态跳页。
      errorMessage.value = '本次登录已被新的认证操作取代，请重试。';
      return;
    }
    await goByStatus(outcome.status);
  } catch (e) {
    clearPassword(); // 失败也清空密码，不残留
    errorMessage.value = authErrorMessage(e, 'login');
  }
}

/** 登录后 /auth/me 失败 / 刷新恢复失败的重试入口；不重新提交密码。 */
async function retryVerify(): Promise<void> {
  if (store.authSubmitting) return;
  errorMessage.value = '';
  try {
    const outcome = await store.retryVerify();
    if (!outcome.executed) {
      errorMessage.value = '正在处理其他认证操作，请稍后再试。';
      return;
    }
    if (outcome.superseded) {
      errorMessage.value = '身份核验已被新的操作取代，请重试。';
      return;
    }
    await goByStatus(outcome.status);
  } catch (e) {
    errorMessage.value = authErrorMessage(e, 'login');
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1 class="auth-title">A口腔诊所系统 · 管理端</h1>
      <p class="auth-subtitle">请使用员工账号登录</p>

      <div v-if="verificationNotice()" class="verification" role="alert">
        <p class="verification-text">{{ verificationNotice() }}</p>
        <button
          v-if="canRetryVerify()"
          class="text-button"
          type="button"
          :disabled="store.authSubmitting"
          @click="retryVerify"
        >
          {{ store.authSubmitting ? '核验中…' : '重试身份确认' }}
        </button>
      </div>

      <form class="auth-form" @submit.prevent="submit" novalidate>
        <div class="field">
          <label for="login-name">用户名</label>
          <input
            id="login-name"
            v-model="form.loginName"
            name="username"
            type="text"
            autocomplete="username"
            maxlength="100"
            placeholder="请输入用户名"
          />
        </div>

        <div class="field">
          <label for="login-password">密码</label>
          <input
            id="login-password"
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </div>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

        <button class="submit" type="submit" :disabled="submitDisabled">
          {{ store.loginSubmitting ? '登录中…' : '登录' }}
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ffffff;
  padding: 1rem;
}
.auth-card {
  width: 100%;
  max-width: 360px;
}
.auth-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: #1f3b3a;
  margin: 0 0 0.25rem;
}
.auth-subtitle {
  color: #6b7b7a;
  margin: 0 0 1.5rem;
}
.auth-form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.field label {
  font-size: 0.875rem;
  color: #2c3e3d;
}
.field input {
  height: 2.5rem;
  padding: 0 0.75rem;
  font-size: 0.9375rem;
  border: 1px solid #cbd5d4;
  border-radius: 6px;
  color: #1f3b3a;
  background: #ffffff;
}
.field input:focus {
  outline: none;
  border-color: #2f9e9a;
  box-shadow: 0 0 0 2px rgba(47, 158, 154, 0.18);
}
.form-error {
  margin: 0;
  font-size: 0.875rem;
  color: #c0392b;
}
.verification {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 1rem;
  padding: 0.75rem;
  border: 1px solid #e2c08d;
  border-radius: 6px;
  background: #fffaf0;
}
.verification-text {
  margin: 0;
  font-size: 0.875rem;
  color: #8a5a00;
}
.text-button {
  align-self: flex-start;
  padding: 0.25rem 0.5rem;
  font-size: 0.875rem;
  color: #6b7b7a;
  background: none;
  border: none;
  cursor: pointer;
}
.text-button:hover:not(:disabled) {
  color: #2f9e9a;
  text-decoration: underline;
}
.text-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.submit {
  height: 2.5rem;
  font-size: 0.9375rem;
  font-weight: 600;
  color: #ffffff;
  background: #2f9e9a;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.submit:hover:not(:disabled) {
  background: #278884;
}
.submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
