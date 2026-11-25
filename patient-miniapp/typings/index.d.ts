/// <reference types="miniprogram-api-typings" />

/** 小程序全局 App 实例的扩展字段（微信官方 TS 模板约定）。 */
interface IAppOption {
  globalData: Record<string, unknown>;
}
