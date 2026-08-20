# AGENTS.md — SpaceFireworks

## 这个项目是什么

`SpaceFireworks`（包名 `tech.illusion.spacefireworks`）是一款 PICO Spatial SDK 烟花特效应用：默认以体积窗口
（`volumetric` `WindowContainer`）播放缩小版烟花景箱，窗口前挂一个 `Toolbar`/`Augment`（重新点火 + 切换沉浸），
点击切换沉浸后打开 `Stage`（Full Space），同一份场景等比放大到真实尺度，烟花在头顶天空真实大小绽放。完整产品需求
见设计契约 `.spatialsdk/design-contract.md`（brainstorming + 两轮效果图迭代后由用户确认，v1，2026-08-10）——
**后续所有 UI/容器改动必须先读这份契约，以它为唯一基准**，不要凭感觉加东西也不要漏掉契约里锁定的项。

配套的效果图源文件在 `.spatialsdk/design-ref/concept-render.html`（HTML 概念渲染图，可直接在浏览器打开）。

## 当前状态：仅完成 onboarding 脚手架 + baseline 验证，烟花/按钮/沉浸系统尚未实现

`pico-cli project create --template volumetric` 生成的骨架 + 少量按契约调整的 manifest 字段，**没有**实现契约里
的任何业务逻辑（Toolbar/Augment 按钮、ParticleComponent 烟花系统、Stage 切换）。当前 `HomeVolume.kt` 还是模板自带
的示例内容（一个 `box.usdz` 模型 + "Hello, Spatial SDK!" 的 `AttachmentPanel` 文字面板），**不是**契约里的场景占位/
按钮——下一步开发要把这些示例内容替换/扩展成契约要求的东西，不是在此基础上"顺便留着"。

已按契约调整的部分（相对 CLI 默认生成值）：
- `AndroidManifest.xml`：`pico.spatial.windowcontainer.id` 改为 `SpaceFireworksVolume`；
  `pico.spatial.windowcontainer.defaultsize` 改为 `0.5x0.42x0.35`，`defaultsize.unit` 改为 `meters`
  （契约里给的是量级参考值，不是硬指标，后续实现时如果视觉上不合适可以调）
- 其它 manifest 字段（`materialbackground`、`worldscaletype` 等）保持 CLI 默认，契约没提就没动

## 架构决策（来自契约，不要为了实现方便更改）

- 默认容器必须是真实的 `volumetric` `WindowContainer`，不能退化成 `planar`
- 窗口前的按钮必须用 `com.pico.spatial.ui.augment.Toolbar`/`Augment`（系统伴生窗口），不能拿 `Row`/`Column`
  手搓一个假悬浮层顶替
- 切换沉浸后 `main` 窗口不销毁、只是不可见；`Stage` 内的按钮是 **Stage 场景内的 2D Compose 覆盖层**，不是
  `Augment`——bundled reference 明确写 "Augment 只在 WindowContainer 内生效"，这是两套不同的挂载机制，视觉要一致
- 烟花：一次绽放 = 一个 `ParticleComponent` 实体，不是一发一实体（性能预算 Shared Space ≈30 / Full Space ≈60
  活跃实体，含所有固定实体）

## 技术点核实结果（更新：已用 SDK sources jar 反编译核实，不再是"未核实"）

- **`ParticleComponent` 不可用，已改用 `MeshInstancesResource` 方案，已实现并验证跑通**：反编译
  `~/.gradle/.../com.pico.spatial.core/core/0.13.3/*-sources.jar` 确认 `ParticleComponent` 没有公开构造函数，只能
  从 Spatial Editor 预制的 `AssetBundle` 里自动带出来，运行时不能手动 new 一个。本项目没有那套资产管线，改用
  `content/Fireworks.kt` 里的 `FireworkDirector`（单例，管理 `MeshInstancesResource` 里的 instance 增删/位置更新）
  + `FireworkSystem`（`System` 子类，每帧转发 `deltaTime`，`System.register(FireworkSystem::class.java)` 注册一次）。
  一次绽放 = 一个 `MeshInstancesResource` 里的 N 个 instance，**不是** N 个 entity，一样满足预算要求。已知限制：
  一个 `MeshInstancesResource` 共享一个 `UnlitMaterial`，所以一个颜色需要一个独立的 instances 实体——5 种样式库
  如果要同时出现不同颜色，做法是按颜色（不是按样式）开固定几个 instances 实体（比如 3-4 个色通道），样式的差异
  用发射角度/速度/reused 的 velocity 分布来体现，不是靠每种样式单开一个实体。
- `MeshResource.createBox/createSphere(...)`、`UnlitMaterial.create(BlendingMode)`、`ModelComponent(mesh, material)`
  + `.meshInstances = ...`、`Transform(Vector3, EulerAngles, Vector3)`、`Color4(r,g,b,a)` 都已经是反编译核实过的
  真实签名（见 `Fireworks.kt`/`HomeVolume.kt` 的实际用法），不是猜的。
- `main`（窗口缩小态）→ `stage`（真实尺度）的具体实现路径：重建 vs 用 `convertTransformTo` 跨容器搬运实体，
  两者都不违反契约，选哪个留给下一个增量（Stage 切换）实现时决定
- 生成的模板原本用 `AttachmentPanel`（`SpatialView.attachments`）做 2D 面板——这是和 `Augment`/`Toolbar`
  （系统级伴生窗口）**不同的第三种机制**，契约选了 `Toolbar`/`Augment`，下一个增量（Toolbar 按钮）要用契约选的
  那个，不要偷懒继续用模板自带的 `AttachmentPanel`

## 已验证：increment 1（场景占位 + 发射锚点 + 单色最小烟花）

`content/HomeVolume.kt` + `content/Fireworks.kt`：一个占位地面色块（`sceneBackdrop`）+ 一个发光锚点标记
（`fireworkLaunchAnchor`）+ 一个承载所有火花 instance 的实体，启动时自动点一次火（暖橙色，24 发火花，无 Toolbar，
下一个增量再接按钮）。`./gradlew assembleDebug` 编译通过；模拟器上装机启动两轮，`pico-cli log` 确认
`focus=<id>(tech.illusion.spacefireworks)`、`FirstFrameRelease(Displayed)`、`func_used_spatial` 记录了
`ModelComponent`/`TransformComponent` 用量，全程无 `AndroidRuntime`/`FATAL` 崩溃日志。

**已验证：increment 2（Toolbar 按钮）**——`Toolbar { Button(...) { Text("重新点火") } Button(...) { Text("切换沉浸") } }`
（`com.pico.spatial.ui.design.windows.Toolbar` + `com.pico.spatial.ui.design.Button`，`Augment` 家族，反编译
`design-0.13.3-sources.jar`/`foundation-0.13.3-sources.jar` 核实过签名）。`重新点火` 接到
`FireworkDirector.ignite()`；`切换沉浸` 暂时只打一条 log（Stage 增量还没做）。

**截图限制更新：这台模拟器重启一次、别的项目不在前台之后，反而拍到了真实内容**——`ui-verify/increment2-toolbar.png`
清楚拍到了深色体积窗口盒体 + 橙色锚点标记 + 两个按钮；`ui-verify/increment2-burst-midflight.png`（点火后 0.9s 截的）
拍到了一小簇金色火花正在往外飞。之前两次拍不到是因为设备被其它项目占着/launcher 面板挡在前面，不是这台环境
永久拍不到——**下次验证先确认没有其它 app 抢占前台**，再决定要不要花时间等窗口进画面。

**已验证：increment 3（用户提供的城堡模型作 sceneBackdrop + 烟花按比例缩放）**——用户提供
`~/Downloads/Fantastic_castle.usdz`（12.8MB，4 组 PBR 材质），复制进 `app/src/main/assets/castle.usdz`，
`build.gradle.kts` 加了 `androidResources { noCompress += listOf("usdz") }`（resources-loading.md packaging
checklist 明确要求，验证过 APK 里确实是 `Stored` 不是 `Deflated`）。`HomeVolume.kt` 的 `loadSceneBackdrop()`：
`Entity.loadSuspend("asset://castle.usdz")` 加载到原点 → `entity.getVisualBounds(relativeTo=null,
recursive=true)`（`Entity.kt` 反编译核实的公开 API，内部包着 internal 的 `BoundingBox.getVisualBounds`，不能直接
调那个）量出真实尺寸（不假设作者定的原始比例）→ 按窗口尺寸算等比缩放系数、`scaleVector` 应用 → 缩放后的高度
（`rawSize.y * fitScale`）和地面 Y（`rawBounds.min.y * fitScale`）传给 `FireworkDirector.bind(...,
backdropHeight = finalHeight)`，`FireworkDirector` 内部按 `backdropHeight / REFERENCE_HEIGHT` 把爆炸高度和火花
速度等比放大/缩小——不是写死系数。加载失败（`ResourceLoadingException`）会兜底退回旧的占位地面色块，不会崩溃。
截图 `ui-verify/castle-1.png`/`castle-2.png`（城堡渲染出来，缩放合理，没顶到窗口边界）、`castle-burst.png`
（点火后 1.9s，金色火花正好在城堡屋顶上方炸开，宽度和城堡尺度看着成比例）都实拍确认过，无崩溃。

