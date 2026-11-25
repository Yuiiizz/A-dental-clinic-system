# 签到窗口与短效门店码

负责人使用 `GET/PUT /staff/branches/{id}/booking-config` 读取和修改门店配置。未配置时读取返回 `configured=false`、`version=1`、关闭的自助签到及默认 15 分钟步长；首次 PUT 必须携带 `expected_version=1`，启用自助签到必须同时填写非负的提前及迟到分钟。已配置后用 `branch_booking_config.version` 做乐观并发校验，同值写入不增版本；写入、幂等记录和审计同事务。

前台使用 `POST /staff/branches/{id}/checkin-code` 生成门店码。服务端核验本店角色权限、已核实任职、门店已核验营业和自助签到开关；码不含患者信息，由 HMAC-SHA256 绑定门店、签发秒数和随机 nonce，签发后 60 秒到期。相同 `Idempotency-Key` 返回原码，过期后需使用新键。生产环境经 `CHECKIN_SIGNING_KEY` 注入至少 32 随机字节的 Base64；未配置时拒绝发码。本地 `local` profile 的公开测试密钥仅用于演示，生产 profile 不继承它。

患者端现可通过 `POST /me/appointments/{id}/checkin` 扫描门店短效码签到。服务端重新核验患者本人预约、门店、配置、当天签到窗口和医生停诊；在同一事务内更新预约并建立候诊票，使用与前台签到相同的 `queue_counter` 和 `queue_ticket`。两端按门店、预约、计数器顺序加锁，重复返回原票。相同幂等键重放仍核验当前患者与预约归属，但不要求短效码继续有效。患者预约概览返回可空的 `ticket_id` 和 `queue_no`，刷新后可查看候诊号。管理端门店页可配置签到窗口与自助开关；预约工作台可按门店生成并显示短效二维码，过期后手动刷新。二维码在浏览器本地生成，不向第三方图像服务发送码值。
