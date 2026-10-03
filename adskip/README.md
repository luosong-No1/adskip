# 广告跳过（AdSkip）

基于 Android **无障碍服务**自动点击「跳过 / 关闭广告」的小工具。

设计目标很明确：**能自己看到界面，只在识别到明确的跳过/关闭按钮时才动手，看不懂就什么都不做。**

- 不申请任何运行时权限
- 不申请 `INTERNET`，**无法联网**，不存在数据外传的可能
- 不申请 `QUERY_ALL_PACKAGES`，不枚举你装了哪些应用
- 唯一的"权限"是你在系统设置里手动打开的无障碍服务

---

## 一、它是怎么工作的

```
窗口切换 / 内容变化
      │
      ├─ 窗口切换 → 进入 5 秒「狩猎期」，其间高频扫描
      │              并额外安排 400ms / 1s / 2s 三次延时补扫
      │              （开屏广告的「跳过 3」通常比窗口本身晚几百毫秒才渲染出来）
      │
      └─ 内容变化 → 只有在狩猎期内、或事件文本里出现
                     「广告 / 跳过 / 关闭」字样时才真正扫描（省电）
      │
      ▼
 遍历无障碍树（最多 300 个节点，深度 28）
      │  同时判断：当前窗口里有没有广告特征？
      ▼
 逐节点套用分级规则 + 安全护栏
      │
      ▼
 选出「分级优先级最高、面积最小」的那一个
      │
      ├─ 先试 ACTION_CLICK（真·点击控件）
      └─ 失败退回 dispatchGesture 按坐标点一下
         （很多开屏广告是自绘控件，无障碍上不可点击）
```

### 四级识别规则（可在界面里逐个开关）

| 分级 | 命中条件 | 默认 |
|---|---|---|
| **跳过** | 文本含「跳过」「跳过广告」「3s 后跳过」「Skip」「Skip ad」；或控件 id 含 `skip` | 开 |
| **关闭广告** | 「关闭广告」「不感兴趣」「屏蔽此广告」；或 id 形如 `ad_close` / `iv_ad_close`；或窗口内出现广告特征 + 通用关闭控件 | 开 |
| **叉号** | 单个 `× ✕ ✖ ╳ ✗ x`，且同时满足「图标类控件 + 可点击 + 尺寸很小」 | 开 |
| **宽松关闭** | 任意「关闭 / Close」文本 | **关** |

第四级默认关闭，因为它会把普通对话框的关闭按钮也点掉。

---

## 二、三步拿到 APK

### 方式 A：GitHub Actions（推荐，本机零环境）

不需要装 JDK、Android SDK、Android Studio，编译在 GitHub 的机器上完成。