**修复：城堡悬空问题**——用户反馈"位置太高了"（`castle-1.png` 那版城堡飘在窗口正中间，底部到窗口底座之间空一大截）。
根因：只 `scaleVector` 缩放了模型，没重新摆位置——entity 空间原点是容器的**几何中心**（见
coordinates-and-units.md），不是地面，模型自己的 pivot 恰好在其自身底部（`rawBounds.min.y ≈ 0`），所以缩放后
底部还是卡在容器纵向中心，不是容器地面。修复：量出缩放后模型底部的 Y，再整体平移到 `WINDOW_FLOOR_Y = -0.21`
（manifest 里 `defaultsize` 高度 0.42m 的一半）。截图 `ui-verify/castle-grounded.png` 确认城堡贴地了。
**以后换背景模型（森林/海边）时如果又"浮空"，先查是不是漏了这一步平移，不是缩放系数算错。**

**修复：X/Z 也没居中**——用户用俯视角截图指出城堡在底座上不居中。同一个根因的另一半：只对齐了 Y（地面），
没检查模型自己的 footprint 中心是不是也在其 pivot 上（不能假设）。修复：`rawBounds.center.x`/`center.z`
（缩放后）算出来偏了多少，一起平移掉，让 footprint 中心落在容器的 X=0,Z=0。`ui-verify/castle-centered.png`
确认。**这两个修复应该合并成一个通用的"footprint 底部+中心对齐"函数**，下次换场景模型时不要重复这两处踩坑——
这是当前实现里还没做的技术债，留给下一次接触这块代码时处理。

## increment 4：烟花引擎重写（用户反馈"不够真，要逼真和炫彩"）

`content/Fireworks.kt` 整个重写。旧版是 24 个同色小球沿直线飞，看着就是"撒纸屑"。新版：

- **6 条颜色通道**（`Palette`：白/金/红/青/紫/绿）。一个 `MeshInstancesResource` 只能带一个材质，所以
  "同屏能有几种颜色"取决于开几个通道实体，**不取决于火花数**。每条通道仍然只是 1 个 draw call。
  材质用 `BlendingMode.ADD`，颜色分量故意 >1（如 `Color4(1.6f, 0.95f, 0.28f, 1f)`），叠加后中心发白、
  边缘保持饱和。
- **线性阻力弹道**（`positionAt`）：`v(t)=(v0-g/k)e^(-kt)+g/k` 的闭式积分。真实烟花是"炸出去→被空气瞬间刹住
  →下坠"，直线匀速是最不像的地方。闭式解还有个额外好处：**拖尾不需要存历史轨迹**，直接问同一颗火花
  `age - lag` 时刻的位置即可。
- **拖尾**：每颗火花 4-6 个实例（头 + `TRAIL_SEGMENTS` 个滞后采样），`TRAIL_LAG=0.05s`。
  ⚠️ 试过 0.032s，尾巴叠在自己头上，只是把点变胖成"泡泡"，反而更假——lag 必须明显大于一帧。
- **5 种样式**（契约要求）：菊花/柳树/环/心形/彩色渐变，见 `directionsFor` + `baseParamsFor`。
- **一场秀**：4-6 发、每发间隔 1.15-2.2s，随机 2-3 种样式（契约 3 节）。
- 每发有白色爆闪核心 + 上升火箭拖尾；火花有 twinkle 闪烁和"末尾骤灭"的 fade（`1-r³`，不是线性变暗）。

### 三个真实 bug（都不是"调参数"能解决的）

1. **`initial` 里重资源加载会挡住后面所有初始化**。`SpatialView.initial` 是一个顺序协程，原来先
   `Entity.loadSuspend("asset://castle.usdz")`（13MB）再 `System.register(FireworkSystem)`，结果城堡加载
   慢的时候烟花系统几秒都不注册，表现是"完全没有烟花"。**现在先建通道 + 注册 System，再加载城堡。**
   另外 `initial` 里抛异常不会崩溃、会被协程吞掉，所以整个 body 包了 try/catch 并打日志——否则症状是
   "场景建到一半就停了"且毫无线索。
2. **炸在容器外面的烟花会被直接裁掉，不会自动缩放适配**。把 apex/radius 调大后整场秀"消失"，就是炸到
   volumetric 窗口（0.5×0.42×0.35m，原点在几何中心）外面去了。现在 `HomeVolume.skyAbove()` 从窗口半尺寸和
   背景高度算出 `SkyBox`（apex 范围/最大半径/水平散布），director 只在这个盒子里布置炸点。
   **换场景模型或改窗口尺寸时，这个盒子会自动跟着变，不要再写死数字。**
3. **不同样式的实际半径差了 2.6 倍**。阻力下总位移是 `v0/k`，原来各样式直接配 `speed`，导致低阻力的柳树
   飞得比高阻力的菊花远好几倍、直接冲出容器。现在配的是 `reach`（相对 shell 半径的倍数），速度由
   `radius * reach * drag` 反推。重力同理改成 `maxRadius * GRAVITY_PER_RADIUS`，否则小容器里柳树会直接坠出画面。

### 性能实测（模拟器，非真机结论）

`FireworkDiag` 日志里的 `dt=` 就是帧间隔。实测：活跃实例约 600-700 个以内 `dt≈0.0417`（24fps，模拟器上限），
**超过约 800 个实例 `dt` 直接翻倍到 0.0835（12fps）**。每个活跃实例每帧一次 `update()`（一次 JNI + 3 次对象分配）。
当前配置 `SPARKS=52 × (1+TRAIL_SEGMENTS=5) = 312 实例/发`，两发重叠约 600，刚好压在拐点内。
**改 `SPARKS`/`TRAIL_SEGMENTS` 前后都要看一眼 `dt`。** 真机性能未测，需要 `pico-cli perf`，模拟器数据不能当结论。

`FireworkDiag` 诊断日志**是故意保留的**（每 0.5s 一行：t/dt/sparks/addFail/updFail/skybox/样本坐标+尺寸）。
调这套效果没有它基本是瞎猜。

## increment 16：沉浸模式（Stage）+ 手部追踪双击捏合

用户要求：点「沉浸」进 VR 空间并**按比例放大**城堡/月亮/天空盒/烟花；双手拇指+食指各锚一个小球
（参考 SpaceCube V1）；右手双击捏合放烟花、时长 3 分钟；左手双击捏合返回体积窗口。
追加要求：**进沉浸时关闭原体积窗口**（不是最小化）。

### 容器结构

`Main.kt` 现在注册两个容器：`DefaultWindowContainer`（入口，manifest 配）+ `Stage(id)`（DSL 声明，
**非默认 Stage 不需要写 manifest**，manifest 只配默认容器）。

- 进：`openStage(IMMERSIVE_STAGE_ID, StageStyle.Full)` → `closeWindowContainer(MAIN_WINDOW_ID)`。
  **先开后关**，中间不能出现"一个容器都没有"的瞬间。
- 出：`openWindowContainer(MAIN_WINDOW_ID)` → `closeStage()`，同理先开后关。
- `StageStyle.Full` = 接管显示、关掉 passthrough。它同时是手部追踪能用的前提。

**实测坑**：开 Stage 只会隐藏**别的 App** 的窗口，不隐藏自己的。第一版没关窗口，截图里体积窗口
连同它的黑色穹顶浮在沉浸场景中间，把地面切断成两半。

### 一个场景，两个尺寸

场景构建抽到 `content/Scene.kt` 的 `buildFireworkScene(content, root, bounds, director)`，
两个容器只是传不同的 `SceneBounds`。沉浸态 = 名义窗口尺寸 × `IMMERSIVE_SCALE=8`
（城堡 0.231→1.85m，穹顶顶点 ~5.3m），场景根抬高自身半高让地面落在脚下、后推 7m。

