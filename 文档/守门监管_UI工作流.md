# 守门监管 UI 工作流

> 目标：让后续 Codex / Claude / 其他窗口修改 UI 时，不再凭感觉直接改生产页面，而是先按标准做样板、截图、评分，再迁移。

## 1. 固定顺序

每次 UI 工作必须按这个顺序：

1. 读取 `D:\AI_Test\守门应用锁项目\文档\守门监管_DESIGN.md`。
2. 查看 `D:\AI_Test\守门应用锁项目\临时\视觉参考\目标视觉稿\` 和 `当前线上问题截图\`。
3. 在 `D:\AI_Test\守门应用锁项目\临时\UI高保真原型\` 里做静态 HTML 样板。
4. 用 390x844 截图。
5. 按 DESIGN 的 5 项评分表打分。
6. 低于 8 分先改静态原型。
7. 通过后再迁移到 `D:\AI_Test\守门应用锁项目\源码\AppLockApprovalWeb\src\index.ts`。
8. 生产迁移后重新截图，再 dry-run，再部署。

禁止跳过静态原型直接大改 Worker 页面。

## 2. 工具和 Skills 使用规则

### `frontend-skill`

用途：生产 UI 质量约束。

什么时候用：

- 改真实 PWA 页面。
- 审核页面是否像 App、是否过度卡片化、是否信息层级混乱。
- 做最终生产迁移前的 UI 自检。

重点吸收：

- Start with composition, not components。
- App UI 要克制、清楚、少颜色、少装饰。
- 卡片只在确实承载交互时使用。
- 图片/插画必须服务叙事，不做无意义装饰。

### `huashu-design`

用途：静态高保真原型、设计变体、专家评审。

什么时候用：

- 做 `D:\AI_Test\守门应用锁项目\临时\UI高保真原型\`。
- 需要并排探索 2-3 个视觉方向。
- 需要对页面打分、指出哪里不高级、哪里像 AI UI。

本项目使用方式：

- 不把它当生产框架。
- 只把 HTML 当设计媒介。
- 先做首页、设备页、我的页静态稿。
- 通过截图验收后再迁移生产代码。

### `imagegen`

用途：生成高质量位图资产。

什么时候用：

- 家庭守护插画不够精致。
- 手机设备图不够真实。
- 账号头像或提示卡插画粗糙。

限制：

- 不为普通图标使用 imagegen。
- 不生成带 UI 框架和文字的整屏图，避免后续无法编辑。
- 生成后必须保存到项目资源或临时目录，并记录来源。

### Playwright

用途：截图验收和点击检查。

硬要求：

- 每次 UI 交付必须保存 390x844 截图。
- 截图目录独立命名，不覆盖旧图。
- 至少截图：首页、设备页、我的页；涉及其他页面时补对应截图。
- 生产迁移后必须重新截图线上页面。

推荐目录：

- 静态原型截图：`D:\AI_Test\守门应用锁项目\临时\UI高保真原型\screenshots\`
- 生产页面截图：`D:\AI_Test\守门应用锁项目\源码\AppLockApprovalWeb\output\playwright\`

## 3. 参考 GitHub 思路

这些项目只作为方法参考，不直接复制：

- Anthropic `frontend-design`：强调避免 generic AI UI，建立视觉意图和验收。
- `LibreUIUX-Claude-Code`：参考设计代理、设计评审和反 AI slop 清单。
- `awesome-claude-design`：参考 `DESIGN.md` 方式，把审美规则项目化。
- `interface-design`：参考颜色、间距、深度、组件状态的工程约束。

抽取的方法：

- 把“高级”写成可执行规则。
- 把反例写清楚。
- 每页截图打分。
- 先做静态样板，再迁移生产。

## 4. 每次 UI 任务的工作票模板

在 `TODO.md` 里追加任务时，必须包含：

- 本轮边界：改哪些页面，不改哪些接口。
- 参考来源：目标图、当前截图、DESIGN.md。
- 原型阶段：要做哪些静态页面。
- 评分标准：每页目标分数。
- 生产迁移：迁移哪些页面，保留哪些接口。
- 验证：本地截图、线上截图、真机检查点。

## 5. 静态原型要求

静态原型可以使用假数据，但必须符合真实业务：

- 首页：待审批、正在放行。
- 设备页：一个红米手机、保护控制、高风险入口、桌面图标、最近远程命令。
- 我的页：监管人账号、审批记录、修改密码、添加桌面引导。

静态原型不能出现：

- DeepSeek。
- 守门模式。
- 当前设备首页卡。
- 命令状态 Tab。
- 虚假一键添加桌面。

## 6. 生产迁移要求

迁移到 Worker 页面时必须保留：

- `/api/login`
- `/api/activate`
- `/api/password`
- `/api/guardian/requests/:id/approve`
- `/api/guardian/requests/:id/reject`
- `/api/guardian/devices/:id/commands`
- `approvedMode`
- `approvedMinutes`
- `guardianNote`
- `commandType`
- `targetPackage`
- `payloadText`
- PWA manifest、service worker、no-store 缓存策略。

迁移后不得恢复：

- 首页当前设备卡。
- 设备页多个完整控制区。
- DeepSeek 强控。
- 守门模式。
- 我的页命令状态。

## 7. 验收出口

一轮 UI 工作只有满足这些条件才算完成：

- 静态原型截图已保存。
- 验收记录已写分数。
- 生产迁移前没有低于 8 分的页面。
- 生产页面 dry-run 通过。
- 线上截图已保存。
- `CODEX.md` 记录本轮结果和下一步。