1. 在 GitHub 新建一个仓库
2. 把 **`adskip` 目录里的全部内容**放到仓库根目录（`.github/`、`app/`、`*.gradle.kts` 等都在根目录），提交并推送：

   ```bash
   cd adskip
   git init
   git add .
   git commit -m "ad skip app"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

   > 工作流也能自动识别「仓库根目录下有个 `adskip/` 子目录」的布局，所以直接推整个仓库也行。

3. 打开仓库的 **Actions** 页面 → 等 `Build APK` 跑完（首次约 2～4 分钟）
   → 在该次运行的 **Artifacts** 区域下载 `AdSkip-debug-apk` → 解压得到 `.apk`

也可以在 Actions 页面点 **Run workflow** 手动触发。

CI 会同时跑 `gradle testDebugUnitTest`（规则层单元测试），测试不过就不会产出 APK。

### 方式 B：Android Studio

1. `File → Open`，选中 `adskip` 目录
2. 等 Gradle Sync 完成（需要联网下载 Gradle 8.7 与依赖）
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
4. 产物在 `app/build/outputs/apk/debug/app-debug.apk`

> **注意**：本工程**没有附带 `gradle/wrapper/gradle-wrapper.jar`**（二进制文件不便内联）。
> 如果 Android Studio 提示 `Could not find or load main class ...GradleWrapperMain`，
> 二选一：
> - 在项目根目录执行一次 `gradle wrapper --gradle-version 8.7` 生成 wrapper；
> - 或 `Settings → Build, Execution, Deployment → Build Tools → Gradle`，
>   把 *Use Gradle from* 改成你本地已装的 Gradle 8.7。

### 方式 C：命令行

```bash
# 需要 JDK 17 + Android SDK（platform 34 / build-tools 34.0.0）
cd adskip
gradle testDebugUnitTest assembleDebug
```

---

## 三、安装与开启

1. 把 APK 传到手机安装（首次需允许"安装未知应用"）
2. 打开 App，点 **去开启无障碍服务**
3. 在系统设置里找到 **已下载的服务 / 已安装的服务 → 广告跳过**，打开开关
   （不同 ROM 路径略有差异，一般在 *设置 → 无障碍 → 已安装的服务*）
4. 回到 App，状态应显示 **已连接，正在工作**

之后正常使用手机即可，不需要让本应用停留在后台。

**关闭方式**：App 里的「总开关」只停用识别；彻底关闭请去系统无障碍设置里关掉。

---

## 四、界面说明

| 区域 | 说明 |
|---|---|
| 服务状态 | 区分「未开启」「系统已启用但未连接」「已连接」三种状态，解决"我明明开了怎么没反应"的困惑 |
| 识别规则 | 四个分级开关 + 总开关 |
| 排除的应用 | 每行一个包名，支持前缀匹配（填 `com.tencent` 会连子包一起排除） |
| 安全护栏 | 当前生效的护栏清单 |
| 最近跳过记录 | 最近 40 条：时间 / 命中分级 / 包名 / 命中的文本。**调参和排查误点主要靠它** |

---

## 五、安全护栏

自动化点击最大的风险是**误点**，所以规则层是「宁可不点，不要点错」：

1. **危险词一票否决**：文本或描述里出现
   `取消 / 确定 / 确认 / 支付 / 付款 / 购买 / 开通 / 续费 / 订阅 / 升级 / 下载 / 安装 / 卸载 /
   删除 / 注销 / 退出 / 同意 / 允许 / 拒绝 / 发送 / 转账 / 还款 / 提现 / 充值 / 绑定 / 授权 /
   登录 / 注册 / 返回 / 放弃 / 重置 / 提交 / 免密 / 指纹 / 人脸`
   以及 `cancel / confirm / ok / pay / buy / purchase / subscribe / delete / remove / agree /
   allow / deny / send / transfer / logout / submit / continue / next …`
   → 直接放弃该控件。英文按单词边界匹配，所以 `Look at this` 不会因为含 `ok` 被误判。

2. **面积护栏**（三条）：
   - 面积超过屏幕 1/4 的不点 —— 那多半是整块广告位，点了会跳到广告落地页
   - 有子节点、且面积超过屏幕 1/12 的大容器不点 —— 真正的按钮都是叶子节点
   - 宽或高小于 24px 的不点 —— 避免点到装饰性像素

3. **点击节流**：
   - 两次点击之间至少间隔 0.7 秒
   - 每分钟最多点 25 次
   - 同一个目标 1.5 秒内只点一次

4. **内置保护名单**（`SkipPrefs.BUILT_IN_PROTECTED`，代码里可改）：
   - 系统界面、设置、权限控制器、安装器、锁屏
   - **桌面启动器**（AOSP / Pixel / MIUI / EMUI / 荣耀 / ColorOS / OxygenOS / vivo / 三星 / Nova 等）
     —— 从应用退回桌面会触发窗口切换并进入扫描期，而桌面上不存在广告，误点只会帮倒忙
   - **输入法**（Gboard、搜狗、百度、讯飞、QQ 输入法）—— 弹键盘同样会触发窗口变化
   - **电话、联系人、短信**
   - 主流银行与支付类应用（支付宝、云闪付、工行、建行、中行、招行、农行、交行、邮储、
     平安、兴业、光大、华夏、宁波银行、北京银行、PayPal 等）
   —— 这些应用内**永远不介入**。

5. **分级默认值**：最激进的「宽松关闭」默认关闭，需要你主动打开。

---

## 六、已知限制（做不到什么）

请先看这一节，能省下不少"为什么没用"的困惑。

1. **无障碍树里读不到的界面，无能为力。**
   广告画在 `SurfaceView` / 纯图片画布 / 自绘 OpenGL 上时，树里根本没有对应节点。
   不少游戏和部分应用的开屏广告属于这一类 —— 这是本方案最大的天花板。

2. **这不是广告拦截器。**
   它靠"点按钮"生效，拦不住横幅广告、信息流广告、视频贴片（除非它们自带可点的跳过/关闭按钮）。
   从网络层拦广告需要 VPN/代理方案，不在本工程范围内。

3. **无法自动打开自己的无障碍服务。**
   Android 不允许应用自己开关无障碍服务，必须用户手动去系统设置里操作，这是系统层面的安全设计。

4. **关键词匹配不可能 100%。**
   跳过按钮的样式千奇百怪（有的只有一张图，有的写"X"以外的生僻符号）。
   所以提供了分级开关、排除列表和点击记录来兜底。

5. **部分应用会主动对抗无障碍服务。**
   有些应用检测到无障碍开启后会改变行为或拒绝运行（金融类常见，已在保护名单内）。

6. **Android 14+ 的敏感界面保护。**
   系统允许应用把界面标记为 `accessibilityDataSensitive`，只有声明为"无障碍工具"的服务才能读取。
   本工程声明了 `isAccessibilityTool="true"`（见第九节），但个别系统/应用的额外安全策略仍可能屏蔽内容。

7. **未提供前台服务常驻通知。**
   无障碍服务本身不会被系统轻易回收，所以没有额外做通知保活；极端省电策略下建议把本应用加入电池白名单。

---

## 七、调参手册

| 现象 | 怎么办 |
|---|---|
| 有开屏广告但没跳过 | 先看「最近跳过记录」。没记录 = 没识别到，多半是第 6.1 条（自绘广告）或关键词没覆盖 |
| 跳过了但点得太晚 | 广告 SDK 渲染慢，可以在 `AdSkipAccessibilityService` 里把 `scheduleRescan` 的延时数组调密一些 |
| 应用内弹窗广告没关掉 | 打开「宽松关闭」试一次，用记录确认点了什么；确认可靠后保留 |
| 误点了不该点的 | 把那个应用加进「排除的应用」；如果它在内置名单外且是金融类，考虑直接加进代码里的 `BUILT_IN_PROTECTED` |
| 某个应用想跳过开屏，但它太激进 | 用「排除的应用」把它排掉。**不要**轻易去改危险词列表 |
| 想支持某个特殊写法（如"跳过X秒"） | 改 `SkipRules.kt` 里的关键词正则，然后跑 `gradle testDebugUnitTest` 看有没有踩到已有用例 |

改完规则记得跑一遍单测 —— `SkipRulesTest.kt` 里的 30+ 条用例专门盯着误报。

---

## 八、项目结构

```
adskip/
├── .github/workflows/build.yml         CI：跑单测 + 出 debug APK
├── build.gradle.kts                    顶层构建脚本（插件版本）
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── tools/make_icons.py                 生成启动图标 PNG（Pillow）
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     只声明一个 Activity + 一个无障碍服务
        │   ├── java/com/adskip/
        │   │   ├── SkipRules.kt               ← 规则层：纯逻辑，零 Android 依赖
        │   │   ├── SkipPrefs.kt               ← 配置存储 + 内置保护名单
        │   │   ├── AdSkipAccessibilityService.kt  ← 核心：扫描与点击
        │   │   └── MainActivity.kt            ← 界面
        │   └── res/
        │       ├── xml/accessibility_service_config.xml
        │       ├── layout/activity_main.xml
        │       ├── values/ (中文) values-en/ (英文)
        │       └── mipmap-*/  + mipmap-anydpi-v26/
        └── test/java/com/adskip/
            └── SkipRulesTest.kt        ← 规则层单元测试（普通 JVM 即可运行）
