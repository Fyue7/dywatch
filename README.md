# dywatch

一个只在手机上跑的抖音主播监控看板：**精确到个位的粉丝数** + **开播即时提醒**。
不登录抖音账号、不需要服务器，数据全部存在手机本地。

> 非官方工具，与抖音/字节跳动无关；仅供个人学习与自用，抓到的是公开页面上的公开数据。
> 抖音随时可能改前端或收紧风控，届时本工具可能失效。使用后果自负。

## 它做三件事，彼此独立

| 动作 | 取谁 | 取到之后 | 频率 |
|---|---|---|---|
| 点右上 **刷新** | 列表里所有人 | 写入采样 → 卡片数字与涨跌更新 → 界面刷新 | 只有你点 |
| **监控**开关（每主播） | 只取打开监控的号 | **不写任何数字**，只判断是否开播 → 弹提醒 | 60 秒/轮，开了加速 15 秒/轮 |
| **0 点闹钟** | 列表里所有人 | 只写当天那一条快照（画柱状图用） | 每天 0 点 |

粉丝数**不会自动轮询**——你想看最新数字就点刷新。监控开关只管"盯开播 + 提醒"，跟数字没有任何关系。

开播提醒三层：静音横幅（短震无铃声）→ 盖在其他应用之上的大弹窗 → 锁屏全屏亮屏页。
弹窗和卡片上的「去看直播」用深链直达直播间，拉不起来再逐级兜底。

## 首次使用

1. 打开 App，右上「**添加**」。
2. 粘贴该主播的**抖音分享链接**（`v.douyin.com/...`）或 `sec_uid`。
   - 抖音号搜索接口需要登录态 cookie，本工具不登录，所以**按抖音号搜不到**，只能走分享链接。
3. 添加后点「刷新」取一次数。最多 4 个主播。
4. 想让某个主播开播时提醒你，就把那张卡片的「监控」打开。

左上角的标题点一下可以改名字（存本地，留空恢复默认 `dywatch`）。

## 主界面

- **卡片**：粉丝数、今日涨跌（对比当天 0 点快照）、是否在播、最近一次取数时间
- **监控 / 加速**：监控 = 盯着这个号开播并弹提醒；加速 = 把检测频率从 60 秒提到 15 秒（开播后自动复位）
- **去看直播**：未开播是灰的；开播点亮，点了直达直播间
- **解验证**：被风控要滑块时，点这里人工过一次，全 App 放行
- **壁纸**：自定义背景，卡片自动转毛玻璃、文字反白
- **体检**：把"提醒能不能响"拆成一条条自检（通知权限、渠道、悬浮窗、电池白名单、闹钟、服务、签名页面、检测链路、深链、快照、更新），红黄绿三态 + 一键跳系统设置
- **测试提醒**：短按走演示链路，长按走真实 fireAlert 链路，用来验证提醒栈

## 数据与隐私

- 全部数据在本地：`filesDir/dywatch.json`（采样）、`dywatch-daily.json`（每日快照）。
- 不登录、不上传、不收集任何信息；没有自己的服务器。
- 取数方式：App 内一个 1dp 的隐藏 WebView 打开 douyin.com，在抖音自己的页面里调它自己的签名器
  `frontierSign` 签一个请求，然后同源 fetch。**不自己实现签名算法**，抖音改了签名器，App 不用跟着改。
- 首次启动没有任何预置主播，完全从零开始。

## 构建

```bash
# 环境：JDK 17+、Android SDK（compileSdk 34）；local.properties 里写 sdk.dir
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug            # 期望 0 error
```

一份代码两种打包（逻辑完全相同，只差应用名/包名/预置名单）：

```bash
./gradlew assembleDebug                                  # 公开版：包名 com.dsh.dywatch.oss，应用名 dywatch，首启空列表
./gradlew assembleDebug -Ppersonal=true -PpersonalName=你的名字 \
    -PverName=1.4.0                                      # 私人版：包名 com.dsh.dywatch，首启读本地预置名单
```