- **`getVisualBounds(relativeTo = null)` 是容器空间**，root 一旦平移就会把偏移折进每次原始测量。
  所以场景统一挂在一个 root 实体下，并且**用 root 当测量参考系**；窗口态的 root 是单位变换，
  只为让两边走同一条代码路径。
- **只有绽放带需要显式跟着缩放**。场景里几乎所有量都是半尺寸的*比例*，自动就跟着放大；
  但 `BURST_LIFT_M`/`BURST_BAND_M` 是**故意写成绝对值**的（increment 7 的教训：拉大容器只该多出
  空天，不该把烟花抬高）。所以 `SceneBounds` 多带一个 `scale` 字段，只有 `scaled()` 会改它，
  `skyAbove` 用它乘绽放带。漏了这步的第一版日志是 `apex -3.483..-3.133`（带宽只有 0.35m 贴着
  城堡顶），修好后是 `apex -2.993..-0.193 r=1.200`，正好是窗口态 `-0.373..-0.023 r=0.150` 的 8 倍。

### FireworkDirector 从单例改成按场景实例

两个容器会短暂同时存在，单例会让后 `bind` 的一方悄悄抢走前一方的通道。改成
`class FireworkDirector(label, showDurationS)`，常量进 `companion object`，
`attach()/detach()` 进出一个 `CopyOnWriteArrayList`，`FireworkSystem`（仍然全局只注册一个）
`tickAll` 所有活跃实例。所有 `FireworkDiag` 日志带 `[window]`/`[stage]` 前缀便于分流。
时长：窗口 60s，沉浸 180s。

### 手部追踪（照搬 SpaceCube V1）

`HandTrackingProvider` 带 `@RequiredFullSpace` —— 这就是它只能放在 Stage 的原因，在
`WindowContainer` 下会永远停在 PENDING 且毫无日志。20ms 轮询 `latestData`，
`PinchTracker` 用迟滞（0.010 起 / 0.018 松）+ 2 tick 去抖，双击窗口 600ms、**两只手各自计时**。
四个 0.008m 小球分别贴在左右手的拇指尖/食指尖（不是每只手一个放中点），
位置必须过 `handRoot.convertPositionFrom(raw, null)` —— 原始关节坐标不能直接当实体局部坐标。
`handRoot` 是直接挂在 content 下的单位变换实体，**不能挂到场景 root**，否则小球会跟着城堡一起被
平移放大。

模拟器实测 `start() -> SUCCESS, supportState=SUPPORTED, state=STARTED`，但
`first latestData: left=false, right=false`——模拟器没有手，手势本身只能在真机验证。
真机上手部追踪还受**头显系统固件版本**单独门控（SpaceCube 在这上面丢过一整轮调试）。

### 真实建筑尺寸（用户追加要求）

沉浸态城堡目标 **40m**（介于迪士尼睡美人城堡 23m 与灰姑娘城堡 57m 之间），
比例由 `IMMERSIVE_CASTLE_HEIGHT_M / NOMINAL_CASTLE_HEIGHT_M` 反解，不是手填的倍数。

**踩过的坑，值得记**：城堡是用三轴 `min` 适配的，**哪一项卡住取决于盒子形状**。
窗口那个又高又浅的盒子（0.6×1.35×0.45）里卡住城堡的是**宽/深**项（h=0.231）；
一旦把沉浸态地面加宽（`IMMERSIVE_FOOTPRINT_SPREAD=3`），宽/深项松开、**改由高度项**决定，
城堡直接变成 67.8m。所以 `NOMINAL_CASTLE_HEIGHT_M` 现在是**算出来**的
`NOMINAL_HALF_H * 2 * BACKDROP_HEIGHT_SHARE`，不是抄窗口的 0.231。
代码里那个"实际高度 vs 目标偏差 >10% 就 warn"的运行时检查当场抓到了这个错误——留着。

为什么要加宽地面：直接等比放大窗口的盒子，城堡会站在 102×76m 的邮票上，
想退到能看全 40m 建筑的距离就已经踩空了。只加宽 footprint 是安全的（见上）。
实测终值：`102x -- castle 40.0m, ground 180x135m, 58m ahead, dome apex 69m`，
观察距离取 `城堡高 × 1.7`，并夹在地面前沿内 10m。

### 天空盒渐变（用户追加要求）

纯黑穹顶在窗口态就是贴在墙上的一块黑板。改成 `assets/sky_gradient.png`（8×512 RGBA，
一极深蓝黑近乎不透明，另一极完全透明），配 `BlendingMode.TRANSPARENT`。
球面 UV 的 V 是极到极，所以一维贴图就够。`MaterialCullingMode.FRONT` 顺带保证渐变只混合一次。

### 大尺度暴露出来的"绝对值常量"

按比例放大会把所有**写死绝对米数**的常量打回原形。已修三处，改常量时注意同类问题：

- `BURST_LIFT_M` / `BURST_BAND_M`：乘 `SceneBounds.scale`（新增字段，只有 `scaled()` 会改它）。
- `MOON_DROP_M`：同上。
- **通道停放点**：原来是常量 `CHANNEL_PARK = (0,-5,0)`，用来把 `ModelComponent` 的源网格藏到
  容器外。在 1.35m 窗口里它在外面，在真实尺寸场景里它**落在场景正中**，那颗杂散网格就冒出来了。
  改成 `-bounds.halfH * 3` 由调用方算好传进 `bind()`，`transformOf` 减的也是它。
  Stage **完全没有裁剪**，窗口态靠裁剪掩盖的东西在这里都会露出来。

### increment 16 后续三轮（月亮 / 爆炸反光 / 星点细腻度）

**月亮抬高**：`MOON_DROP_M` 改回**不乘** `scale`。它是给桌面尺寸窗口调构图用的绝对值，
乘上比例后把沉浸态月亮那 20.7m 的份额抵消得只剩 0.26m，月亮就贴在场景腰部了。
绽放带该乘、月亮下降量不该乘——两者都是绝对值，但用途不同。

**烟花照亮场景：真实点光源在这条渲染路径上不生效**（重要，别再试一遍）。
`PointLightComponent` 是有的，也照文档把地面换成了 `PhysicallyBasedMaterial`。
组件回读证明值确实写进去了（`slot=0 set=1621lm r=0.30m -> readback=1621lm r=0.30m`），
但地面实测亮度在 4 帧里**恒为 49.0/255**，强度加到 ~2500 流明 / 0.3m 半径仍然纹丝不动。
所以改成**不依赖光照管线**的做法：`FireworkDirector.washSurfaces` 持有地面材质，
绽放时按"离发射点多近"给一个 0.75 的洗色量，用 `setBaseColor` 乘在贴图上（1.0 = 原样），
0.7 秒平方衰减。实测静置 36.7、闪光帧 46.6（+27%），确认起落。
地面已改回 `UnlitMaterial`——PBR 那套在模拟器上不亮，真机上还可能反过来把地面变黑。
**城堡不参与**：它用的是 usdz 自带材质，没有句柄。

**沉浸态"色块"**：星点尺寸原来是线性乘 `scale`，181 倍下 5mm 的球变成 0.9m 的团。
改成 `starScale = scale^STAR_SIZE_EXPONENT`。这个指数要按**视角尺寸**调，不是按比例调：
0.35 → 星点 3cm，58m 外是亚像素，烟花几乎消失（矫枉过正）；**0.65 → ~30 倍、约 15cm**，
读作亮点。同时火花数按 `scale^0.18` 从 52 涨到上限 120——半径从 15cm 变成 46m，
同样的点数铺上去就是稀疏的几颗。窗口态两个值都保持原样（`starScale=1.03, sparks=52`）。

### 暖色月光（用户："默认状态光线太冷清，月亮偏黄暖一点，场景要有自然月光暖色调"）

没有真实光源可用（见上），所以"月光"是**把暖色乘进每个场景材质的 base color**。
关键手法：**原地染色，不替换材质**。

`ModelComponent.materials[i]` 声明返回基类 `Material`，但 SDK 的 `MaterialAdapter.toMaterial()`
做了 `is` 分发、**实际给的是具体子类**，所以 `is UnlitMaterial` / `is PhysicallyBasedMaterial`
能命中并调 `setBaseColor`。而 `setBaseColor` 是**乘在贴图上**的（1.0 = 原样），
于是既能染色又完整保留贴图。实测城堡有 **5 个材质**全部染到——要是像地面那样替换成单一材质，
石墙、屋顶、鎏金尖顶会被压成一个颜色。

