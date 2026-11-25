<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useSessionStore } from '@/stores/session';
import { authErrorMessage } from '@/services/auth-error';
import { ApiError } from '@/services/http';
import { validateNewPassword } from '@/services/password-policy';

const router = useRouter();
const store = useSessionStore();

const form = reactive({ current: '', next: '', confirm: '' });
const errorMessage = ref('');
const reLoginRequired = ref(false);
const logoutError = ref('');
// 身份核验未完成时，页面不自动重发改密请求，只允许用户主动重试 /auth/me。
const verificationPending = ref(false);
const verificationAfterChange = ref(false);

const busy = computed(() => store.authSubmitting);
const submitDisabled = computed(() => busy.value || verificationPending.value);

function clearPasswords(): void {
  form.current = '';
  form.next = '';
  form.confirm = '';
}

// 提交失败/完成或离开页面都清空密码，包括当前密码。
onBeforeUnmount(clearPasswords);

function validate(): string | null {
  if (!form.current) return '请输入当前密码。';
  // 下限 12 个 Unicode 码点、上限 72 个 UTF-8 字节，两次一致（服务端仍为最终依据）。
  return validateNewPassword(form.next, form.confirm);
}

/** 身份核验重试：改密已成功则确认后进首页；仅探测会话则回到可再次提交的表单。 */
async function retryVerify(): Promise<void> {
  if (busy.value) return;
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
    if (outcome.status === 'ready') {
      verificationPending.value = false;
      if (verificationAfterChange.value) {
        await router.replace({ name: 'home' });
      } else {
        errorMessage.value = '身份已确认，请重新提交修改密码。';
      }
      return;
    }
    if (outcome.status === 'mustChangePassword') {
      verificationPending.value = false;
      if (verificationAfterChange.value) {
        reLoginRequired.value = true;
        errorMessage.value = '密码已更新，请重新登录确认。';
      } else {
        errorMessage.value = '身份已确认，请重新提交修改密码。';
      }
      return;
    }
    if (outcome.status === 'guest') {
      reLoginRequired.value = true;
      errorMessage.value = '登录已失效，请重新登录。';
      return;
    }
    errorMessage.value = '身份确认未完成，请检查网络后重试。';
  } catch (e) {
    errorMessage.value = authErrorMessage(e, 'change');
  }
}

async function submit(): Promise<void> {
  if (busy.value || verificationPending.value) return; // 认证变更互斥
  errorMessage.value = '';
  reLoginRequired.value = false;
  logoutError.value = '';
  const invalid = validate();
  if (invalid) {
    errorMessage.value = invalid;
    return;
  }
  try {
    const outcome = await store.changePassword(form.current, form.next);
    clearPasswords();
    if (!outcome.executed) {
      errorMessage.value = '正在处理其他认证操作，请稍后再试。';
      return;
    }
    if (outcome.superseded) {
      // 迟到的旧结果不得写成功状态或跳页。
      errorMessage.value = '本次改密结果已被新的认证操作取代，请确认当前状态。';
      return;
    }
    if (outcome.result === 'password-rejected') {
      errorMessage.value = '当前密码不正确，请重试。';
      return;
    }
    if (outcome.result === 'session-invalid') {
      reLoginRequired.value = true;
      errorMessage.value = '登录已失效，请重新登录。';
      return;
    }
    if (outcome.result === 'unconfirmed') {
      verificationPending.value = true;
      verificationAfterChange.value = false;
      errorMessage.value = '身份确认未完成，请检查网络后重试。';
      return;
    }
    // result === 'changed'
    if (outcome.status === 'ready') {
      await router.replace({ name: 'home' });
      return;
    }
    if (outcome.status === 'guest') {
      reLoginRequired.value = true;
      errorMessage.value = '密码已更新，请重新登录。';
      return;
    }
    // 密码已改但身份核验失败：不自动重发改密，先让用户重试身份确认。
    verificationPending.value = true;
    verificationAfterChange.value = true;
    errorMessage.value = '密码已更新，但身份确认未完成，请重试。';
  } catch (e) {
    clearPasswords();
    if (e instanceof ApiError && e.code === 'CSRF_MISSING') {
      reLoginRequired.value = true;
      errorMessage.value = authErrorMessage(e, 'change');
    } else {
      errorMessage.value = authErrorMessage(e, 'change');
    }
  }
}

async function relogin(): Promise<void> {
  store.clearSession();
  await router.replace({ name: 'login' });
}

// 强制改密账号可在此退出：仅服务端成功后才宣称退出，失败提示“退出未确认”可重试。
async function logout(): Promise<void> {
  if (busy.value) return; // 与改密共用互斥闸门
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
      reLoginRequired.value = true;
      errorMessage.value = '登录状态异常，请重新登录。';
    } else {
      logoutError.value = '退出未确认，请重试。';
    }
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-card">
      <h1 class="auth-title">修改密码</h1>
      <p class="auth-subtitle">首次登录需设置新密码后才能使用系统</p>

      <div v-if="reLoginRequired" class="relogin" role="alert">
        <p>{{ errorMessage || '登录状态异常，请重新登录。' }}</p>
        <button class="submit" type="button" @click="relogin">重新登录</button>
      </div>

      <form v-else class="auth-form" @submit.prevent="submit" novalidate>
        <div class="field">
          <label for="current-password">当前密码</label>
          <input
            id="current-password"
            v-model="form.current"
            type="password"
            autocomplete="current-password"
            placeholder="请输入当前密码"
          />
        </div>

        <div class="field">
          <label for="new-password">新密码</label>
          <input
            id="new-password"
            v-model="form.next"
            type="password"
            autocomplete="new-password"
            placeholder="至少 12 个字符（最多 72 字节）"
          />
        </div>

        <div class="field">
          <label for="confirm-password">确认新密码</label>
          <input
            id="confirm-password"
            v-model="form.confirm"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入新密码"
          />
        </div>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <p v-if="logoutError" class="form-error" role="alert">{{ logoutError }}</p>

        <button
          v-if="verificationPending"
          class="submit"
          type="button"
          :disabled="busy"
          @click="retryVerify"
        >
          {{ busy ? '核验中…' : '重试身份确认' }}
        </button>

        <button class="submit" type="submit" :disabled="submitDisabled">
          {{ busy ? '提交中…' : '保存新密码' }}
        </button>
        <button class="text-button" type="button" :disabled="busy" @click="logout">
          {{ busy ? '处理中…' : '退出登录' }}
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
.relogin {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  font-size: 0.875rem;
  color: #c0392b;
}
.relogin p {
  margin: 0;
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
.text-button {
  align-self: center;
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
</style>
