// 页面生命周期上下文：onHide/onUnload 使旧异步结果失效；onShow 重新激活时使用新代次。
import type { PageContext } from './auth-flow';

export interface ActivePage {
  active?: boolean;
  lifecycleGeneration?: number;
  hidden?: boolean;
}

export function activate(page: ActivePage): void {
  if (page.active) return;
  page.lifecycleGeneration = (page.lifecycleGeneration ?? 0) + 1;
  page.active = true;
}

export function deactivate(page: ActivePage): void {
  if (!page.active) return;
  page.active = false;
  page.lifecycleGeneration = (page.lifecycleGeneration ?? 0) + 1;
}

export function contextOf(page: ActivePage): PageContext {
  const generation = page.lifecycleGeneration ?? 0;
  return {
    active: () => page.active === true && page.lifecycleGeneration === generation,
    owner: page,
    generation,
  };
}

/** onHide：使旧上下文失效，并记住页面曾隐藏。 */
export function suspend(page: ActivePage): void {
  page.hidden = true;
  deactivate(page);
}

/** onShow：重新激活并返回是否从隐藏状态恢复（用于清理 busy 提示）。 */
export function resume(page: ActivePage): boolean {
  const wasHidden = page.hidden === true;
  page.hidden = false;
  activate(page);
  return wasHidden;
}
