<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ApiError, createHttpClient } from '@/services/http';
import { createInternalMessagesApi, type InternalContact, type InternalMessage } from '@/services/internal-messages';
import { useSessionStore } from '@/stores/session';

const session = useSessionStore();
const api = createInternalMessagesApi(createHttpClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  getCsrfToken: () => session.csrfToken,
}));
const contacts = ref<InternalContact[]>([]);
const selected = ref<InternalContact | null>(null);
const messages = ref<InternalMessage[]>([]);
const cursor = ref<string | null>(null);
const draft = ref('');
const loadingContacts = ref(false);
const loadingMessages = ref(false);
const sending = ref(false);
const error = ref('');
const notice = ref('');
let generation = 0;
let threadRequestId = 0;
let timer: ReturnType<typeof setInterval> | undefined;
const me = computed(() => session.me?.account_id ?? '');
const selectedKey = computed(() => selected.value ? `${selected.value.branch_id}:${selected.value.peer_staff_id}` : '');

function friendly(errorValue: unknown, fallback: string): string {
  if (errorValue instanceof ApiError) {
    if (errorValue.status === 401) return '登录状态已变化，请重新确认身份。';
    if (errorValue.status === 403 || errorValue.status === 404) return '当前门店沟通权限或联系人已变化，请刷新联系人。';
    return errorValue.message;
  }
  return fallback;
}

async function loadContacts(): Promise<void> {
  const token = ++generation;
  threadRequestId++;
  loadingContacts.value = true;
  loadingMessages.value = false;
  sending.value = false;
  error.value = '';
  try {
    const rows = await api.contacts();
    if (token !== generation) return;
    contacts.value = rows;
    if (selected.value) {
      selected.value = rows.find(row => `${row.branch_id}:${row.peer_staff_id}` === selectedKey.value) ?? null;
    }
    if (!selected.value && rows.length) selected.value = rows[0];
    if (selected.value) await loadMessages();
    else { messages.value = []; cursor.value = null; }
  } catch (e) {
    if (token === generation) { contacts.value = []; selected.value = null; messages.value = []; error.value = friendly(e, '联系人读取失败。'); }
  } finally {
    if (token === generation) loadingContacts.value = false;
  }
}

async function loadMessages(older = false): Promise<void> {
  const target = selected.value;
  if (!target || (older && !cursor.value)) return;
  const token = generation;
  const requestId = ++threadRequestId;
  const key = `${target.branch_id}:${target.peer_staff_id}`;
  loadingMessages.value = true;
  try {
    const page = await api.messages(target.branch_id, target.peer_staff_id, older ? cursor.value ?? undefined : undefined);
    if (token !== generation || requestId !== threadRequestId || key !== selectedKey.value) return;
    messages.value = older ? [...page.items, ...messages.value] : page.items;
    cursor.value = page.nextCursor;
    if (!older) error.value = '';
  } catch (e) {
    if (token === generation && requestId === threadRequestId && key === selectedKey.value) error.value = friendly(e, '消息读取失败。');
  } finally {
    if (token === generation && requestId === threadRequestId && key === selectedKey.value) loadingMessages.value = false;
  }
}

function choose(target: InternalContact): void {
  if (selectedKey.value === `${target.branch_id}:${target.peer_staff_id}`) return;
  generation++;
  threadRequestId++;
  sending.value = false;
  loadingMessages.value = false;
  selected.value = target;
  messages.value = [];
  cursor.value = null;
  draft.value = '';
  error.value = '';
  notice.value = '';
  void loadMessages();
}

async function send(): Promise<void> {
  const target = selected.value;
  if (!target || sending.value) return;
  const content = draft.value.trim();
  if (!content || content.length > 500) { error.value = '请输入 1–500 字的消息。'; return; }
  const token = generation;
  const key = selectedKey.value;
  sending.value = true;
  error.value = '';
  notice.value = '';
  try {
    await api.send(target.branch_id, target.peer_staff_id, content);
    if (token !== generation || key !== selectedKey.value) return;
    draft.value = '';
    notice.value = '消息已保存，等待对方查看。';
    await loadMessages();
  } catch (e) {
    if (token === generation && key === selectedKey.value) {
      error.value = friendly(e, '发送结果未确认。请保留原文，稍后手动重试；系统会使用同一幂等键。');
    }
  } finally {
    if (token === generation && key === selectedKey.value) sending.value = false;
  }
}

function displayTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '时间待核实' : date.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
}

watch(() => session.sessionRevision, () => {
  generation++;
  threadRequestId++;
  api.clearPending();
  contacts.value = []; selected.value = null; messages.value = []; cursor.value = null; draft.value = '';
  sending.value = false; loadingMessages.value = false; loadingContacts.value = false;
  if (session.status === 'ready') void loadContacts();
});
onMounted(() => {
  void loadContacts();
  timer = setInterval(() => { if (document.visibilityState === 'visible' && selected.value && !sending.value && !loadingMessages.value) void loadMessages(); }, 12000);
});
onUnmounted(() => { generation++; if (timer) clearInterval(timer); });
</script>

<template>
  <main class="message-page">
    <header class="message-header">
      <div><RouterLink to="/">← 返回工作台</RouterLink><h1>店内沟通</h1><p>前台与本店医生的内部工作消息</p></div>
      <span class="live-badge">真实接口</span>
    </header>
    <div class="rule">消息仅用于沟通。改约、替诊、停诊、收费等变更须到对应业务页面正式办理；请用预约编号定位事项，避免粘贴证件号和完整病历。</div>
    <div class="message-layout">
      <aside class="contact-pane">
        <div class="pane-heading"><h2>联系人</h2><button type="button" :disabled="loadingContacts" @click="loadContacts">刷新</button></div>
        <p v-if="loadingContacts" class="hint">正在读取…</p>
        <p v-if="!loadingContacts && contacts.length === 0" class="hint">当前没有同店且可使用系统的前台或医生。</p>
        <button v-for="contact in contacts" :key="`${contact.branch_id}:${contact.peer_staff_id}`" type="button"
          class="contact" :class="{ active: selectedKey === `${contact.branch_id}:${contact.peer_staff_id}` }" @click="choose(contact)">
          <span class="avatar">{{ contact.peer_name.slice(0, 1) }}</span>
          <span><strong>{{ contact.peer_name }}</strong><small>{{ contact.branch_name }} · {{ contact.actor_role === 'frontdesk' ? '医生' : '前台' }}</small></span>
        </button>
      </aside>
      <section class="thread-pane">
        <template v-if="selected">
          <div class="thread-heading"><div><h2>{{ selected.peer_name }}</h2><p>{{ selected.branch_name }} · 每 12 秒检查新消息</p></div><button type="button" :disabled="loadingMessages" @click="loadMessages()">刷新消息</button></div>
          <div class="thread-scroll">
            <button v-if="cursor" type="button" class="older" :disabled="loadingMessages" @click="loadMessages(true)">读取更早消息</button>
            <p v-if="!loadingMessages && messages.length === 0" class="empty">还没有消息。可从预约编号和需要协商的事项开始。</p>
            <article v-for="message in messages" :key="message.message_id" class="bubble-row" :class="{ mine: message.sender_account_id === me }">
              <div class="bubble"><p>{{ message.body }}</p><time>{{ displayTime(message.sent_at) }}</time></div>
            </article>
          </div>
          <form class="composer" @submit.prevent="send">
            <label for="message-draft">发送工作消息</label>
            <textarea id="message-draft" v-model="draft" maxlength="500" rows="3" placeholder="例如：预约 123 的替诊方案待患者确认，请先核对排班。" :disabled="sending" />
            <div class="composer-foot"><small>{{ draft.length }}/500 · 发送后不可编辑或删除</small><button type="submit" :disabled="sending || !draft.trim()">{{ sending ? '发送中…' : '发送消息' }}</button></div>
          </form>
        </template>
        <div v-else class="empty-state">选择联系人后查看消息</div>
      </section>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
  </main>
</template>