- `MOONLIGHT_TINT = (1.12, 1.05, 0.95)` 用于**地面**：既暖移又提亮（原来不只是中性，是发闷）。
  首版是 (1.28, 1.12, 0.86)，用户反馈"太强烈"——**蓝通道才是关键**：压到 0.86 是在从表面里抽色，
  不只是加暖，地面直接变琥珀。提亮保留了，因为最初的抱怨"太冷清"里有一半是亮度不是色相。
- `LANDMARK_TINT = (1.02, 1.05, 1.09)` 给**城堡**，蓝通道**高于 1**。用户反馈"建筑颜色有点偏黄"：
  同一个暖色乘在两种表面上效果完全不同——地面是深色大理石，蓝压到 0.95 几乎看不出；
  城堡是近白石材，同样的色调直接读成黄。而且**中性色调也去不掉黄**：实测未染色的城堡自带 R−B=+6.2，
  奶油石色是贴图本身的。所以蓝要推过 1 才能把 R 和 B 拉平。实测 R−B：+6.2（原始）→ +4.8 → **+1.1**。
- `MOON_TINT = (1.40, 1.24, 0.95)` 给月亮，更暖更亮——它是光源。
  月亮**故意不进 `washSurfaces`**：烟花把月亮照亮是反的。
- `TintedSurface` 记住自己的静置色，烟花洗色**叠加在月光之上**而不是从纯白开始。

实测（同机位、同裁剪区域）—— 无染色 → 首版(过强) → 现值：
地面 (35.8, 37.3, 37.0) → (52.3, 46.1, 31.5) → **(42.0, 40.5, 34.6)**；
城堡 (47.3, 49.8, 47.7) → (57.6, 56.9, 49.3) → **(55.6, 55.2, 50.7)**。
暖度（R−B）从 +20.8 收到 +7.4，提亮从 +46% 收到 +17%。再调就是 `MOONLIGHT_TINT` 一个常量。

### 音效：用户素材 + 禁音开关

**先做的合成版已被替换。** 原来是自己写 PCM（上扫哨声 + 三层噪声爆破）灌进 `AudioTrack`，
能跑（每次播放都回读到 `PLAYSTATE_PLAYING`），但用户随后提供了真实素材——录音比手调波形好，
没有理由留着合成版。历史价值只有一条：`ToneGenerator` 表达不了频率扫描和噪声包络，别再往那边走。

现在是 `SoundPool` + `assets/sfx_*`（用户提供的 4 个文件）：
`sfx_launch.mp3`（woosh）、`sfx_burst_small.mp3` / `sfx_burst_mid.wav` / `sfx_burst_big.wav`。
`build.gradle.kts` 的 `noCompress` 加了 `mp3`/`wav`——`SoundPool` 是通过 `AssetFileDescriptor`
直接读 APK 的，必须 Stored（已核对 APK 里 4 个都是 `Stored`）。

**样本选择别按弹径。** 第一版按 loudness 分三档，实测**每次都落在最大的那个**（日志里全是 `sound=4`）：
弹径本来就只在天空最大半径的 0.72–1.0 之间浮动，这点跨度分不开三档。
改成**加权随机**（`SIZE_BIAS=0.35` 来自尺寸、其余靠掷骰），外加 ±8% 的播放速率抖动，
避免同一个样本重复播放的罐头感。

**禁音**：`FireworkSfx.muted` 是一个**进程级 Compose state 对象**——窗口和 Stage 是两套独立
composition，状态放任何一边都会导致切换容器后失效。按钮用 SpatialUI 的 `ToggleIconButton`，
图标是 `MuteButton.kt` 里**自绘的 `ImageVector`**：`androidx.compose.material.icons` 正是本项目禁用的，
而 SDK 自带的 SDF 图标集是按名字通过 manifest 数组寻址的、拿不到 `ImageVector`。24×24 上两个形状而已。

`FireworkAudio` 是 **app 级单例**（`shared(context)`，持 applicationContext），不随场景销毁——
`director.detach()` 只置空引用不 release，否则关掉窗口进沉浸会把池子拆了。

**验证到什么程度**：`pool ready`、6 次播放 `stream` 全部非 0（返回 0 表示池子拒绝），
样本分布确认 mid/big 都取到了，无崩溃，截图确认扬声器图标出现在【点火】和【沉浸】之间。
**声音本身和禁音按钮的点击都没法在模拟器上验证**——听不到，且 2D tap 打不到 Augment（见下）。

### 控制面板：三个图标 + tip 标签（折叠功能已移除，射线不派发悬停）

`content/ControlPanel.kt` 是**窗口 Toolbar 和 Stage HUD 共用的同一个 composable**——之前两边是各写
一遍的 Row，已经开始漂了（一边 点火/沉浸、一边 点火/退出沉浸），合并掉。
差异只剩两个参数：第三个按钮的图标和文案。三个按钮为**纯图标、无文案**，面板背景**透明**（`Augment(enableMaterialBackground = false)`）——
2026-08-11 用户两次要求。文案没删掉，转到了 `contentDescription`，无障碍和 UI dump 仍然拿得到。
（顺带：SpatialUI **没有 Tooltip 组件**，组件清单里只有 `SpatialPopup`/`Coachmark`，
所以中间那版的 "tip 标签" 是图标下方的 `Text`，不是悬浮提示。）

禁音键**故意也用 `IconButton` 而不是 `ToggleIconButton`**。后者语义上更对，但默认值和邻居不一样：
尺寸 `Min`(38×32) 对 `Regular`(56×48)、形状不同、容器色也不同，用户直接反馈"中间按钮跟旁边两个不一致"。
而且**对不齐**——`ButtonColors.containerColor` 是 `internal`，读不出 IconButton 的默认色来喂给它；
手抄颜色角色只会多一处会漂的地方。用同一个组件则天然一致，状态靠图标（喇叭 ↔ 带叉喇叭）
和标签（禁音 ↔ 取消禁音）表达。

### 这个容器里射线/注视**不派发悬停事件**（实测结论，别再试）

用户要求过"未聚焦收缩为一个图标、聚焦展开"。实现了，真机上打不开，最后按用户要求改成常驻展开。

**证据**：在四个 interaction source（面板 Row + 三个按钮）上都挂了悬停日志，真机整个会话
`FireworkPanel` 只打出**一条**初始 `hover=false`，`hover=true` **0 次**。
同一个版本在模拟器上用鼠标光标立刻报 `panel=true`——**采集代码是对的，事件本身不存在**。

两条走过的死路：
- `Modifier.hoverable` 只挂外层 Row：射线悬停派发给**可交互元素**，裸 Row 不是。
  改成从按钮自己的 `interactionSource` 收（`IconButton`/`ToggleIconButton` 都接受并向下传）
  是正确做法，但依然什么都收不到。
- `Modifier.spatialHoverEffect` 看着像 PICO 原生答案，**不是**：它的 block 在初始化时被调用两次
  （`isActive=true` 一次、`false` 一次）用来预计算两种视觉状态。它描述效果，不上报悬停。

以后要做"聚焦才显示"的 UI，**先加日志确认事件存在**再写逻辑。

### 图标必须按 24dp 的实际尺寸画

四个图标（火焰/喇叭开/喇叭关/展开收起）都是 `PanelIcons.kt` 里手绘的 `ImageVector`：
`androidx.compose.material.icons` 正是本项目禁用的，SDK 自带的 SDF 图标集又是按名字通过 manifest
数组寻址、拿不到 `ImageVector`。

**踩到的坑**：第一版火焰造型更"像火"，但只占 24 格里的 9 格宽，**在设备上渲染成了一条白色细缝**。
放大截图确认后重画成饱满的水滴 + 肩部缺口（横跨 5.4–18.6），24px 下清晰可辨。
展开/收起图标同理砍掉了四条对角线，只留四个角标——那个尺寸装不下更多细节。
**画完先用 24px 离线光栅化看一眼，别只看放大图。**

### 文字烟花（开场 "Pico" / "Story"）

`content/FireworkText.kt` + `FireworkDirector` 里的 `ShellPlan.text`。开场两发拼字，之后才是随机场次。

**能这么做的关键**：`burst()` 里 `velocity = direction * speed`，而线性阻力下总位移是 `v0/k`、
正比于初速——所以传**非单位向量**就能让火花落在按比例更近的位置，用向量长度编码字形。
字形点阵取值 −1..1，现成的 radius/reach 机制负责缩放。

