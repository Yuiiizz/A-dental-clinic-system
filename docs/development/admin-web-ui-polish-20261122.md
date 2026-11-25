# 管理端排版与 Inventory 走查（2026-11-22）

本轮只改 `admin-web` 界面和必要前端测试；未改后端接口、业务状态、权限、数据库、OpenAPI、Word、云端配置或已部署版本；未删除数据，未部署。

## 走查范围

- 宽度：1440 / 1366 / 1024 / 760。
- 页面：经营工作台（首页）、预约列表、签到候诊、就诊/接诊、日历排班、门店、店内沟通、登录。
- 重点：普通耗材库存 Inventory（批次余额、验收入库、验收记录、目录/库位），以及供应商页面与库存的导航关系。

## 主要问题与修正

### 1. 760 宽度导航把工作区推到首屏之外

改前：侧栏在 760 宽度变成普通文档流，所有导航分组占满约一屏，经营工作台要滚动很久才出现。

改后：

- 760 及以下侧栏改为 **按需抽屉**，顶部显示“☰ 导航”；工作台内容首屏可见。
- 抽屉带遮罩；选择菜单后自动关闭；点击遮罩可关闭。
- 1024/1366/1440 保留固定侧栏，分组标题增加统一线性图标。
- `theme.css` 的移动端高权重规则同步改成 fixed 抽屉，避免被全局 `#app .sidebar` 覆盖成 static。

### 2. Inventory 表格在 760 下被裁切

改前：批次余额表在 760 宽度下最右侧“查看验收单”和余额状态被裁切；状态、有效期换行拥挤。

改后：

- 900 以下批次余额改为 **横向可读卡片**：耗材、库位、批号、生产企业、有效期、状态、余额各占清晰一行；表格在移动端隐藏。
- 1024/1440 保留表格，长名称、生产企业可换行；余额为 0 显示“暂无可用”并高亮。
- 移动端表格通用规则改为 `min-width: 620px`、允许单元格换行和按钮换行，避免页面级横向溢出。

### 3. Inventory 数量/单位、低库存和出库边界

- 验收数量旁显示当前耗材的 **基本单位**（选择耗材后为“包/件”等；未选时提示“单位待选”）。
- 余额明确说明：当前接口不返回补货阈值，**不自动判定低库存**；数量为 0 只显示事实性“暂无可用”，不编造补货规则。
- 明确提示：当前接口只提供验收入库和批次余额，**尚未接入出库/领用**，页面不伪造出库单。
- 验收入库保留供应商、单据、批号、有效期、数量、单位取得成本、持有人、追溯依据等字段和原有校验。

### 4. Inventory 权限与操作反馈

- 无验收入库权限时显示明确的“只读权限”提示，不渲染可提交表单，不只依赖按钮禁用。
- 错误/成功提示保留 `role=alert/status`；超时/结果不明仍提示先核对验收记录，并保留原表单与幂等键。
- 供应商页增加“库存管理 →”入口，Inventory 侧栏保留“维护供应商 →”，采购/库存流程互相可达。

### 5. 统一视觉

- 统一使用现有薄荷绿主色、浅底卡片、圆角、状态色和错误/成功提示；未引入新的设计系统。
- 导航分组新增统一线性 SVG 图标；不额外引入图标依赖。
- 修正排班页“实时接口”标签为“真实接口”，与其它页面一致。
- 760 下供应商头部保留“库存管理 / 真实接口”两级入口，不挤压标题。

## 截图

### 工作台 760：改前 → 改后

改前（导航占满首屏）：

![工作台 760 改前](../screenshots/admin-ui/workspace-760-before.png)

改后（内容首屏可见）：

![工作台 760 改后](../screenshots/admin-ui/workspace-760-after.png)

抽屉导航：

![工作台 760 抽屉](../screenshots/admin-ui/nav-drawer-760-after.png)

### Inventory 760：入库改前 → 改后

![Inventory 入库改前](../screenshots/admin-ui/inventory-receiving-before-760.png)

![Inventory 入库改后](../screenshots/admin-ui/inventory-receiving-after-760.png)

### Inventory 760：余额改前 → 改后

![Inventory 余额改前](../screenshots/admin-ui/inventory-balances-before-760.png)