私人版的主播名单放在 `app/src/personal/assets/seed_accounts.json`（该目录不进仓库）：

```json
[{"secUid":"MS4wLjABAAAA…","uniqueId":"xxx","nickname":"某个主播"}]
```

依赖已锁定：`app/gradle.lockfile` + `gradle/verification-metadata.xml`，Gradle wrapper 带 sha256 校验。

### 发布用的正式签名

`assembleRelease` 会用仓库根目录的 `keystore.properties`（**不入库**）签名；没有这个文件时 release 不签名，
`assembleDebug` 仍用本机 debug keystore，所以别人 clone 下来照样能构建、能跑。

```properties
storeFile=/absolute/path/to/dywatch-release.jks
storePassword=***
keyAlias=dywatch
keyPassword=***
```

⚠️ keystore 一旦丢失或更换，已安装的用户（包括你自己）就无法覆盖升级，只能卸载重装。
升级依赖后需要 `./gradlew :app:dependencies --write-locks` 并重写校验元数据。

## 平台支持

**只支持 Android**（minSdk 24 = Android 7.0 及以上），没有 iOS、也没有桌面版。

做不到 iOS 的原因很实在：这套东西靠「前台服务常驻 + 隐藏 WebView 后台跑 + 悬浮窗盖在别的应用上 +
锁屏全屏意图 + 精确闹钟」，iOS 一条都不允许（后台不能让 WebView 这样常驻，也不允许侧载安装）。
要上 iOS 得换成「服务器端轮询 + 推送」的架构，那是另一个项目了。

## 已知限制

- 添加主播只能用分享链接 / sec_uid（搜索接口要登录态）
- 最多 4 个主播（写死，改 `Store.canAdd()` 的数量上限即可）
- 强依赖抖音网页的签名器：抖音前端大改 / 收紧游客态时可能整体失效，届时会表现为体检页红叉 + 连败提醒
- 国产 ROM 需要手动给四个权限（通知、悬浮窗、电池白名单、精确闹钟），体检页会逐条指出缺哪个
- 0 点快照与监控检测都依赖系统闹钟；没给「闹钟和提醒」权限会退化成 0 点后 10 分钟内的窗口
- 目前只有 debug 签名；如果你自己 fork 出包，注意签名不同会导致无法覆盖安装

## 目录

```
app/src/main/java/com/dsh/dywatch/
  Douyin.kt              取数/签名/静音/拦截（桥必须在 loadUrl 之前注册）
  MainActivity.kt        主界面（零 WebView），刷新=发指令给服务
  LiveWatchService.kt    前台服务：三种取数用途（刷新/检测/快照）、看门狗、喊人、数据防线
  LiveNotify.kt          通知统一入口（开播提醒 + 异常喊人）
  AlertOverlay.kt        悬浮大弹窗
  LiveAlertActivity.kt   锁屏全屏提醒页
  CaptchaActivity.kt     「解验证」
  HealthActivity.kt      体检页
  DetailActivity.kt      二级页：当前数 + 每日柱状图 + 快照列表
  BarChartView.kt        自绘柱状图（无第三方库）
  Update.kt              查 GitHub Releases / 下载 / sha256 校验 / 交系统安装器
  Store.kt               本地存储（原子写 + 坏文件 .bak 隔离 + 首启可预置名单）
  WatchPrefs.kt          开关与状态
  AccountAdapter.kt      卡片列表
  A11y.kt                无障碍：按钮角色、控件命名、48dp 触摸目标
  Scheduler.kt / AlarmReceiver.kt / BootReceiver.kt   0 点闹钟、看门狗、开机自启
  DeepLink.kt / EdgeUi.kt / UiWallpaper.kt           深链、沉浸式、壁纸
app/src/main/res/values/colors.xml   视觉 token 的唯一来源
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