配套三个必须的调整：
- **关掉速度抖动**。普通弹的 ±15% 抖动会把笔画糊成一团云，字样弹 `speedJitter = 1f`。
- **z 恒为 0**，不走 `Z_FLATTEN`，让整个词落在同一个正对观众的平面上。
- **单色**。多色的词明显更难读，所以字样弹只取一个 tint。
- `WORD_PARAMS`：低闪烁、长寿命、只有 1 段拖尾、星点稍大——拖尾多了每个点都成彗星，笔画就散了。

字体是手绘 5×7 点阵（只做了 P S i c o t r y 八个字），因为一个火花在爆开后有几厘米粗，
比这更细的字形采样下来也是这几个点。"Pico" 45 个火花、"Story" 57 个，都在预算内。

**验证状态**：字形几何**离线渲染验证过**（同一份点阵用 Python 光栅化，两个词都清晰可读）。
**没能在设备上看到实际绽放**——字样弹在 showClock 0.15s / 2.75s，加上火箭上升 0.6s 左右，
而 `pico-cli capture screenshot` 连拍必须间隔 ≥2s（间隔小于这个会返回重复帧），
掐点抓拍变成撞运气；期间还遇到系统启动器面板抢前台。真机当时掉线，装不上。
**下次验证建议**：临时把 `WORD_GAP_S` 调大、或让字样弹重复几次，比追帧靠谱。

### 验证方式上的坑

**2D tap 注入打不到空间容器里的 Augment**（技能文档写了，实测也确认：`input tap` 打在「沉浸」
按钮位置毫无反应）。为了验证 Stage，临时加了一个 6 秒后自动 `enterImmersive` 的
`LaunchedEffect`，验完删除。**所以「沉浸」按钮的点击链路本身没有在模拟器上验证过**，只验证了
它调用的那条路径；按钮要在真机上点。

## increment 15：穹顶以城堡底座为心，改成**月亮让位**

用户："天空盒的中心应该以城堡底座中心为中心点，并且天空盒应该囊括月亮"，外加"月亮高度下降 0.2m"。

### 关键结论：只挪中心会让情况更糟

increment 14 的穹顶（心在 y=−0.090，b=0.585）代入月亮 3D 位置得 **1.229**（>1 即在穹顶外），
用户看到的"侧面露出"属实。但**光把中心挪到城堡底座会更糟**：月亮变成在穹顶中心上方 0.877m，
它那个高度的水平截面从 0.257×0.193 缩到 0.225×0.169，**月亮圆心本身**就到了 1.022。

想靠加大穹顶解决也不行。x/z 半轴已经顶到容器壁（再大就捅出墙、被裁出洞），b→∞ 的极限值是
**0.968**，只剩 3% 余量；实算 b=4m（穹顶高 3.3m）仍是 1.016，要 **b≈8m** 才包得住。
决定性的一条：穹顶顶部收在天花板下时 b=1.342，光 y 项就占 0.427，留给 x/z 的只有 0.573，而
月亮原位置的 x/z 项是 0.586——**即使把月亮缩成一个点，待在原地也在穹顶外**。

所以方向反过来：**穹顶尺寸是给定的（x/z 顶到墙、顶点收在天花板下），让月亮往里收。**

### 实现

- `SkyDome(ax, b, az, cy)` + `skyDomeOver(halfW, halfH, baseY)`：`cy` = 城堡底座 Y（= `launchOrigin.y`，
  城堡本来就在 x=z=0），`ax/az` = 墙内 0.99，`b = halfH*0.99 − cy`。三个数全部来自实测，无手调。
- `loadMoon(…, sky)` 拿到穹顶后，把水平偏移按 `pull` 逐步内收直到 `sky.contains(月亮, r+clearance)`。
  **只收 x/z，不动高度**——高度是构图，水平偏移才是余量。
- 实测：`wanted=(0.156, 0.003, −0.124) pull=0.72 → centre=(0.112, 0.003, −0.089) inSky=true`；
  穹顶 `semi=(0.297, 1.342, 0.223) base=−0.674 apex=0.668`（天花板 0.675）。

### 一个不能用的捷径（踩过）

判断"球是否整个在椭球内"**不能**把椭球三个半轴各减去球半径再测球心。那只对**正球**成立。
反例：半轴 (10, 1)、r=0.5、球心 (9.0, 0.15) 能通过缩轴测试，但球面实际到 1.13。
本项目椭球是 0.297×1.34×0.223，偏心极大，所以 `SkyDome.contains` 老老实实**采样球面**
（12×24 个点，加载时算一次，代价可忽略）。

### 副作用（正面）

中心落到底座后，黑色是**从地面满宽起拱**的，increment 14 里"城堡底座和地面板露在黑色之外"
的问题自动消失。另外月亮下降 0.2m 后落在 y≈0.003，和烟花绽放带（顶到 +0.127）有重叠，
视觉上是烟花在月亮附近炸开——目前看着正常，如果不想要就把 `MOON_DROP_M` 调小。

## increment 14：黑色天空盒（自建穹顶，尺寸由月亮反解）

用户先给了 `Extracted_Minecraft_Java_Editions_Stars.usdz`（纯几何星点，无贴图），再给
`Starry_Night.usdz`（4096×2048 等距柱状星空全景，emissive），最后否掉两者：
**"移除夜空模型，直接使用自建天空盒（黑色）来模拟，让天空盒的半径弧度刚好包括月亮上方一点"**。
两个模型和 `sky_color.jpg` 都已从 `assets/` 删除。

### 硬约束：天空盒不能"包住"相机

内容被裁到 **SpatialView 的盒子**（不是容器），所以**任何完全包住盒子的球面都在盒外、会被整块裁掉、
一个像素都看不到**。天空必须把面**放在盒内**。这条同时否掉了"半径足够大的球"和"包裹式 skybox"。

配套的一条：`MaterialCullingMode.FRONT`。我们是**从盒外往里看**，闭合曲面上所有能看见的面都是正面，
FRONT 剔除把它们全剔掉，剩下的就是远端的内表面——这才是"天空"而不是"一个黑球"。这个判定是逐片元、
按当前视角算的，用户绕到侧面也照样成立。

### 为什么是椭球，高度怎么来的

容器 0.6 × 1.35 × 0.45 m，远不是立方体。真球一旦大到能罩住场景，就会从侧壁/后壁捅出去被裁出洞。
所以 **x/z 半轴钉在墙内（`halfW*0.99` / `halfD*0.99`），只留高度自由**。

高度不是调出来的，是**解出来的**——要求椭球面正好穿过月亮顶上方 `SKY_DOME_MOON_CLEARANCE_M` 处：

```
(p.x/ax)² + (p.z/az)² + ((p.y − cy)/b)² = 1,  cy = bottom + b
⇒ b = (p.y − bottom) / (1 + √(1 − (p.x/ax)² − (p.z/az)²))
```

所以**月亮挪，天空跟着挪**。月亮被推到角落导致根号里为负时，退回"填满容器高度"。
实测：月亮中心 (0.156, 0.203, −0.124) r=0.054 → 半轴 (0.297, 0.585, 0.223)，中心 y=−0.090，
跨度 y=[−0.675..0.494]，窗口顶是 0.675，即穹顶上方还留约 0.18m 透视。

底面和 increment 13 的工具栏同理，压到**地面之下**（那里正好有 `SCENE_LIFT_M` 的空间），
否则穹顶自己的底会盖住城堡站的地面。

### 一个被实测推翻的判断

截图上看穹顶下半部"透出了床"，我一度以为纯黑在 passthrough 下会被键成半透明，还改成了深蓝黑。
**采样打脸**：穹顶内像素恒定在 ~25/255，背后是亮枕头(172)、画(116)还是床单(129)都一样——
它是不透明的，那 ~25 是合成器叠的 passthrough 薄雾，调材质颜色去不掉。已改回纯黑。
教训还是那条：**看图下结论前先采样**。

## increment 13：Toolbar 后仰 45° + 底边落到地面上

两次连续的用户要求，最后落在同一段代码上（`HomeVolume.kt` 里的 `Augment`）：

1. "Toolbar 稍微倾斜45度，方便用户从上往下看"
2. "当前需要UI控制面板底部放置在地面上"

**内置 `Toolbar` 表达不了第 1 条**：反编译 `com.pico.spatial.ui.design.windows.Toolbar` 看到它内部把
`rotation3D = null` 写死，也没有暴露旋转参数。所以改成直接用它底层的裸 `Augment`，其余参数
（`BottomFront` 锚点、`BottomCenter` 对齐、Material.Regular 玻璃、32dp 圆角、64dp 最小高度、
12dp 内边距、8dp 间距）照抄 `Toolbar`，让它仍然读起来像系统工具栏。这是**有意偏离**
"优先用内置组件"的设计规则，不是偷懒，注释里已写明原因。