![Inventory 余额改后](../screenshots/admin-ui/inventory-balances-after-760.png)

### 其它宽度

- 1440 入库改前/改后：`inventory-receiving-before-1440.png` / `inventory-receiving-after-1440.png`
- 1024 入库改前/改后：`inventory-receiving-before-1024.png` / `inventory-receiving-after-1024.png`
- 1440/1024/760 余额改后：`inventory-balances-after-1440.png`、`...-1024.png`、`...-760.png`
- 1440/1366/1024 工作台改前/改后：`workspace-{宽度}-before.png` / `workspace-{宽度}-after.png`

### 关键页面 760 实际渲染

- 预约列表：`appointments-760-after.png`
- 签到候诊：`checkin-760-after.png`
- 日历排班：`calendar-760-after.png`
- 登录：`login-760-after.png`
- 门店：`branches-760-after.png`
- 排班与基础配置：`scheduling-760-after.png`
- 店内沟通：`messages-760-after.png`
- 供应商：`suppliers-760-before.png` → `suppliers-760-after.png`

所有截图均在本地 Vite 开发服务器实际渲染；Inventory/门店/排班/消息使用浏览器 route mock 注入只读样例，未调用真实后端或云端。演示页面仍显示“演示数据 · 本地模拟”标识，真实页面保留“真实接口”标识。

## 修改文件

- `admin-web/src/views/WorkspaceView.vue`
  - 移动抽屉状态、顶部“导航”按钮、遮罩、菜单关闭；导航分组 SVG 图标。
- `admin-web/src/theme.css`
  - 760 侧栏固定抽屉、topbar flex、单列导航；移动表格/按钮换行。
- `admin-web/src/views/InventoryView.vue`
  - 移动余额卡片、零库存事实提示、低库存阈值/出库未接入说明、单位徽标、只读权限提示、状态徽标、截断修复。
- `admin-web/src/views/SuppliersView.vue`
  - 库存管理互链与 760 头部布局。
- `admin-web/src/views/SchedulingConfigView.vue`
  - “实时接口”统一为“真实接口”。
- `admin-web/src/__tests__/workspace.test.ts`
  - 新增窄屏抽屉打开/关闭测试。
- `admin-web/src/__tests__/InventoryView.test.ts`
  - 新增零库存“暂无可用”、低库存阈值边界、只读权限测试。

## 验证

```powershell
cd admin-web
$env:Path = 'D:\DevTools\node-v24.22.1-win-x64;' + $env:Path
npm run typecheck
npm run test:unit
npm run build
```

结果：

- `typecheck`：通过。
- `test:unit`：**29 个测试文件、272 项通过**。
- `build`：通过；Vite 提示主 chunk 约 1.18 MB（既有 chunk-size 警告，不阻塞构建）。
- 实际浏览器走查：1440/1366/1024/760 下页面没有页面级横向溢出；760 工作台首屏可见，抽屉可打开/关闭；Inventory 760 余额卡片无裁切；供货商/门店/排班/消息/登录均可渲染。

## 仍需真机或云端验证

- 真实员工登录态、Cookie/CSRF、真实服务端权限和真实库存/供应商/预约数据；本轮 route mock 只验证布局和交互边界。
- 760 真机触屏、长姓名/长供应商名、系统字体放大和触屏滚动惯性。
- 出库/领用表单不存在（后端未提供），不能把本轮页面当作已实现出库流程。
- 低库存阈值由后端/业务规则决定；本轮只展示“接口未提供阈值”的边界，未新增业务判断。
- 没有部署、没有修改真实数据、没有上传或发布版本。

## Codex 定向复核建议

1. 760 真机检查抽屉层级、遮罩、滚动锁定和导航选择后的关闭。
2. 1024/760 检查 Inventory 余额卡片、入库表单单位/数量、权限提示和按钮是否被键盘/触屏遮挡。
3. 核对“无低库存阈值 / 无出库接口”的文案是否与后端实际能力一致，不能被误读为已完成补货或出库。
4. 检查 1440/1366 表格长名称、错误提示、供应商头部和门店/排班/消息页面。
5. 复核前不要部署或改云端配置。