```

技术栈：Kotlin 1.9.24 · AGP 8.5.2 · Gradle 8.7 · JDK 17 · compileSdk 34 · minSdk 24 · 传统 XML View。

规则层刻意做成零 Android 依赖，就是为了能在普通 JVM 上跑单测 —— 改规则不怕拍脑袋。

---

## 九、关于 `isAccessibilityTool="true"` 的取舍

无障碍服务配置里声明了 `android:isAccessibilityTool="true"`（API 31+ 的属性）。

- **好处**：Android 14 起，非"无障碍工具"的服务读不到被标记为 `accessibilityDataSensitive`
  的界面内容，会漏掉一部分弹窗；声明为 true 才能完整读取。
- **代价**：这个声明本意是给辅助残障用户的服务用的。严格来说本应用并不属于此类，
  加上 Google Play 对无障碍权限的审核极其严格（要求说明用途、隐私政策、通常还会被拒），
  **本工程定位是个人自用侧载，不适合上架**。
- 如果你更在意支付类界面的额外保护，把这一行改成 `false` 重新编译即可 —— 代价是可能漏掉一些弹窗。

---

## 十、隐私

- 无网络权限，代码里没有任何网络调用
- 不收集、不存储、不上传任何界面内容
- 「最近跳过记录」只写在本地 `SharedPreferences`（最多 40 条），且已设置 `allowBackup="false"`，
  不会随系统备份离开设备
- 界面内容只在内存里存在一次扫描的时长，用完即弃

---

## 十一、免责声明

- 本项目仅供**个人学习与自用**。
- 自动跳过广告可能违反某些应用的用户协议；同类工具在国内也曾引发过法律争议（有作者收到过律师函）。
  **请不要分发、售卖，或用它绕过付费内容。**
- 自动化点击存在误点风险。虽然做了多层护栏，仍请自行评估后再长期开启，
  并建议保留内置的金融类应用保护名单。
- 本工程**未在真实 JDK / Android SDK 上编译验证过**（开发环境无 JDK、无 Android SDK、无外网）。
  已完成的验证如下，供你判断风险：
  1. 10 个 XML 文件格式合法，Tag 闭合正确；
  2. 全部 `@string / @color / @style / @drawable / @mipmap / @xml` 引用、Kotlin 里的 `R.*`
     引用、以及 viewBinding 用到的控件 id 都能解析到声明处；中英文字符串资源一一对应；
  3. 5 个 Kotlin 文件括号配对、字符串与块注释闭合正确；
  4. 规则层 48 项行为用例通过（用等价移植的 Python 版本真实执行，覆盖英文正则、
     面积护栏、危险词优先级、`disclosure`/`download` 这类误报防护等边界）；
  5. 同口径的 Kotlin 单测（`app/src/test/java/com/adskip/SkipRulesTest.kt`）已随工程提供，
     **首次 CI 会真正编译并执行它** —— 这是唯一能确认真实编译结果的环节。
  首次 CI 若因版本号或环境差异报错，通常只需调整
  `gradle/wrapper/gradle-wrapper.properties` 与 `app/build.gradle.kts` 里的
  Gradle / AGP 版本，规则与业务代码本身不受影响。