**第 2 条的定位公式**（`AugmentPositionProvider`，`Augments.kt`）：

```
y = windowSize.height * anchor.y - contentSize.height * alignment.y + offset.y
```

`anchor.y=1`（BottomFront）配 `alignment.y=1`（BottomCenter）⇒ **内容底边 = 视图底边 + offset.y**，
且 `offset.y` 是**屏幕向下为正**。内置 `Toolbar` 用的是 `+32.dp`，所以它是"挂"在容器下方的。
要让底边落到地面上，offset 就得是负值，大小 = **地面相对容器底面的高度**：

```kotlin
val groundLift = launchOrigin?.let { converter.lengthToDp(it.y + halfH, LengthUnit.Meters) } ?: 0.dp
offset = DpOffset3D(0.dp, -groundLift, 0.dp)
```

用 `launchOrigin.y`（= `grassTopY`，城堡站的那个面）而不是手调的常数，这样换地面模型、
容器被 resize 都自动跟着走。实测 `Ground.usdz` 的 raw 尺寸是 `100.00 x 0.00 x 100.00`——
**厚度为 0**，所以地面就在 `floorY = -halfH + SCENE_LIFT_M` 处，`groundLift ≈ 1mm`。
也就是说这次可见的变化几乎全部来自"去掉 Toolbar 那 32dp 的下挂"，不是来自地面厚度。

倾斜的 pivot 用 `NormalizedPoint3D.Bottom`：**旋转轴就是落在地面上的那条底边**，所以倾斜时
接触点不动，两条需求不打架。

## increment 12：月亮

用户提供 `Moon.usdz`（4.5MB，baseColor + normal 贴图）→ `assets/moon.usdz`。`loadMoon()` 放在
**烟花带故意空出来的上层天空**里（当时用户刚指出头顶空了 59cm）。

- 位置全部写成**实测视图半尺寸的比例**（`MOON_*_SHARE`），窗口被 resize 也不会跑偏；
  三个轴都做了 `coerce`，保证整个球体留在容器内。
- **居中**放置（`target - center*scale`），不是像背景那样"坐"在什么东西上——它是浮空的。
- **z 取负**（往里推 0.55×半深），让它读起来是远处的天体、在烟花后面，而不是混在烟花里的道具。
- **独立 try/catch**：月亮加载失败只损失月亮，不会连累地面和城堡（外层 catch 会退回占位地面）。

实测：原始直径 2.00 → 缩放 0.054，半径 0.054，中心 `(0.156, 0.405, -0.124)`，三轴都在界内。
这个模型是**普通 usdz + jpg 贴图**（UsdPreviewSurface），不是 `Ground.usdz` 那种 ShaderGraph，
所以直接加载就正常显示，不需要 increment 9 的贴图搬运手法。

## increment 11：两个多余的球 + 地面贴到底座（用户观测到的 bug）

用户反馈"体积窗口上面和下面各有一个球"。查下来是**三件事，其中两件是我的 bug**：

1. **窗口正中那个亮点 = `ModelComponent` 的源网格**。`ModelComponent(mesh, material) + meshInstances`
   这种写法里，**即使 instances 驱动着真正的火花，组件仍然会在实体自身的 transform 上把源网格画一遍**。
   6 个颜色通道实体都停在默认原点（= SpatialView 中心），叠出一个常驻亮点，**烟花没放的时候也在**。
   修法：把通道实体停到视野外（`FireworkDirector.CHANNEL_PARK = (0,-5,0)`），并在 `transformOf()` 里
   把这个偏移减掉——instance 坐标是实体局部空间的，所以外部调用方仍然按绝对坐标写，火花位置不变
   （实测 `PEAK y=[-0.494..0.039]`，没有跑到 -5 去，说明补偿正确）。
   **⚠️ 以后再用 `meshInstances`，记得源网格会自己画一份。**
2. **城堡里那个小黄点 = 我留下的调试球**。契约 2.2 明明白白写着 `fireworkLaunchAnchor` 是
   **"非可见 Entity"**，我在 increment 1 为了验证放置算法画了个发光小球，之后一直没删。已删除。
   **教训：调试可视化要么当场删，要么记进待办，别指望以后会自己想起来。**
3. **底部那个白蓝圆球不是我们的**。翻出 onboarding 阶段的第一张 baseline 截图（那时项目还是模板、
   连场景代码都没写），这个球就已经在了——是系统的空间光标/指示器。

另外按用户要求把 `SCENE_LIFT_M` 从 0.02 改成 **0.001（0.1cm）**，地面几乎贴着容器底座。
实测 `ground top=-0.674`（底座在 -0.675）。

## increment 10：整场 60 秒 + 按钮文案改短

- **场次时长 60 秒**（`SHOW_DURATION_S`）。`ignite()` 原来是"排 4-6 发就收工"，现在改成
  **按时钟排期**（`while (t < SHOW_DURATION_S)`），发间隔仍是 1.15-2.2s，全场约 35 发。
  改时长只需要动这一个常量，发数会自动跟着变。
  ⚠️ **这不会抬高峰值开销**：单发寿命 < 3s、间隔 1.15-2.2s，所以同屏存活的烟花数量没变，
  变长只是"总发数变多"，不是"同屏更多"。实测 `dt` 全程仍在 0.04 附近，`PEAK` 三轴照旧在界内。
- **按钮文案**：`重新点火 → 点火`、`切换沉浸 → 沉浸`（契约第 4 节已同步）。
- 实测确认：t=42s 时 `sparks=52`（仍在放），t=70s 时 `sparks=0`（已收尾）。

## increment 9：换成 `Ground.usdz` 平面地面 + **ShaderGraph 材质不支持的坑**

用户用 `WorkSpace/Test/Ground.usdz` 替换草地。这个模型是 Spatial Editor V6.0.1 导出的
**纯平面**（`extent = [(-50,0,-50),(50,0,50)]`，100×100，**Y 方向厚度为 0**），带大理石材质。

### 坑：ShaderGraph 材质在裸 `.usdz` 加载路径下不受支持 → 渲染成棋盘格占位图

```
W SPC-…-Material: ShaderGraph[/Root/Plane/Marble_13/material/M_Marble_13] is unsupported.
E SPC-…-UsdGeomGprimReader: Failed to get Material,Plane!
```

几何体正常加载、位置也对，但表面显示为灰白棋盘格（缺贴图占位）。根因：`materials-lighting-effects.md`
里写得很清楚——**`ShaderGraphMaterial` 属于 Editor 的 AssetBundle 管线**（`.bundle`），
不是 `Entity.loadSuspend("asset://x.usdz")` 这条路能解析的。

**当前解法（不需要用户重新导出）**：把颜色贴图从嵌套的 `0/Marble_13.usdz` 里解出来，
降采样成 `assets/ground_color.jpg`（2048²，1.1MB），运行时用 `applyGroundMaterial()` 遍历模型
子树，把每个 `ModelComponent` 的 `materials[0]` 换成自建的 `UnlitMaterial`（贴 baseColorTexture，
`MaterialCullingMode.NONE` 防止从背面看不见）。用 Unlit 而不是 PBR，是因为这个场景**没有布任何光源
和 IBL**，PBR 表面会渲染成全黑。
日志确认：`ground material applied to 1 mesh node(s)`，棋盘格消失，大理石正常。

**真正彻底的解法**（若要连法线/粗糙度一起要）：在 Spatial Editor 里导出成 `.bundle`，
改用 `AssetBundle.load(...)` + `bundle.loadModel(...)`。需要用户那边重新导出，没有擅自做。

### 平面地面可以非等比缩放

因为是**纯平面**，X/Z 可以独立缩放去铺满非正方形的 footprint（0.6×0.45），不会像地形模型那样被拉扯变形。
之前那块正方形草地就是因为不能非等比拉伸，只能等比缩放，所以左右留了空隙。
`GROUND_*_SHARE` 现在配 0.98，实测 `scale=(0.0059, 0.0044, 0.0044)`，地面铺满窗口。
厚度为 0 的模型要注意 `maxGroundH / groundSize.y` 会除零，代码里有 `> 1e-5f` 的保护。

## increment 8：草地作地面，城堡站在草地上（已被 increment 9 替换）

