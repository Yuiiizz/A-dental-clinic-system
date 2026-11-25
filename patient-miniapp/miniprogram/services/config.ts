// 患者小程序 API 基础地址（可配置，不写死生产域名）。
//
// 普通 wx.request 生产环境须配置合法 HTTPS 域名；AnyService 可通过云开发接入现有轻量服务器。
// 本地调试如指向本机，请显式填入开发地址，并在微信开发者工具勾选“不校验合法域名”，
// 不要将 localhost 作为生产默认值。
//
// 留空时，http 客户端会在发起请求时明确报错（API 地址未配置），避免误连错误地址。
function configuredBaseUrl(): string {
  try {
    const ext = (wx as unknown as { getExtConfigSync?: () => { apiBaseUrl?: string } }).getExtConfigSync?.();
    if (ext?.apiBaseUrl) return ext.apiBaseUrl;
    const stored = wx.getStorageSync('a-clinic.apiBaseUrl');
    if (typeof stored === 'string' && stored.trim()) return stored.trim();
  } catch { /* noop */ }
  return '';
}

export const API_BASE_URL = configuredBaseUrl();

// AnyService 配置不含密钥。只有两个值同时存在时，默认 HTTP 客户端才启用云调用。
// 发布前可在小程序配置中设置，也可由受控的 extConfig 提供；不要从 Storage 读取生产云路由。
function cloudAccess(): { envId: string; serviceName: string } | null {
  // 当前环境的公开路由标识；不包含密钥。上线前仍须验证登录与写入流程。
  const releaseEnvId = 'isolated-clownfish-d6c8z4c7a3d55';
  const releaseServiceName = 'dentalapi';
  try {
    const ext = (wx as unknown as { getExtConfigSync?: () => { cloudEnvId?: string; anyServiceName?: string } }).getExtConfigSync?.();
    const envId = releaseEnvId || ext?.cloudEnvId?.trim() || '';
    const serviceName = releaseServiceName || ext?.anyServiceName?.trim() || '';
    if (envId && serviceName) return { envId, serviceName };
  } catch { /* noop */ }
  return null;
}

export const CLOUD_ACCESS = cloudAccess();