<style scoped>
.message-page{min-height:100vh;padding:28px;background:#f4faf8;color:#203f3b;font-family:inherit}.message-header{display:flex;justify-content:space-between;align-items:center;max-width:1280px;margin:0 auto 18px}.message-header a{color:#368c81;text-decoration:none;font-size:14px}.message-header h1{font-size:30px;margin:10px 0 4px}.message-header p,.thread-heading p{margin:0;color:#6b8580}.live-badge{padding:8px 13px;border-radius:999px;background:#def5ec;color:#247c65;font-weight:600}.rule{max-width:1280px;margin:0 auto 18px;padding:14px 18px;border:1px solid #d5e8df;border-radius:14px;background:#fffef7;line-height:1.6;color:#667164}.message-layout{display:grid;grid-template-columns:300px minmax(0,1fr);max-width:1280px;height:min(72vh,760px);min-height:540px;margin:auto;background:#fff;border:1px solid #dcebe7;border-radius:22px;overflow:hidden;box-shadow:0 16px 42px #24554b12}.contact-pane{overflow:auto;border-right:1px solid #e3eeea}.pane-heading,.thread-heading{display:flex;align-items:center;justify-content:space-between;padding:18px 22px;border-bottom:1px solid #e8f0ed}.pane-heading h2,.thread-heading h2{font-size:18px;margin:0}.pane-heading button,.thread-heading button,.older{border:0;background:transparent;color:#2f8e80;cursor:pointer}.contact{display:flex;align-items:center;gap:12px;width:100%;padding:15px 19px;border:0;border-bottom:1px solid #edf3f1;background:#fff;text-align:left;color:inherit;cursor:pointer}.contact:hover,.contact.active{background:#eaf8f3}.avatar{display:grid;place-items:center;width:42px;height:42px;border-radius:14px;background:#cdeee3;color:#277b69;font-weight:700}.contact span:last-child{display:flex;flex-direction:column;gap:4px}.contact small{color:#71918a}.thread-pane{display:flex;flex-direction:column;min-width:0}.thread-scroll{flex:1;overflow:auto;padding:18px 24px;background:linear-gradient(#fbfefd,#f6fbf8)}.bubble-row{display:flex;margin:12px 0}.bubble-row.mine{justify-content:flex-end}.bubble{max-width:min(76%,580px);padding:12px 15px;border:1px solid #e0ebe7;border-radius:18px 18px 18px 5px;background:white;white-space:pre-wrap;overflow-wrap:anywhere}.mine .bubble{background:#dff4eb;border-color:#c7e9da;border-radius:18px 18px 5px 18px}.bubble p{margin:0;line-height:1.55}.bubble time{display:block;margin-top:6px;text-align:right;color:#7a9189;font-size:11px}.composer{padding:16px 22px;border-top:1px solid #e2ece8}.composer label{display:block;margin-bottom:8px;font-weight:600}.composer textarea{box-sizing:border-box;width:100%;padding:12px;border:1px solid #cddfd8;border-radius:12px;resize:vertical;font:inherit}.composer textarea:focus{outline:2px solid #78c8b4}.composer-foot{display:flex;align-items:center;justify-content:space-between;margin-top:9px}.composer-foot small,.hint,.empty{color:#78908a}.composer-foot button{padding:10px 22px;border:0;border-radius:11px;background:#2a9a82;color:white;cursor:pointer}.composer-foot button:disabled{opacity:.5;cursor:not-allowed}.empty-state{display:grid;place-items:center;flex:1;color:#839b94}.empty{text-align:center;margin:50px 0}.older{display:block;margin:0 auto}.error,.notice{max-width:1280px;margin:12px auto;padding:12px;border-radius:10px}.error{background:#fff0ed;color:#a43f36}.notice{background:#e6f7ee;color:#24765b}@media(max-width:700px){.message-page{padding:14px}.message-layout{grid-template-columns:1fr;height:auto}.contact-pane{max-height:230px;border-right:0;border-bottom:1px solid #e3eeea}.thread-pane{min-height:520px}}
</style>