用户提供 `The_Hulunbeir_Grassland_and_track.usdz`（7.3MB，呼伦贝尔草地）→ `assets/grassland.usdz`。
`loadSceneBackdrop` 现在加载两个模型并返回 `SceneAnchors(landmarkHeight, landmarkTopY, launchOrigin)`：

1. **草地 = 地面**：按 footprint 铺满（`GROUND_WIDTH_SHARE/DEPTH_SHARE = 0.98`），底面坐到容器地板；
   高度另有上限 `GROUND_MAX_HEIGHT_SHARE = 0.10`，防止带地形起伏的模型偷偷吃掉烟花要用的天空。
2. **城堡 = 地标**：底面坐到**草地顶面**（不再是容器地板），footprint 中心对齐 x/z=0。
3. 烟花发射点 = 草地表面；`skyAbove()` 的入参从"背景高度"改成**城堡屋顶的绝对 Y**（`landmarkTopY`），
   因为现在地面有厚度，"高度"已经不足以定位屋顶。

实测：草地原始 `29.03 x 3.00 x 29.03` m（真实测绘尺度），缩放 0.0152 → 顶面 `-0.629`；
城堡 `h=0.231` 站在其上 → 屋顶 `-0.399`；炸点带 `-0.329..0.021`。

⚠️ **已知不足**：草地模型是正方形（29×29），容器 footprint 是 0.6×0.45（长宽比 1.33），等比缩放
被**深度**卡住，所以草地只有 0.441 宽、没铺满 0.6 的窗口宽度，左右会露出一点空隙。要铺满就得非等比
拉伸（会拉扯贴图）或容许深度方向被裁掉。**没有自作主张改，留给用户决定。**

⚠️ 城堡是放在草地**包围盒顶面**上的。这个模型有 3m 的地形起伏，如果最高点不在正中央，城堡看起来会
略微悬空。目前实拍看着是贴合的，但换其它带地形的模型时要重新确认。

## increment 7：容器再加高 1/2（0.9 → 1.35 m）

用户要求"当前容器高度再增加 1/2"。manifest `defaultsize` 改成 `0.6x1.35x0.45`，
`FALLBACK_HALF_H` 同步 0.45 → 0.675（这个常量只是首帧兜底，真实值靠运行时实测）。

系统实际给到 `size(1500, 3375, 1125)` → 3375/2500 = **1.35m，足额**（未被 min/max 5400 夹）。
运行时实测 `view half=0.300x0.675m`，skybox `apex -0.317..0.458 r=0.150`。

⚠️ **加高不会让烟花变大**：`skyAbove` 的半径是 `min(高度余量/2, halfD*0.8, halfW*0.5)` 三者取小，
现在 0.150 是被**宽/深**卡住的（`halfW*0.5 = 0.15`），不是高度。**想让单发更大必须加宽/加深，不是加高。**

### 用户纠正：改容器高度 ≠ 把烟花放高（重要，别再犯）

第一版实现里 `apexMax = ceiling - radius`，即"烟花铺满整个可用天空"。于是容器一加高，烟花的炸点
也跟着飞高了。用户明确纠正：**"我是让你给容器增加高度，不是让你同时把烟花高度也一致调整放高"**。

改法：炸点固定在**背景顶部往上一条固定宽度的带子**里（`BURST_BAND_M = 0.35f`，就是 0.9m 容器时
那条带子的宽度），天花板只当上限 clamp，不再参与决定炸多高：

```kotlin
val apexMin = (backdropTop + radius * 0.85f).coerceAtMost(ceiling - radius)
val apexMax = (apexMin + BURST_BAND_M).coerceIn(apexMin, ceiling - radius)
```

校验：相对城堡顶的位置在两种容器高度下完全一致（`castleTop+0.127 .. castleTop+0.477`），
容器多出来的高度全部变成**头顶的空白天空**。

**通用原则：容器尺寸变化只应该改变"可用空间"，不应该自动改变内容的位置/大小。**
凡是拿容器边界直接算内容位置的地方（`ceiling - radius` 这种）都要警惕。

### 调整烟花高低就改这一个常量

用户随后又要求"烟花高度再下降一点"。原来炸点下沿写的是隐式的 `backdropTop + radius * 0.85`
（约 0.128m，还跟爆炸半径耦合），现在抽成显式常量：

```kotlin
private const val BURST_LIFT_M = 0.07f   // 最低那发炸点距离背景顶部的高度
```

整条带子跟着它上下移动，且**与容器尺寸、爆炸半径都解耦**。
**以后用户说"高一点/低一点"，只改这一个数，别再动 `BURST_BAND_M` 或天花板系数。**
（0.128 → 0.07 那次实测：apex 带从 `-0.317..0.033` 降到 `-0.374..-0.024`。）

实测峰值（模拟器 / 真机）：`|x|=0.155 / 0.223`、`y=[-0.432..0.099] / [-0.405..0.050]`、
`|z|=0.070 / 0.060`，对应限值 ±0.300 / ±0.675 / ±0.225，头顶留出约 0.58m 空白。

## increment 6：裁切问题的真正根因（用户第二次反馈"部分烟花还是被裁切"）

加高容器只解决了一半。逐条量出来的结论：

1. **裁切体是 `SpatialView` 的包围盒，不是 WindowContainer**。实体加进 `SpatialViewContent` 后活在
   SpatialView 空间里（原点是它包围盒的中心，见 coordinates-and-units.md）。之前 `SpatialView` 没给任何
   modifier，尺寸由 Compose 测量决定，**跟 manifest 的容器尺寸不是一回事**。现在加了
   `Modifier.fillMaxSize().depth(...)`，并用 `onGloballyPositioned` + `LocalPhysicalLengthConverter`
   （`LengthUnit.Meters`，注意**不是** `METERS`）**在运行时量出真实半尺寸**，skybox 由实测值推导，
   `FireworkDirector.updateSky()` 支持窗口被用户 resize 后重新收敛边界。
   实测结果：`view half=0.300x0.450m`，确实等于 manifest 的 0.6×0.9——**但这是量出来的，不再是假设**。
2. **深度是唯一量不到的轴，也是最容易穿帮的轴**。`onGloballyPositioned` 只给 2D 布局坐标（没有 z），
   `Modifier.depth()` 文档也写明只是"preferred size，父约束可以覆盖"。而容器深 0.45m 却宽 0.6m 高 0.9m，
   球状爆炸最容易从前后壁穿出去。而且 `skyAbove` 里 `spreadZ` 被 `coerceAtLeast(0f)` 悄悄夹成 0，
   **这本身就是"深度预算不够"的信号，却被我吞掉了**。现在给所有星的方向乘 `Z_FLATTEN=0.5` 把爆炸压扁：
   横竖照样大（空间和观众都在这两个方向），前后收窄。
3. 天花板系数 0.94 → 0.90，给星星自身的 mesh 半径留余量。

**现在用实测数字回答"到底出没出界"**（`FireworkDiag` 每 0.5s 打一次全程峰值 `PEAK |x| / y / |z|`）：
- 模拟器：`|x|=0.222` (限 0.300)、`y=[-0.063..0.342]` (限 ±0.450)、`|z|=0.070` (限 0.225)
- 真机：`|x|=0.223`、`y=[-0.105..0.290]`、`|z|=0.065`
三轴都有 24%~69% 余量。**以后再有人问"会不会被裁"，跑一次看 PEAK 行，不要靠推理。**

**真机帧率实测**：`dt≈0.0140`（约 71fps），模拟器是 `dt≈0.0417`（24fps）。
**模拟器那条"800 实例帧率砍半"的结论不适用于真机**，真机余量大得多，密度还能往上加。

## increment 5：容器加高（用户反馈"高度太矮，绽放被裁切"）

- `AndroidManifest.xml` `defaultsize` **0.5x0.42x0.35 → 0.6x0.9x0.45 米**。高度是烟花能炸多大的直接上限
  （`skyAbove()` 用"背景顶部到天花板"的余量算爆炸半径），窗口现在是"竖"的，因为烟花需要天空。
  ⚠️ `HomeVolume.kt` 里的 `WINDOW_HALF_W/H/D` 是这个 manifest 值的**手工镜像**，运行时没有任何地方回读容器
  真实尺寸——**改 manifest 必须同步改这三个常量**，否则 skybox 算错、烟花又会炸到容器外被裁掉。
- 顺带修掉一个真 bug：`burst()` 里的 `speedJitter` 最大 1.15，而位移是 `radius * reach * jitter`，
  所以最快的那批星最多会**超出 skybox 15%** 被容器壁裁掉。现在 `ignite()` 里 `radius` 先除以
  `SPEED_JITTER_MAX` 再抖动，保证总位移不超过 `maxRadius`。
- 背景城堡同步放大到 `FIT_HEIGHT=0.26`（窗口变高了，0.17 会显得太小）。

## 设备分工（用户 2026-08-11 指定，后续一直照此执行）

- **真机 `PB3B4XJGL2090011G` = 用户自己试玩的机器**：只在用户要求时装包/启动，装完**不要 `app stop`**，
  留着给用户用。
- **模拟器 `emulator-5554` = agent 自己验证用**：所有截图、日志、调参验证都在模拟器上做，用完立刻
  `app stop` + 释放锁。用户 2026-08-11 专门问过这件事（"为什么你现在验证完后没有关闭应用，以便空出
  模拟器给其它进程使用"）——**把 stop + release 和 capture 写进同一条命令**，截完就收，别留到下一轮。
  同一天还实测到：锁 TTL 到期后别的会话把股票 App 拉到前台，我截到的是别人的画面。TTL 按本轮实际
  需要给，用完主动 release，不要等它自然过期。

**两台设备同时在线时，每条 `pico-cli` 命令都必须显式 `-d <serial>`**——默认目标是 device list 的第一台
（现在是真机），会出现"锁的是 A、操作的是 B"。设备锁也要带 serial：`"$LOCK" acquire <owner> <ttl> <serial>`。

真机常被**其它项目的会话**占用（实测遇到 `tossar-realdevice-test` 持锁 1200s）。抢不到就报告并让出，
不要死等、不要连续重试——见根目录 `.claude/skills/spatial-design-first-build`。

## 截图验证的两个坑（吃了大亏，务必先读）

1. **连续快速截图会拿到完全相同的旧帧**。间隔 0.25s 连拍 14 张，其中 11 张 md5 完全一致——
   `pico-cli capture screenshot` 在这台模拟器上跟不上，返回的是缓存帧。我一度以为"窗口消失了/烟花没渲染"，
   其实是截图是旧的。**截图间隔至少 1.5-2s，并且用 `md5` 确认帧不重复再下结论。**
2. **缩略图看不见火花**。一颗火花在 2160px 的原图里只有约 10px，图缩到 2000px 显示时基本消失。
   我因此连续几轮误判"没渲染"。**判断视觉效果必须裁剪 + 放大**（`PIL` 已装，见
   `.spatialsdk/ui-verify/crop-*.png` / `best-*.png` 的生成方式：裁城堡上方区域再放大 2-3 倍）。

另外：验证过程中主机上**接入了一台真机 `PB3B4XJGL2090011G`**，设备锁按真机 serial 上了锁，但
`pico-cli app launch` 默认仍打到 `emulator-5554`。**多设备在场时所有 `pico-cli` 命令都要显式 `-d <serial>`**，
否则锁的是一台、操作的是另一台。

## 行为变更（与之前记录的决定相反，需要知会）

`FireworkDirector.ignite()` 现在会**清场重开**，而不是"若已有烟花在飞就忽略"。之前 increment 2 记录的是保留
忽略保护，但改成一场 6-10s 的秀之后，忽略意味着按钮在整场秀期间都像坏了。契约没有规定这一点，这是实现期
的判断，如果用户希望保持"播放中不可打断"，改 `ignite()` 开头即可。

## 环境限制发现（影响后续 Gate B 截图验证，不是本项目代码的问题）

这台机器上的 PICO 模拟器是**多个项目共用的**（同一时间还跑着 `StockQuote`、`TossAR` 等其它项目）。
`pico-cli capture screenshot` 在这台模拟器上拍到的是一个**固定外部房间机位**，不是新启动窗口实际生成的位置——
验证过程中 `SpaceFireworks` 已经通过 logcat 确认成功启动并拿到了 focus（`Watchdog: focus=16(tech.illusion.
spacefireworks)`、`FirstFrameRelease(Displayed)`，用到了 `ModelComponent`/`TransformComponent`/`ViewAttachment`，
无 crash），但两次 `capture screenshot` 拍到的都是同一张 `StockQuote` 的界面（它之前就摆在那个固定机位的取景范围
内，我们的体积窗口大概率生成在了镜头外）。

**后续 Gate B 截图验证如果遇到同样情况**：不要反复重拍指望运气，先用 `pico-cli log` 确认 focus/首帧渲染证据，
截图核对不到的项目老实标"不可判定"，而不是硬说截图证明了什么。如果需要真的把窗口拍进画面，可能要研究这台模拟器
有没有移动/重新摆放窗口位置的办法（`pico-cli device`/`emulator` 相关命令还没查过），这个留给下次需要截图验证时
再解决，不在这次 onboarding 里堵着解决。

## 关键文件

- `.spatialsdk/design-contract.md` — 唯一需求基准（面板清单、元素表、状态清单、文案、不做清单、截图验证计划）
- `.spatialsdk/design-ref/concept-render.html` — 配套效果图
- `.spatialsdk/ui-verify/onboarding-baseline*.png` — onboarding 阶段的截图尝试（拍到的是 StockQuote，见上面的
  环境限制说明，不代表 SpaceFireworks 本身有问题）
- `app/src/main/AndroidManifest.xml` — `LaunchActivity` 的 `pico.spatial.windowcontainer.*` meta-data
- `Main.kt` — app 入口，`DefaultWindowContainer { PicoTheme { HomeVolume() } }`
- `content/HomeVolume.kt` — 当前还是模板示例内容（box.usdz + "Hello, Spatial SDK!" 面板），**待替换**成契约里的
  场景占位 + 发射锚点 + Toolbar

## SpatialUI-only 规则

全部 2D UI 必须用 SpatialUI（`com.pico.spatial.ui.design.*` / `com.pico.spatial.ui.augment.*`），包在 `PicoTheme`
里。禁止 Material/Material3。颜色/字体角色按契约元素表里写的真实 `PicoTheme` 角色名（`labelPrimary`/`labelLarge`
等，已核实存在，不是瞎编的），不要写死色值。

## 自然的下一步（交给 `spatial-app-dev-workflow`，一次实现一个契约里的面板/行为）

1. ~~把 `HomeVolume.kt` 的示例内容换成契约 2.2 节的 `sceneBackdrop` + `fireworkLaunchAnchor`~~ **done**
2. ~~实现最小的 `fireworkBurst`~~ **done**（用 `MeshInstancesResource` 代替 `ParticleComponent`，见上面的核实结果），
   但目前是启动自动点一次火，**还没接按钮**
3. ~~加 `mainToolbar`，把 `重新点火` 接到 `FireworkDirector.ignite()`~~ **done**（保留了"若已有烟花在飞就忽略"
   的保护，作为已确定的行为，不是待定项了）；`切换沉浸` 按钮已经在，onClick 还只是打 log，Stage 增量要接真逻辑
4. 接入随机样式库（5 种：菊花/柳树/环/心形/彩色渐变）+ 一场秀（4-6 发/6-10 秒）编排——注意上面提到的"一个
   `MeshInstancesResource` 一种颜色"限制，多色要多开几个 instances 实体，不是按样式数开
5. 实现 `stage` 切换（`main` 隐藏不销毁 + 场景等比放大 + `stageHud`）+ 退出沉浸回窗口
6. 音效（发射哨声 + 爆炸声）
7. 全部完成后走 `spatial-design-first-build` 的门禁 B（锁内截图验证），注意上面提到的固定机位限制——目前两次
   screenshot 尝试都拍不到窗口内容，正式验证前可能需要先解决"怎么让窗口出现在固定机位画面里"这个问题

## Build / install / run

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
pico-cli app install app/build/outputs/apk/debug/app-debug.apk
pico-cli app launch tech.illusion.spacefireworks --activity .platform.LaunchActivity
```

`JAVA_HOME` 那行**不能省**。这台机器的默认 `java` 现在是 Homebrew 的 **OpenJDK 25.0.2**，
而 Gradle 8.13 内嵌的 Kotlin 解析不了 "25.0.2" 这个版本串，构建会在**配置阶段**就炸，报错信息
只有一行 `* What went wrong: 25.0.2`（真正的异常要 `--stacktrace` 才看得到：
`IllegalArgumentException` @ `intellij.util.lang.JavaVersion.parse`）。看到这个报错不要去查代码，
是 JDK 选错了。

这台机器上模拟器是多项目共用的，装/启/截图/停之间请用 `spatial-design-first-build` 的设备锁
（`.claude/skills/spatial-design-first-build/scripts/device-lock.sh`），别裸跑抢别的会话的设备。
