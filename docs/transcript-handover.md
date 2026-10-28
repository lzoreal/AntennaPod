# AntennaPod 转录（Transcript）功能交接文档

> 面向对象：接手这个 fork 转录功能的下一个 agent / 开发者。
> 基线：换句高亮 250ms 刷新基于 `ffcc05320`（2026-10-25）；转录功能历史基线提交 `7e2b4e5b9`（2026-10-24）；交接文档与真机脚本自 `35cf3c35e`（2026-10-24）起，后续修订以 `git log -- docs/transcript-handover.md` 为准；上游基线提交 `bbe756414`（Merge develop，2026-10-13）。
> 全量单测在代码基线 `7e2b4e5b9` 上已跑通：`./gradlew --console=plain test` → `BUILD SUCCESSFUL`，3666 个任务，0 失败。

## 0. 30 秒上手

| 想做的事 | 命令 / 入口 |
|---|---|
| 改解析 / 分句 | `:parser:transcript`，先看 `VttTranscriptParser` 与 `TranscriptSentenceUtils` |
| 改转录页显示 | `:app` 的 `TranscriptAdapter.getDisplayInfo()` + `TranscriptPageFragment` |
| 改悬浮窗 | `:ui:transcript` 的 `TranscriptFloatingWindowService` |
| 改本地缓存/文件 | `:ui:transcript` 的 `TranscriptUtils` |
| 跑相关单测 | `./gradlew --console=plain :parser:transcript:test :model:test :ui:transcript:test :storage:preferences:test :app:test` |
| 编译 | `./gradlew :app:assembleDebug` |
| 真机验证（自动） | `bash scripts/transcript-tests/run.sh <场景>`（见 §5.2） |

阅读顺序建议：§3 代码地图 → §4 数据/配置约定 → §1 功能总览 → §6 坑速查 → §7 未完成项。

## 1. 功能总览

### 1.1 展示入口

| 能力 | 行为 | 位置 |
|---|---|---|
| 音频播放器转录页 | 播放器内的整屏转录列表，含控制条 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L64)，布局 [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml) |
| 视频/浮层转录弹窗 | `DialogFragment`，复用同一 adapter | [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java#L42)，布局 [transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml) |
| 单行渲染 | 发言人 + 正文，正文用可点击/可选词的 TextView | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L238)、[TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java)、布局 [transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml) |
| 悬浮窗（其他应用之上） | 只显示当前句的双语小窗 | [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L45)，布局 [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) |
| 菜单开关 | `transcript_menu_item`（进入转录页）、`floating_transcript_item`（悬浮窗，checkable）、`transcript_item`（旧入口） | [mediaplayer.xml](file:///D:/AntennaPod/app/src/main/res/menu/mediaplayer.xml#L5) |
| 外部拉起播放器并打开转录页 | `am start ... --ez open_player true --ez open_transcript true` | [MainActivity.java:740-745](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java#L740) |
| 播放服务驱动悬浮窗 | `TranscriptFloatingWindowService.start/stop` | [Media3PlaybackService.java:317](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L317)、[AudioPlayerFragment.java:249](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java#L249) |
| 服务注册（未导出） | `android:exported="false"` | [AndroidManifest.xml:301](file:///D:/AntennaPod/app/src/main/AndroidManifest.xml#L301) |

### 1.2 解析与双语

| 能力 | 行为 | 位置 |
|---|---|---|
| 三种格式 | `TranscriptParser.parse(text, type)` 按 MIME 分发 JSON / VTT / SRT | [TranscriptParser.java:12](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java#L12) |
| VTT 结构 | 忽略 `NOTE`/`STYLE`，解析 cue timing/`<v.speaker>` voice span，跳过非法或零时长 cue | [VttTranscriptParser.java:33](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L33) |
| 双语判定 | 双语 cue 占比过半即 `bilingual`；无双语 cue 时检测“交替单语言 cue”（两种文字体系严格交替，各 ≥2 条，≥4 cue） | `VttTranscriptParser.detectAlternatingPrimaryScript()` |
| 交替配对 | 主语言 cue + 下一条译文 cue 合并为一个 segment（`primary\ntranslation`） | [VttTranscriptParser.java:199](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L199) |
| 长双语 cue 不拆句 | 一条 cue 一个 segment（原文 + 换行 + 译文）；段内句子仍按**字符位置比例**估算时间，供高亮/点击/悬浮窗使用。逐句换行开启时，adapter 在显示层把同一 cue 按句配对交替（EN1/ZH1/EN2/ZH2），译文多则并入最后一行、少则重复最后一句 | [VttTranscriptParser.java:100](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L100)、[TranscriptAdapter.java:363](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L363) |
| 语言切换边界 | 按文字体系（Latin / CJK+假名+韩文 / 其他）判定主/译边界，纯符号行忽略 | [TranscriptSentenceUtils.java:22](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L22) |
| 句子切分 | `。！？…` 直接断句；`.` 需后随空白/引号，且**两侧都是数字时不断**（避免把 `2024.` 当小数）；收尾标点并入前句 | [TranscriptSentenceUtils.java:106](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L106) |
| SRT | 仅看首行做发言人识别；候选名需在文件内重复出现（或为唯一候选标签），否则整行保留为正文；支持纯发言人行、保留额外冒号后文本 | [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) |
| JSON（旧） | `startTime/endTime`（秒→毫秒）+`body`，短段按 5s 合并；null/空 speaker 沿用上一位发言人（首段为空），`end <= start` 丢弃 | [JsonTranscriptParser.java:53](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L53) |
| JSON（TMLT 词级） | 首段含 `start`+`text` 时按 1:1 建段：`start/end` 毫秒、`speaker`、`text`、`words=[[词,起点ms,时长ms]]`；词按文本顺序对齐（大小写不敏感，容错重复词，仅丢该词区间）；无 `end` 的注释段按 15 字/秒估算 0.5–10s | [JsonTranscriptParser.java:131](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L131)、[:172](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L172) |
| 乱序容错 | `Transcript.addSegment` 稳定二分插入，不再因乱序抛异常 | [Transcript.java:13](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java#L13) |
| 重叠 cue | `TranscriptSegment.append` 取最大 endTime | [TranscriptSegment.java:26](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java#L26) |

UI 侧的 `TranscriptTextUtils` 是兼容层：分句/语言边界/句子时间转发到 `TranscriptSentenceUtils`，`progressAt` 为本地实现（保留旧入口，避免 `:app`/`:ui:transcript` 重复实现）。

### 1.3 显示控制

| 能力 | 行为 | 位置 |
|---|---|---|
| 语言模式三态 | 双语 / 仅原文 / 仅译文，循环切换并持久化 | `UserPreferences.TRANSCRIPT_LANGUAGE_MODE_*`（[UserPreferences.java:139](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L139)）、`TranscriptAdapter.setLanguageMode()` |
| 逐句换行 | 开启时同一条 cue 内按句配对交替（EN1/ZH1/EN2/ZH2）；关闭时原文段落 + 译文段落 | `isTranscriptSentencePerLine()`、`TranscriptPageFragment.toggleSentencePerLine()` |
| 排版面板 | 控制条 `Aa` 展开的卡片式面板：字号（正文 `16 + step*2` sp，时间戳同步 `14 + step*2` sp，`A−`/`A+` 圆形步进）、行距（1.0/1.15/1.3×）、左右边距（16/24/32dp）、上下留白（紧凑/标准/宽松，含列表首尾）、数值胶囊与恢复默认 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1301)、[TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L104) |
| 控制条与二级面板 | 工具栏转录按钮开合 `controlsBar`；收起一级控制条（或进入沉浸）时同步收起偏移/排版二级面板，重新打开保持收起 | `toggleControlsBar()`、`hideControlPanels()` |
| 分级高亮（两套，互不叠加） | **句级**（VTT/SRT/无词段）：所有段 `alpha=1`，不再整段半透明；当前句 accent+加粗、已播句 `transcript_text_strong`、未播句 `transcript_text_muted`，交错显示时同一对的主/译行同档；**全列表统一字号**。**词级**（JSON 带 `words`，参考 `apple_podcast_transcript_demo.html`）：不叠加句色/句粗；按播放头三态——已读 `transcript_text_strong`、未读 `transcript_text_muted`、当前词 strong + **假粗体**（不改变行宽、避免逐词重排），**没有背景色块**；译文/时间戳用 muted。当前词所属句子只用于跟随定位，不参与视觉着色。颜色 token 定义在主题属性（Light `#101418/#5A6675`，Dark `#F2F4F7/#9AA6B8`，对 `#F9FCFF/#21272B` 分别为 17.97/5.68 与 13.71/6.13:1；真黑继承 Dark）。整屏轮询词级 100ms、句级 250ms；四种语言/换行显示都由 `TranscriptWordMapper` 映射词区间 | `TranscriptAdapter.applyWordGradedHighlight()`/`FakeBoldSpan`/`applySentenceGradedHighlight()`/`sentenceIndexAtOffset()`、`transcript_text_strong`/`transcript_text_muted`、`TranscriptPageFragment.highlightUpdateInterval()` |
| 跟随音频 | 整屏页 RESUMED 且播放中时以 250ms（词级转录 100ms）轮询位置，高亮与滚动同源，期间忽略 1 秒 `PlaybackPositionEvent`；ticker 只刷新目标，滚动全部由 `OnPreDraw`（每帧布局完成、绘制前）驱动，保证读到的行高/句子位置是最新布局。目标为当前句（主/译成对）中心，阅读线固定在视口高度 **45%**（`TranscriptFollowMotion.ANCHOR_RATIO`）。目标变化时：误差 ≤2dp 停、≤8dp 不起步；`|error| > 0.75 × 视口` 直接 snap；其余按 **320–520ms 三次缓出**（距离越远时长越长）单向趋近 45%，动画中目标变化从当前误差重新计时；系统关闭动画时直接 snap。目标行未布局时按 10dp/帧推进；初始定位/重开/seek/偏移/回到当前句在目标行不可见时 `scrollToPosition` + 下一帧 snap。用户拖动/暂停/离屏时停表，手动拖动后即使高亮句仍在屏幕内也不自动跳回（需点“回到当前句”/点击句子/调偏移恢复）。**首尾暂不做 padding/居中**，边界只滚到可滚范围。居中单位是当前句（主/译成对）；弹窗/悬浮窗仍按 1 秒事件 | `TranscriptPageFragment` 的 `highlightTicker`/`followPreDrawListener`/`scrollTowardsCurrentSentence`/`snapToError`/`areFollowAnimationsEnabled`/`stepTowardsRow`/`scrollToPosition`/`pendingSnapToHighlight`/`userScrolling`/`returnToCurrentSentence()`；策略与单测见 [TranscriptFollowMotion.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotion.java)、[TranscriptFollowMotionTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotionTest.java) |
| 沉浸模式 | 隐藏播放器其他区块，只看转录 | `btnImmersive`、提交 `0792497f4` |
| 回到当前句 | `btnBackToCurrent`，仅在偏离时可见 | [TranscriptPageFragment.java:806](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L806) |
| 文内搜索 | 控制条放大镜打开搜索条：输入即搜（200ms 防抖）、命中背景高亮、当前命中更深、`N/M` 计数、上/下一条循环、关闭清除；只匹配当前显示文本（双语模式主/译都搜，语言模式或逐句开关变化后重算）；定位命中行时暂停自动跟随并显示“回到当前句”，**不改变播放位置**；浮窗与视频弹窗不提供搜索 | `TranscriptPageFragment.setupSearchControls()`([L825](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L825))/`toggleSearchBar()`([L856](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L856))/`runSearch()`([L894](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L894))/`jumpToSearchHit()`([L941](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L941))/`scheduleSearchScroll()`([L969](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L969))、`TranscriptAdapter.setSearchQuery()`([L740](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L740))/`applySearchHighlights()`([L714](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L714))/`getSearchHitBounds()`([L789](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L789))、[TranscriptSearch.java:11](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearch.java#L11) |

### 1.4 时间操作

| 能力 | 行为 | 位置 |
|---|---|---|
| 点击跳转 | 有词时间轴时**点到哪个词就跳到哪个词**（起止取该词）；点在译文、词间或普通转录时回退到**所点句子**的起止区间（非整段），双语时按触摸落在主/译区域分别映射 | [TranscriptAdapter.java:625](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L625)、[:652](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L652) → `SegmentClickListener.onTranscriptClicked(pos, seg, start, end)` |
| 选词 | 长按选中文本触发 `onTranscriptWordSelected`（目前仅暂停播放，便于选词） | [TranscriptPageFragment.java:1075](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1075) |
| 偏移控制条 | 卡片式面板：标题栏「对齐到本句」+ 重置、偏移量胶囊；细调 ±0.5s、粗调 ±5/±10/±30s。**对齐到本句是两步式**：先点按钮进入点选模式（按钮变「点选句子」并弹提示），再点**正在播放的那一句**，即以它为锚（`offset = 播放位置 - 该句起点`，不跳播、不改变播放状态），只影响该句之后的段落；锚在第一句（起点≈0）时覆盖整集开头。点到的句子若正好是当前高亮显示的那句（当前 cue 时间落在该句区间内），不改数据，只提示向上滑到真正在播的那句。**长按按钮 = 把第一句对齐到当前位置并覆盖整集**（清空其他锚点），用于片头整段广告。位置取 MediaController 实时值（暂停后拖动进度条也准）。之所以不直接用“当前高亮句”，是因为片头广告这类大幅错位下高亮句本身就是错的 | `toggleOffsetBar()`、`setupOffsetButtons()`（[L1230](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1230)）、`syncOffsetToCurrentSentence()`（[L1174](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1174)）、`applyOffsetChange()`（[L1198](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1198)） |
| 偏移持久化 | **按 media id + 媒体位置分段**存 JSON map：`{"<mediaId>": [{"p":起始媒体位置,"o":偏移毫秒}, ...]}`。Δ 时用 `offsetAtMediaTime(pos)`，句/段跳转与单段循环用 `offsetAtCueTime(cueStart)`（锚点换算成 `p-o` 的 cue 时间再比较，因此可以跳过片头广告）；± 微调只改当前所在段（无锚点时在 0 处建一个），重置删除该 media 全部锚点。旧的单数值偏好按“0 处锚点”读入，切集/拖动/回退不再需要重新校准 | [TranscriptTimeOffsets.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/TranscriptTimeOffsets.java)、[UserPreferences.java:966](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L966)、[DBWriter.java:216](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java#L216)、[TranscriptPageFragment.java:1152](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1152) |
| 单段循环 | 循环当前 `TranscriptSegment` 区间（按该句的 cue 时间取偏移），偏移变化后若区间无效自动停止 | [TranscriptPageFragment.java:1083](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1083) |

### 1.5 悬浮窗细节

- 只显示当前句（不是全文），主/译两个 `ScrollTextView`（自定义 `ValueAnimator` 横向滚动，最高 300px/s），间距已收紧。
- 调整时间偏移后，悬浮窗立即按新偏移刷新当前句（监听 `prefTranscriptTimeOffsets`）。
- feed 刷新重载转录时保留旧内容直到新转录就绪；重载失败不再清空或退出悬浮窗（仅切集失败/无转录时停止）。
- 播放暂停时保持显示但停止滚动；播放结束、被关闭、当前集无转录时消失。
- 锁定/关闭按钮约 4s 无操作自动隐藏；隐藏时第一次点击只唤出控件，控件可见时点击打开转录页。
- 可拖动并水平吸附到最近左右边缘（纵向只做边界 clamp）；位置以 dp 存偏好（`prefFloatingTranscriptXDp/YDp/Locked`）。
- **不再显示发言人**（按需求移除，`TranscriptSegment` 仍保留 speaker 供转录页使用）。
- 竞态修复：后台 `Callable` 只返回 `FeedMedia`，`currentFeedId`/`lastPlaybackPosition`/`transcript` 一律在主线程回调里赋值，因此 `dispose()` 能真正阻止过期请求污染新剧集状态（[TranscriptFloatingWindowService.java:361](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L361)）。

### 1.6 本地缓存与来源元数据

- 文件三件套：`<localFileUrl>.transcript`、`<...>.transcript.source`（内容 `type\nURL`）、`<...>.transcript.tmp`（原子写入临时文件）。
- 内存缓存：access-order LRU，最多 8 个，key = mediaId + type + URL + 本地文件 mtime（[TranscriptUtils.java:229](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java#L229)）。
- 下载与本地读取均限 5 MB；下载按响应 charset 解码。
- 本地文件只有在 `.source` 与当前条目一致时才使用；无 `.source` 的旧文件会被解析并**收编**（补写 sidecar）。
- 本地文件损坏/不可解析 → 回退到当前 URL 并覆盖本地文件。
- 强制刷新失败 → 依次回退内存缓存、仍然有效的本地文件。
- `storeTranscript` 先校验可解析才落盘，写 `.tmp` 后 rename（失败则删除目标再 rename，再失败则 copy），并写 sidecar。
- 删除媒体 / 发现本地文件缺失 → `FeedMedia.deleteTranscriptFiles()` 清三件套；同时删除该 media 的时间偏移（本地文件缺失路径见 [PlayActionButton.java:50](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/actionbutton/PlayActionButton.java#L50)）。

### 1.7 刷新联动

| 事件 | 触发方 | 谁响应 | 结果 |
|---|---|---|---|
| `PlaybackPositionEvent` | 播放服务（1 秒） | 弹窗/悬浮窗；整屏页仅在轮询未驱动（暂停/停止）时使用 | 高亮、滚动、句子时间；整屏页播放中由轮询独占，避免旧位置回退；词级转录 100ms、普通转录 250ms |
| `FeedItemEvent` | 条目更新 | 转录页 | 仅当转录来源 URL/类型变化或尚无数据时强制重载 |
| `FeedListUpdateEvent` | feed 更新（`contains(long)` 判定） | 转录页、弹窗、`AudioPlayerFragment`、悬浮窗 | 重新加载媒体信息/转录（悬浮窗重载失败时保留旧内容），菜单可见性刷新 |
| `PlayerStatusEvent` | 播放状态 | 转录页、弹窗、悬浮窗 | 跟随/滚动/暂停语义 |

## 2. 提交历史

完整列表：`git log --author=wenhao --oneline`。下表只保留影响当前实现的关键节点。

| 日期 | 节点 | 影响 |
|---|---|---|
| 2026-08-18 ~ 08-19 | `fdddd12ef`/`f70eeacf6`/`92e6fb82f` | 转录页、Adapter/滚动、`:ui:transcript` 模块与悬浮窗；播放器 ViewPager2 由上下滑动改为水平滑动（`audioplayer_fragment.xml`） |
| 2026-08-21 | `3069aa9df`/`0792497f4`/`2b24e787a`/`867088d88` | 双语 VTT、沉浸模式、固定高亮色、幂等加载 |
| 2026-09-11 ~ 10-13 | `3164027b6`/`7f4eaa113`/`bbe756414` | 上游 develop 合并 |
| 2026-10-22 ~ 10-23 | `a77c90ce7`/`8d8f5616f`/`9df9271a4`/`2ff80e94d`/`fa8e29b85` | 共享分句工具、偏移、分级高亮、图标、正确性修复与单测起步 |
| 2026-10-24 | `3128fe4a9` ~ `7e2b4e5b9` | 当前句悬浮窗、双语配对、细粒度偏移、解析/缓存/刷新加固 |
| 2026-10-24 | `35cf3c35e` | 交接文档与真机脚本 |

## 3. 代码地图

### `:model` — 数据模型
| 文件 | 职责 |
|---|---|
| [Transcript.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java) | 段落列表、`addSegment` 有序插入并标记词级(L13)、`findSegmentIndexBefore`(L30)、`hasWordTimings`(L64)、`isBilingual`(L68) |
| [TranscriptSegment.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java) | 单段 start/end/words/speaker + 可选词时间轴，`append`(L26) 合并重叠 cue 并丢弃词时间轴，`getWordIndexAt`(L57) 二分找“最后已开始的词” |
| [TranscriptWord.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptWord.java) | 词级时间轴单元：`startChar/endChar`（相对段文本）+ `startTime/endTime` |
| [FeedItem.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedItem.java) | `podcastIndexTranscriptUrl/Type`，`transient transcript`(L48)，`updateFromOther` 来源变更失效(L181) |
| [FeedMedia.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedMedia.java) | `getTranscriptFileUrl`(L501)、`getTranscriptMetadataFileUrl`(L508)、`deleteTranscriptFiles`(L513)、`setTranscript` |

### `:parser:transcript` — 解析
| 文件 | 职责 |
|---|---|
| [TranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java) | MIME 分发(L12)、`MIN_SPAN` |
| [TranscriptSentenceUtils.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java) | **分句/文字体系/句子时间的唯一实现**：`languageSplitIndex`(L22)、`scriptOf`(L64)、`chunkStarts`(L106)、`sentenceIndexAt`(L137)、`sentenceEndTime`(L148/152)、`sentenceStartTime`、`splitIntoSentences` |
| [VttTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java) | VTT 结构(L33)、双语判定(L83-L94)、`addAlternatingSegments`(L199)、一条 cue 一个 segment(L100)、`detectAlternatingPrimaryScript`(L160) |
| [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) | SRT + 发言人识别 |
| [JsonTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java) | 旧格式 `startTime/endTime/body` 合并；TMLT `start/end/text/words` 1:1 建段与词对齐（L131/L172），词定位 `locateToken`(L204)，无 `end` 估算(L167) |

### `:ui:transcript` — 加载与悬浮窗
| 文件 | 职责 |
|---|---|
| [TranscriptUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java) | `loadTranscriptFromUrl`(L32)、`loadTranscript`(L84)、`loadLocalTranscript`(L136)、`cacheKey`(L229)、`parseTranscript`(L257)、`storeTranscript`(L274)、`.source` 读写与收编 |
| [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java) | `start`(L86)/`stop`(L103)、窗口与拖拽、`loadTranscript`(L357/361)、`onFeedListUpdateEvent`(L416)、`updateTranscript`(L433) + 句子缓存、`hideTranscript`(L555) |
| [TranscriptTextUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptTextUtils.java) | 兼容层，分句/语言边界/句子时间转发 `TranscriptSentenceUtils`；`progressAt` 为本地实现 |
| [ScrollTextView.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/ScrollTextView.java) | 悬浮窗用自动横向滚动 TextView（`ValueAnimator`，最高 300px/s） |
| [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) | `tv_floating_transcript`、`tv_floating_transcript_translation`、`btn_floating_lock`、`btn_floating_close` |

### `:app` — 页面与交互
| 文件 | 职责 |
|---|---|
| [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java) | 转录页全部逻辑（加载 L356/L360、事件 L494/L535、滚动 L749、点击 L782、搜索 L828、循环 L1126、偏移 L1152-L1246、排版 L1299、字号 L1428、语言/换行 L1415）；词级转录轮询 `highlightUpdateInterval()`(L159) |
| [TranscriptFollowMotion.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotion.java) | 跟随策略纯函数：45% 锚点、8dp 起步/2dp 停、0.75 视口 snap、320–520ms 三次缓出、距离→时长；单测 [TranscriptFollowMotionTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotionTest.java) |
| [TranscriptSearch.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearch.java) | 搜索匹配纯函数：大小写不敏感、CJK 子串、非重叠多次命中；单测 [TranscriptSearchTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearchTest.java) |
| [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java) | `onCreateViewHolder`(L170 绑定监听器)、`getDisplayInfo`(L315 显示缓存/词映射开关)、`buildInterleavedInfo`(L385 逐句交替)、`applyLayout`(L108 排版/时间戳字号)、`getSentenceBounds`(L650 居中)、行高亮(L770)、主题 token 解析(L87)、`bindText`(L295 词级/句级分流)、句级 `applySentenceGradedHighlight`(L475)、词级 `applyWordGradedHighlight`(L501)/`wordEndTime`(L532)/`FakeBoldSpan`(L546)、词级点击、句随词走 `sentenceIndexAtOffset`(L619)、搜索(L821)、`SegmentClickListener` |
| [TranscriptWordMapper.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapper.java) | 纯函数：把段内词区间映射到显示文本（逐句换行/交错双语用 `ranges`，按句索引起搜；大小写不敏感、词边界校验）；单测 [TranscriptWordMapperTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapperTest.java) |
| [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java) | 视频弹窗：`onTranscriptClicked`(L106)、`onFeedListUpdateEvent`(L153)、`loadMediaInfo`(L173/L177)、`scrollToPosition` |
| [TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java) | 点击 + 选词 TextView（词级态由 Adapter 的 Span 完成，TextView 不再自绘背景） |
| [AudioPlayerFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java) | 悬浮窗开关(L249/L251)、菜单可见性、`FeedListUpdateEvent` 重载 |
| [PlayActionButton.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/actionbutton/PlayActionButton.java) | 本地文件缺失时一并清理转录文件与偏移 |
| 布局 | [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml)（`recyclerView`/`controlsBar`/`searchBar`/`searchInput`/`txtSearchCount`/`btnSearch`/`btnSearchPrev`/`btnSearchNext`/`btnSearchClose`/`offsetControls`/`layoutControls`/`btnBackToCurrent`/`btnImmersive`/`btnLineBreakToggle`/`btnTranslationToggle`/`btnLayout`/`btn{FontSize,LineSpacing,SideMargins,VerticalSpacing}{Down,Up}`/`txt{FontSize,LineSpacing,SideMargins,VerticalSpacing}Value`/`btnLayoutReset`/`btnSegmentLoop`/`btnOffsetToggle`/`btnOffsetSync`/`txtOffsetValue`/`emptyView`/`progLoading`）、[transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml)（`transcript_list`/`followAudioCheckbox`）、[transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml)（`speaker`/`content`） |
| 图标 | `app/src/main/res/drawable/`：`ic_transcript_*`、`ic_fullscreen*.xml`、`bg_transcript_offset_button.xml`；`ui/transcript/src/main/res/drawable/`：`bg_floating_control.xml`、`ic_transcript_{close,lock,unlock}.xml`；搜索/关闭复用 `ui/common` 的 `ic_search`、`ic_close` |

### 其他
| 文件 | 职责 |
|---|---|
| [UserPreferences.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java) | 所有转录偏好（L129-L154 keys，L947-L1108 getters/setters）；偏移按锚点数组读写（L966-L1085） |
| [TranscriptTimeOffsets.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/TranscriptTimeOffsets.java) | 纯逻辑：偏移锚点表，`offsetAtMediaTime`/`offsetAtCueTime`、`setAnchor`（同位置 1s 内替换且保留更早起点）、`setOffsetForSegment`、按双时间轴 `normalize` |
| [DBWriter.java](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java) | 删除媒体/条目时清理转录文件与偏移(L121/L133/L217) |
| [FeedListUpdateEvent.java](file:///D:/AntennaPod/event/src/main/java/de/danoeh/antennapod/event/FeedListUpdateEvent.java) | `contains(Feed)` / `contains(long)` |
| [strings.xml](file:///D:/AntennaPod/ui/i18n/src/main/res/values/strings.xml)、[strings.xml (zh-rCN)](file:///D:/AntennaPod/ui/i18n/src/main/res/values-zh-rCN/strings.xml) | `transcript_*`（含 `transcript_search_*`）、`pref_floating_transcript_*`、`show_transcript`、`no_transcript_label`；英文与简体中文同步维护 |
| DB 列 | `FeedItems.podcastindex_transcript_url`、`FeedItems.podcastindex_transcript_type`（**不在 FeedMedia**） |

## 4. 偏好键与默认值

| key | 类型/默认 | 含义 |
|---|---|---|
| `prefFloatingTranscript` | bool/false | 悬浮窗开关 |
| `prefFloatingTranscriptXDp/YDp` | int/-1 | 悬浮窗位置 |
| `prefFloatingTranscriptLocked` | bool/false | 是否锁定（禁拖） |
| `prefTranscriptLanguageMode` | int/0 | 0 双语、1 仅原文、2 仅译文 |
| `prefTranscriptSentencePerLine` | bool/true | 逐句成行 |
| `prefTranscriptFontSize` | int/0 | 字号步进（clamp -2..3） |
| `prefTranscriptLayoutLineSpacing` | int/0 | 行距档位 0=1.0×、1=1.15×、2=1.3× |
| `prefTranscriptLayoutSideMargins` | int/1 | 左右边距 16/24/32dp |
| `prefTranscriptLayoutVerticalSpacing` | int/1 | 上下留白 4/8/16dp（含列表首尾 padding） |
| `prefTranscriptTimeOffsets` | string JSON | mediaId → 锚点数组 `[{"p":媒体位置ms,"o":偏移ms}]`；实现在旧格式（mediaId → 单个毫秒数）上按“0 处锚点”兼容读取 |
| `prefTranscriptTimeOffset` + `prefTranscriptTimeOffsetMedia` | long | 旧单槽值，读取时迁移到上表 |

## 5. 测试

### 5.1 单元测试

按 `AGENTS.md`：`./gradlew --console=plain` + 模块的 `:test` 任务。

| 模块 | 测试文件 | 覆盖点 |
|---|---|---|
| `:model` | [TranscriptTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptTest.java)、[TranscriptSegmentTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptSegmentTest.java)、[FeedItemTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedItemTest.java)、[FeedMediaTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedMediaTest.java) | 乱序插入、append 单调且清空词时间轴、词级 `getWordIndexAt`、来源变更失效、序列化忽略 transcript、三文件清理 |
| `:parser:transcript` | [VttTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParserTest.java)、[SrtTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParserTest.java)、[JsonTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParserTest.java) | 双语保留译文、非双语不误判、长 cue 不拆句（含数字句末）、null speaker、非法时间、乱序；TMLT 1:1 建段与词字符区间、重复词只丢该词、空 `end` 估算、旧格式无词时间轴 |
| `:ui:transcript` | [TranscriptUtilsTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptUtilsTest.java)、[TranscriptFloatingWindowServiceTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowServiceTest.java) | sidecar 写入/收编/过期、5MB、损坏回退 URL、强刷回退、原子写、悬浮窗过期请求竞态（可控调度队列 + `RxJavaPlugins.reset()`） |
| `:storage:preferences` | [UserPreferencesTest](file:///D:/AntennaPod/storage/preferences/src/test/java/de/danoeh/antennapod/storage/preferences/UserPreferencesTest.java) | 按媒体偏移、删除、旧值迁移；分段锚点从锚点位置起生效、取最近锚点、按 cue 时间查询可跳过片头广告、微调只改当前段、同位置替换保留更早起点、重置清空全部锚点 |
| `:app` | [TranscriptTextUtilsTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextUtilsTest.java)、[TranscriptWordMapperTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapperTest.java) | 数字句末 chunkStarts、sentenceEndTime；词区间在逐句换行/交错双语中的映射、词内子串不误匹配 |

注意：`TranscriptFloatingWindowServiceTest` 里 Mockito 静态 mock 只对创建它的线程可见，跨 IO 线程的竞态必须用同一可控调度器（`Schedulers.from(executor)` + `trampoline`）复现，否则测试会假通过。

### 5.2 真机自动验证脚本

[scripts/transcript-tests/](file:///D:/AntennaPod/scripts/transcript-tests/)（Git Bash，仓库根目录运行）：共用底座 `lib.sh` + 单功能场景 + `run.sh`。
`scripts/transcript_device_test.sh` 保留为兼容入口，等价于 `run.sh all`。

单功能场景（推荐：只改到哪个功能就跑哪个）：

```bash
bash scripts/transcript-tests/run.sh --list      # 列出场景
bash scripts/transcript-tests/run.sh layout      # 只跑排版面板
bash scripts/transcript-tests/run.sh search      # 只跑转录内搜索
bash scripts/transcript-tests/run.sh smoke layout offset
bash scripts/transcript-tests/run.sh word --media-id 8878 --position 5000  # 844 已下载音频
bash scripts/transcript-tests/run.sh word --local-audio    # 无本地音频时用静音 MP3 驱动
bash scripts/transcript-tests/scenarios/30_layout_panel.sh   # 单场景脚本，自带备份/恢复
```

| 场景 | 文件 | 断言 |
|---|---|---|
| smoke | `scenarios/00_smoke.sh` | 转录页打开、无崩溃、双语行、本地下载 |
| highlight | `scenarios/10_highlight_follow.sh` | 一条 cue 一行（可见行数 ≤3）、首行双语、高亮截图 |
| layout | `scenarios/30_layout_panel.sh` | Aa 面板四行齐全、默认 1×/24dp/标准、调节到 1.15×/32dp、重置、收起一级控制条后二级面板同步隐藏 |
| offset | `scenarios/40_offset_loop.sh` | 重置为 0、+30 秒、对齐到本句（进入点选模式 → 点选句 → 偏好写成锚点数组 → 分段内 +30 只改当前段）、长按对齐第一句（只保留 0 处锚点）、再重置并确认锚点已清空 |
| floating | `scenarios/50_floating_window.sh` | 悬浮窗菜单开关、服务状态、截图 |
| search | `scenarios/60_search.sh` | 打开搜索条、输入 `the` 得到 `1/N`、下一条变 `2/N`、追加 `zzzz` 显示无匹配、关闭后输入框隐藏；截图人工确认黄色命中高亮 |
| word | `scenarios/70_word_highlight.sh` | 需 `--vtt-file` 为词级 JSON（默认 `D:/TMLT/docs/timings/844.json`，MIME 自动为 `application/json`）：JSON 已解析并显示、可见文本含 844 关键词、日志有下载、无崩溃；用 MediaSession 断言播放位置持续前进；两张间隔截图人工确认当前词加粗、已读/未读词态。推荐用已下载的 844 音频（FeedMedia.id=8878），无本地音频时加 `--local-audio`（静音 MP3 驱动） |

一次运行（`run.sh all` 或兼容入口）只做一次备份/安装/启动，然后按顺序跑选中场景；单独跑场景脚本会自己完成整套准备与恢复。三档句色、当前句居中这类像素效果仍以截图人工确认为准。

脚本按「备份设备 → 注入测试 DB/偏好 → 本地 HTTP + `adb reverse` 提供 VTT → 重启并断言 → 还原设备」执行；任一步失败或 `INT`/`TERM` 都会先恢复设备。参数见 `run.sh --help` 或任一场景脚本的 `--help`。

自动断言：转录页存在双语逐句行、无 `FATAL EXCEPTION`、悬浮窗服务在运行；截图小于 60 KB 直接判黑屏失败，不产出假 PASS。MIUI 唤醒/AOD 的成因与处理见 §6。

产物：`~/.qoder-cn/tmp/transcript-ondevice/run-<时间戳>/`（`results.txt`、`01_transcript_page.png`、`04_floating.png`、`transcript.log`、各阶段 UI dump 与数据库副本）。

其他用法：`--restore-only --work <目录>`（只恢复）、`--keep`（保留现场）、`--serial <序列号>`（多设备）。

2026-10-25 在代码基线 `7e2b4e5b9`（脚本提交 `d66169281`）上、Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）的完整跑通结果（含 `:app:installPlayDebug`）：14/14 PASS，`restored integrity: ok`、`test urls left: 0`、偏好与数据库与备份逐字节一致、`adb reverse` 已清空。截图里可见 `1._Greatness.vtt` 第 1 个 cue（00:00:00–00:00:49）拆出的逐句双语行、当前句蓝色高亮，以及悬浮窗对同一句的滚动显示；另有 `--skip-install` 一轮 13/13 PASS。

> 注意：上述结果使用的是脚本提交 `d66169281`。其后 `ffcc05320` 又修改了 `transcript_device_test.sh`（修复假 PASS），因此该结果不能直接用 HEAD 的脚本复现。

2026-10-25 在“换句高亮 250ms 刷新”的未提交改动（基于 `ffcc05320`，脚本提交 `d66169281`，含临时时延日志）上完整跑通 32/32 PASS，偏好与数据库按字节还原。临时日志测得 250ms 轮询实际约 4.1 次/秒，句边界→高亮时延 73/65/148/153/162/170/−15 ms，均 ≤250ms。**测试注意**：设备偏好若给该 media 存了时间偏移（`prefTranscriptTimeOffsets`，例如 `{"1":103500}`），高亮会长时间停在第一段，需先清零或换 media id。

2026-10-25 在“一条 cue 一行 + 整段高亮”的未提交改动上完整跑通 32/32 PASS（可见行数 1：整条 cue 即一个高亮块），偏好与数据库按字节还原。截图确认第一条 cue（00:00:00–00:00:49）整块 19sp 高亮，块内当前句全色、其余句统一半透明。

2026-10-25 排版面板在父基线 `ba981d1ff` 的未提交工作树上完成真机验证：`transcript_device_test.sh` 14/14 PASS；手动验证 `Aa` 面板四项调节、恢复默认、强制停止并重启后 1.15×/32dp 保留；验证后设备已按备份恢复（`restored integrity: ok`）。

2026-10-25 合并 `transcript-highlight-cadence` 后的工作树（父基线 `ae6334d19`）真机跑通 13/14：唯一失败是脚本在 UI dump 里找不到溢出菜单的悬浮窗项（弹窗 dump 抖动，见 §6），手动点击该项后悬浮窗服务正常启动；截图确认第一条 cue 整块高亮、块内当前句全色、其余统一半透明。

2026-10-25 显示层逐句交替 + 段内三档句色在同一工作树上手动真机验证：同一条 cue 内可见已播（`0xA6`）、当前（全色加粗）、未播（`0x8C`）三档，主/译按句交替；脚本仍因上述弹窗 dump 抖动停在 13/14。

2026-10-25 逐帧跟随在同一工作树上真机验证（临时日志，验证后已移除）：句切换后误差 229px，按 33→29→24→20→…→1px 约 400ms 收敛后停表，无反复起滚；设备偏好上残留的 `{"1":103500}` 偏移会把高亮钉在第一段，测试前需先归零。

2026-10-25 “单驱动跟随 + 离屏瞬跳”在提交 `203879022`（父基线 `7037be9ae`，脚本提交 `7037be9ae`）上真机验证（Redmi 2407FRK8EC，本地 VTT + 本地静音音频驱动真实播放）：`run.sh --skip-install --media-id 7286 --position 5000 smoke highlight` 14/14 PASS，数据库与偏好按字节还原。专项时序（临时日志，验证后已移除）：冷启动落在长 cue 内 40s 处定位 **79ms** 完成（旧实现同场景 1885px/2161ms 缓动）；后台 45s 后回前台、进度跨 2 个 cue 时 **24ms** 瞬跳完成（旧实现 1379px/12647ms，之后再补 845px 跟随）；cue 边界（50→51）由同一条逐帧跟随在 **183ms** 内收敛 411px（旧实现 605px/5549ms 缓动后仍需跟随修正）。

2026-10-25 用户录制 715 真实英语长 cue 语料（`715._King_Arthur_The_Mysteries_of_Merlin_Part_3.vtt`）的整段交界抖动定位与修复：临时日志确认根因是换段时高亮字号 19sp↔16sp 的重排（旧段行高 1365→约 955px、新段 478→634px），RecyclerView 的重测晚于文本重绑，跟随器在旧布局上先下 33px、再反向 198px，录屏出现空白/重影。最终修复：**全列表统一字号**（去掉高亮 19sp）+ 跟随改到 `OnPreDraw`（每帧布局完成后读取最终行高/句子位置）+ 时间戳随 Aa 档位同步（`14 + step*2`）。同语料复录：跨段 `itemH` 保持 478 不变，误差 193→2 单向收敛，帧内无空白/重影、无反向。按 AGENTS「只跑改动覆盖的场景」本次只复跑该 715 交界专项，未重跑其他场景；时间戳字号缩放未单独截图。

2026-10-25 三项交互调整（提交 `203879022`）：① 收起一级控制条或进入沉浸时同步收起偏移/排版二级面板（`hideControlPanels()`）；② 跟随加迟滞与时间基速度：偏离 ≥64dp 才起步、≤2dp 停（用户实测后手调为 64dp），时间常数 140ms、上限 500dp/s（每帧按 `1-exp(-dt/τ)` 衰减并按 dt 限速），短距离先积累不动；③ 取消手动拖动后“高亮句仍在屏幕内就自动跟回”的设定（移除 `resumeFollowingIfHighlightVisible`），改由“回到当前句”/点击句子/调偏移显式恢复。已编译并安装；本轮未跑设备脚本（速度手感主观，待人工确认）。

2026-10-25 代码审计修复（代码基线 `203879022` + 未提交工作树；脚本提交 `203879022` + 未提交断言/文档）在 Redmi 2407FRK8EC 上全量跑通：`run.sh smoke highlight layout offset floating --media-id 7286 --position 5000` **100 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`，捕获日志无 `FATAL EXCEPTION`；截图确认逐句双语行（EN/ZH 交替）与悬浮窗。修复内容：删除下载不再清偏移、页面/弹窗销毁时 `setAdapter(null)` 解除 EventBus 注册、点句坐标扣除 padding、悬浮窗刷新失败保留旧内容、SRT/JSON 坏 cue 不再污染段尾、VTT 交替双语容忍纯符号/连续同语种 cue、SRT 一次性冒号标签不再当发言人。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261025-183932/`。

注意：`run.sh` 省略 `--media-id` 时按“播放进度最大”推断媒体（本次为 1），而播放器实际恢复 `lastPlayedFeedMediaId`（7286），页面显示 7286 的旧转录，双语断言会误报 FAIL；测播放中的剧集必须显式传 `--media-id`。

2026-10-25 性能优化（工作树，基于 `81cb1171a`）真机回归：`run.sh smoke highlight layout offset floating --media-id 7286 --position 5000` **100 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`，无 `FATAL EXCEPTION`；截图确认字号、时间戳与双语行渲染不变。优化内容：偏移读取按偏好串缓存（不再每 tick 解析 JSON）、Adapter bind 仅在值变化时 requestLayout、悬浮窗缓存 `languageSplitIndex`、VTT 每个 cue 只计算一次文字体系、应用在内存紧张时清空转录内存缓存。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261025-185758/`。

2026-10-25 方案 A MVP（固定 45% 阅读线 + 离散缓动 + 距离 snap；首尾暂不居中）在父基线 `d85105ca2` 的未提交工作树上真机验证（脚本未改，基线 `d85105ca2`；Redmi 2407FRK8EC `4LSGLZEYOFCQBIQC`，本地 VTT + 真实播放）：`run.sh smoke highlight layout offset --media-id 1 --position 5000` **47 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`、`adb reverse` 已清空。`--keep` 现场人工确认：换句/跨 cue 时视口单向趋近 45% 阅读线，无反向修正；手动拖动后高亮句仍在屏幕内也不自动跳回，“回到当前句”可 snap 回位；暂停 2s 播放位置不变，恢复后继续；无 `FATAL EXCEPTION`。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261025-223514`（自动场景，`results.txt`）与 `run-20261025-223741`（`--keep` 手动截图/录屏，已用 `--restore-only` 恢复）。**MVP 限制**：未做首尾 padding，首/末段不能居中，见 §5.4 与 §7。

2026-10-25 用户反馈当前句移动过快；将 `TranscriptFollowMotion` 的补间时长从 220–300ms 调整为 **320–520ms**（`260 + 2.5 × dp`，最短 320、最长 520），锚点、阈值、三次缓出均未改。属主观手感调整，未重跑设备脚本；`TranscriptFollowMotionTest` 已同步更新并通过，编译通过，已安装到设备待人工确认。

2026-10-26 文内搜索（代码父基线 `d85105ca2` + 未提交工作树；脚本未提交）在 Redmi 2407FRK8EC 上跑通：`run.sh search --media-id 1 --position 5000` **20 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`、偏好与数据库按字节还原。场景断言：打开搜索条、输入 `the` 显示 `1/560`、下一条变 `2/560`、追加 `zzzz` 显示“无匹配”、关闭后输入框隐藏。截图确认：搜索条（输入 + 计数 + ↑/↓ + 关闭）、`the` 的黄色背景命中高亮、命中行滚动到 45% 附近、跟随暂停后显示“回到当前句”、音频高亮与搜索高亮共存。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261026-003045/`（`results.txt`、`search_05_results.png`、`search_07_next.png`）。
注意：该设备 media 1 有旧的本地 `.transcript` 缓存（无 `.source` 时会被收编），注入了 `1._Greatness.vtt` 但页面仍显示旧转录，所以场景用通用词 `the`，避免依赖注入语料的具体内容；见 §6。

2026-10-28 词级高亮（代码父基线 `1253fdefd` + 未提交工作树；脚本同工作树未提交）：单测 `:model:test`、`:parser:transcript:test`、`TranscriptWordMapperTest` 全部通过；`./gradlew :app:assembleDebug` 通过。用真实 `844.json` 做了临时整文件解析（测试后已删除，未入库）：得到 **317 段、8831 个词时间轴**，与 sidecar 完全一致；全部 39 集模拟对齐 357964 词仅丢 5 个重复词。

2026-10-28 真机（Redmi 2407FRK8EC `4LSGLZEYOFCQBIQC`，844 音频已下载为 FeedMedia.id=8878）：`bash scripts/transcript-tests/run.sh word --skip-install --media-id 8878 --position 5000` **18 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`，8878 的转录 URL/type/position 与本地 VTT 缓存侧车均还原，日志无 `FATAL EXCEPTION`。截图 `~/.qoder-cn/tmp/transcript-ondevice/run-20261028-082151/word_01.png`（40s，当前词 “and” 高亮；当时为胶囊底色方案）与 `word_02.png`（42s，当前词 “biased,” 高亮）确认高亮随真实播放移动；自动断言用 `dumpsys media_session` 读到 38s → 41s（播放中持续前进）。**注意**：`uiautomator dump` 在持续播放/滚动时会返回旧帧，不能用来判断位置；本场景已改读 MediaSession。
本轮测试底座新增：播放偏好注入（`currentlyPlayingMedia`/`lastPlayedFeedMediaId`/`lastPlayedFeedId`，否则 `-1` 会锁住底栏导致 `--ez open_player` 无效）、本地转录缓存三件套备份/恢复、`--local-audio`（生成并推送 3 分钟静音 MP3，供无本地音频时驱动播放）。

2026-10-28 词级高亮按 `apple_podcast_transcript_demo.html` 重做（同一工作树，代码父基线 `1253fdefd`；脚本未提交）：当前词**不再加背景**，改为全色 + 假粗体；已读词全不透明、未读词 `0x94`；词级转录完全不叠加句级三档/句粗，VTT/SRT/无词段仍走原句级路径。真机 `run.sh word --skip-install --media-id 8878 --position 5000` **18 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`、本地 VTT 缓存按 sha256 还原；产物 `~/.qoder-cn/tmp/transcript-ondevice/run-20261028-090820/`，截图 55s 当前词 “few”、59s “end,”，可看到已读黑、未读灰、当前词加粗且无背景。

2026-10-28 配色 token（`ui-ux-pro-max` 指导，同一工作树）：新增主题属性 `transcript_text_strong` / `transcript_text_muted`（Light `#101418`/`#5A6675`，Dark `#F2F4F7`/`#9AA6B8`，真黑继承 Dark）。词级当前/已读 strong、未读 muted；句级当前句 accent、已播 strong、未播 muted，非当前段不再 45% 半透明。对浅/深背景对比度分别为 **17.97/5.68** 与 **13.71/6.13:1**，句级已播/未播从 2.2–2.7:1 提升到 ≥4.5:1。真机词级 **19 PASS / 0 FAIL**（`run-20261028-093027`），VTT 句级 **19 PASS / 0 FAIL**（`run-20261028-094051`），设备均还原。顺带修复：“仅译文”偏好下英文单语 JSON 被误判为译文而跳过词映射；测试现在固定“双语 + 逐句成行”，避免设备残留偏好干扰。

2026-10-28 VTT 回归（词级/句级分流后，同一工作树）：`run.sh smoke highlight --skip-install --media-id 8878 --position 5000` **18 PASS / 0 FAIL**，双语逐句行、一条 cue 一行、句级三档高亮截图均通过，产物 `~/.qoder-cn/tmp/transcript-ondevice/run-20261028-091115/`（分流前的同项结果见 `run-20261028-085437`）。测试底座顺手修正三处跨轮串帧：`dump_ui` 每次先删 `/sdcard/window_dump.xml` 并重试；VTT 场景先暂停再取稳定 dump；备份后把本地转录缓存移走，强制本次从注入 URL 重新下载，teardown 再按 sha256 原样写回。

2026-10-29 分段偏移 + 「对齐到本句」（代码基线 `4be375c42` + 未提交工作树；脚本同工作树）：单测 `./gradlew --console=plain :storage:preferences:test` **15/15**（新增：锚点从位置起生效、cue 时间查表可跳过片头广告、微调只改当前段、同位置替换保留更早起点、首句锚定覆盖整集开头、编辑夹紧不越过相邻段、重置清空、旧值迁移），`:app:test` 与 `./gradlew :app:assembleDebug` 通过。真机 Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）`bash scripts/transcript-tests/run.sh offset --media-id 1 --position 5000`：**20 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`。断言链：重置 0 → +30 显示 +30 → 「对齐到本句」胶囊 +5 且 `prefTranscriptTimeOffsets` 写成锚点数组 → 段内 +30 变 +35（只改当前段）→ 重置后锚点清空。截图 `offset_07.png` 确认标题栏「对齐到本句」+「重置」与胶囊排版正常。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261029-060434/`。
本轮首跑失败过一次：测试运行期间编辑了 `run.sh`，bash 按字节偏移续读脚本，重复执行初始化并把 `BACKUP_OK` 重置，导致 teardown 跳过恢复、设备被留脏（媒体 1 的转录 URL 指向 `127.0.0.1:8765`）。已把该 URL 按 10-28 干净备份改回 `https://lzoreal.github.io/TMLT/transcripts/zh/899.vtt`，并核对 `integrity_check=ok`、测试 URL 0 条、偏好与备份逐字节一致；`adb reverse tcp:8765` 为运行前既有，未改动。见 §6 最后一行。

2026-10-29 两步式点选对齐 + 长按整集对齐 + 实时播放位置（代码基线 `1da04a427` + 未提交工作树；脚本同工作树）：单测 `:storage:preferences:test` **16/16**（新增 `testStartOffsetReplacesEveryAnchor`），`./gradlew :app:assembleDebug` 通过。真机 Redmi 2407FRK8EC `run.sh offset --media-id 1 --position 5000`：**21 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`。断言链：重置 → +30 → 进点选模式 → 点“已显示的那句”被拦下（偏移不变、仍在点选模式）→ 段内 +30 只改当前段（+30 → +60）→ 长按按第一句对齐整集（胶囊 +70.1、只剩 0 处锚点）→ 重置清空。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261029-065626/`。此前设备上的 `{p:2959,o:1919},{p:17797,o:6037},{p:114307,o:-23}` 是旧单点版留下的实验锚点，长按一次即可覆盖。

### 5.3 语料基线（人工比对用）

真实双语语料：`D:\MyTranscript\site\the-rest-is-history\transcripts\zh\`（远端镜像 `https://lzoreal.github.io/TMLT/transcripts/zh/`）。
`1._Greatness.vtt` 共 62 个 cue，每个 cue 两行（拉丁英文 + CJK 中文）；一条 cue 一行后应得到 **62** 个 segment（逐句拆行时代的 316 行基线已废弃）。合成 fixture 不足以证明双语渲染正确，需用该语料在真机核对。

词级语料：`D:\TMLT\docs\timings\<集号>.json`（与 `D:\TMLT\docs\transcripts\<集号>.vtt` 同源的 sidecar）。`844.json` 有 317 段、8831 个词；全部 39 集共 11954 段、357964 个词，其中 826 段是无词注释（`[INAUDIBLE]` 等，`end=null`），解析对齐后仅 5 个重复词被丢弃（0.001%，见 §7）。844 首段 `start=880ms`、首个词 `From@1120ms`，可直接用 `word` 场景验证首屏词级高亮。

### 5.4 手工验收清单

- [ ] 转录页一条 cue 一行、整段同字号高亮（颜色/加粗/透明度）；时间戳字号随 Aa 档位同步；段内三档（已播/当前/未播）；逐句换行开启时主/译按句交替
- [ ] 词级 JSON：当前词全色+加粗、无背景，已读 strong、未读 muted，随音频逐词移动（100ms 刷新）；点击词从该词起播；仅原文/逐句换行/双语交替下词态不跑偏；无词注释段与无词时间轴的转录回退句级高亮/点击
- [ ] 颜色：Light/Dark/TrueBlack 分别截图；strong/muted 对比度 ≥4.5:1；句级当前/已播/未播三档可区分且未读不低到不可读；不靠颜色单独表达状态（当前词同时加粗）
- [ ] 语言三态循环（双语 / 仅原文 / 仅译文）与逐句换行开关
- [ ] 字号放大/缩小后高亮行同步
- [ ] 排版面板：字号/行距/左右边距/上下留白各档生效，“恢复默认”回到标准，强制停止后重启仍保留
- [ ] 点击句子从该句起点播到该句终点；单段循环可开关
- [ ] 文内搜索：放大镜打开搜索条，输入主/译文本有命中背景高亮与 `N/M` 计数；上/下一条定位到命中行并暂停跟随；无匹配显示提示；关闭清除高亮
- [ ] 偏移粗调/细调/重置，切集后再切回仍保留；删除单集后偏移消失
- [ ] 分段偏移：片头广告（如 0–100s）在真实内容处**长按**「对齐到本句」，第一句立即对上且不跳播；中间广告后点「对齐到本句」→ 点正在播放的那一句，往回拖动到广告前段落时高亮仍正确；点错（点到已显示的那句）有提示且不改数据；段内 ± 只改当前段，重置一次清空
- [ ] 手动滚动暂停跟随，且高亮句仍在屏幕内也不自动跳回；“回到当前句”恢复；跟随滚动把当前句（主/译成对）放在视口 45% 阅读线；**首尾暂不保证居中**（MVP，边界只滚到可滚范围）
- [ ] 收起一级控制条或进入沉浸时，偏移/排版二级面板一并收起
- [ ] 换句高亮在句边界约 250ms 内切换，无整行淡入淡出；相邻句与换段滚动为 320–520ms 单向三次缓出（距离越远时长越长），远跳/离屏跨段瞬时到位；系统关闭动画时直接跳位
- [ ] feed 刷新后（转录 URL 变化）页面/弹窗/悬浮窗/菜单自动更新
- [ ] 悬浮窗只显示当前句、双语间距、暂停不滚动、锁定/关闭 4s 自动隐藏、拖动水平吸附、点击回转录页
- [ ] 断网 + 已有本地文件时可离线显示；本地文件损坏时能重新下载

## 6. 已知坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `database disk image is malformed` | 用 `adb shell ... > databases/Antennapod.db` 写库 | 用 `adb exec-in run-as ... sh -c "cat > ..."` 并回读 `integrity_check` |
| `cat: C:/Program: No such file...` | Git Bash 转换了 `/sdcard/...` | 命令前加 `MSYS_NO_PATHCONV=1` |
| 启动后停在首页 | `-e open_player true` 是字符串 extra | 用 `--ez` |
| 传了 `--ez` 仍停在首页 | 同时传 `episode_id` 会优先走单集页 | 打开播放器时不要传 |
| 冷启动时 `--ez` 不生效 | 播放器还没 attach，底部抽屉处于锁定态，`setState(EXPANDED)` 被忽略 | 等首启完成后再发一次同一 intent，走 `onNewIntent` |
| `am start` 无反应、UI dump 里只有 `com.android.systemui`/`com.miui.aod`、`input tap` 全部丢失 | 设备息屏/Dozing（`mWakefulness=Dozing`）或停在 MIUI AOD 表盘（`mWakefulness=Awake` 但屏幕没 App），非 App 问题；反复重发唤醒手势会打断亮屏动画 | `KEYCODE_WAKEUP` + `wm dismiss-keyguard` + 一次上滑（仅在 App 未聚焦时）；有 PIN/图案锁时脚本无法解锁，需人工解锁 |
| 断言全 PASS 但截图是一片黑 | 息屏状态下 `screencap` 仍成功，只是帧内容为黑；UI dump 与截图是两条独立证据 | 看 PNG 字节数（全黑约 19 KB、AOD 表盘约 30 KB）；脚本已用 `capture_screen` 判 FAIL，人工解锁后重跑 |
| `reverse: inaccessible or not found` | `adb reverse` 是主机侧命令，被塞进了 `adb shell` | 直接 `adb reverse`，不要经 `adb shell` |
| `Can't start from invalid source` | 悬浮窗服务 `exported=false`，外部 `adb`/其它应用不能直接启动 | 在播放器溢出菜单开启（需悬浮窗权限）；开启后由播放服务按需自动 start/stop |
| `no such column: transcript_url` | 列在 `FeedItems` 不在 `FeedMedia` | 用 `podcastindex_transcript_url/_type` |
| 日志有 `Unsatisfiable Request (only-if-cached)` | 正常缓存回退 | 看后续 `Done Downloading` |
| 悬浮窗不出现 | 无悬浮窗权限 / 未从菜单开 / 当前集无转录 | 查 `appops`、菜单勾选、DB 中 URL |
| UI dump 里找不到 `tv_floating_transcript` | 悬浮窗是独立 window，`uiautomator dump` 只导出活动窗口 | 用 `dumpsys activity services` 判断服务 + 截图人工核对 |
| 流媒体播放失败 | 测试机 DNS 被污染（环境问题，非 App bug） | 用本地 VTT + `adb reverse`，或 `--isolate-network` |
| 播放器加载的不是目标单集 | 转录页打开 `currentlyPlayingMedia`；脚本按“进度最大”推断的 media 可能与播放器恢复的 `lastPlayedFeedMediaId` 不一致 | 先在 UI 播放该集，或显式传 `--media-id <正在播放的 FeedMedia.id>` |
| 设备测试里高亮长时间停在第一句 | 设备偏好中该 media 有时间偏移（`prefTranscriptTimeOffsets`） | 测试前清零该 media 的偏移或换 media id；测完按字节还原偏好 |
| 手动验证跟随时页面一直不动 | 脚本只负责打开转录页，播放器可能仍是暂停态（`media_session` 为 `PAUSED`/`NONE`），ticker 不更新 | 先点播放或 `adb shell input keyevent 126`，确认 `state=PLAYING(3)` 再录屏 |
| 搜索注入 VTT 里的词却显示“无匹配” | 设备上该 media 有旧的本地 `.transcript`（无 `.source` 时会被收编），盖过脚本注入的 VTT | 用当前页面可见的通用词（search 场景用 `the`），或换没有缓存转录的 media；不要为此删用户转录缓存 |
| feed 同时挂 JSON 和 VTT，结果没有词级高亮/解析为 null/丢了译文 | `TranscriptType.JSON` 优先级 4 > VTT 3；应用会优先选 JSON，**双语 VTT 会被盖掉**。TMLT 词级 sidecar 必须是 `start/end(ms)/text/words`，旧格式是 `startTime/endTime(秒)/body` | 只挂一种；词级 JSON 目前只有主语言，若要保留双语译文，暂时不要同时挂 JSON 与双语 VTT |
| 词级 JSON 部分行没有任何高亮 | 这些段是 `words=[]` 的注释/短应答（826/11954），没有词时间轴；`end=null` 时按文本长度估算 0.5–10s | 正常回退句级高亮；不要把它们当解析失败 |
| `--ez open_player` 没反应、UI dump 停在首页 | 备份偏好里 `currentlyPlayingMedia`/`lastPlayedFeedMediaId` 为 `-1`，播放器底栏锁定，`setState(EXPANDED)` 被忽略 | 脚本现在会注入这两个值（及 `lastPlayedFeedId`）；手工测时先确保播放器有当前媒体 |
| 播放中 `uiautomator dump` 一直返回旧位置（如始终 40s）或空 | 持续播放/动画时 uiautomator 等不到 idle，dump 失败后读到上一次的 `/sdcard/window_dump.xml` | 位置断言用 `adb shell dumpsys media_session`；需要文本断言时先 `tt_pause_playback` 再 dump。`dump_ui` 已先删旧文件并重试 3 次 |
| VTT 场景初始页显示上一集旧转录 | 测试前未清掉上一轮写入的本地 `.transcript`，sidecar 有时被判为当前来源 | `backup_transcript_cache` 现在备份后会把三件套移走，强制从注入 URL 下载，teardown 原样写回 |
| 点一次播放反而停住 | 应用已经在播放，`butPlay` 实际是“暂停” | 先读 MediaSession 位置，>5000ms 视为播放中，不要再点 |
| 运行中出现莫名报错（如 `run.sh: line N: TT_SOURCED: command not found`）、跑了两次初始化、设备被留脏 | 运行期间编辑了 `run.sh`/`lib.sh`/场景脚本：bash 按字节偏移续读脚本，读到错位行（`tt_init_env` 会重置 `BACKUP_OK`，teardown 因而跳过恢复） | 测试期间不要改脚本；若已发生，按 `run-*/Antennapod.db.orig` 与 `prefs.orig.xml` 手工回滚并核对 `test urls left: 0` |

## 7. 已知限制 / 未完成

- 搜索只在整屏转录页；浮窗和视频弹窗没有搜索。
- 搜索只匹配当前显示文本（语言模式决定主/译范围），是大小写不敏感的子串匹配，没有整词/正则/跨集搜索；命中只定位、不自动 seek，点击命中句才按现有逻辑跳播。
- 搜索输入在 200ms 防抖后于主线程扫描全部 segment 的显示文本；超长转录（数千段）未做后台索引，首次搜索可能有短暂卡顿。
- 搜索状态不持久化：切集、转录重载、页面重建（旋转）后清空。
- SRT：首行冒号前的文字只有全文件重复出现（或为唯一候选标签）时才当发言人；一次性的散文标签（如 `Note: ...`）会保留在正文里。
- 「点选对齐」的**成功改偏移**路径没有设备自动断言：`1._Greatness.vtt` 每个 cue 占满整屏，`adb input swipe` 会被列表跟随和文本选区吃掉，滚不到另一句；写锚点与刷新高亮与长按共用 `setTranscriptTimeOffset`/`applyOffsetChange`，由长按断言覆盖，选句本身靠人工验收。
- 偏移锚点按**媒体位置**保存。同一集重新下载后（DAI 重新拼广告、时长变化）锚点全部失效；目前没有文件指纹校验，需手动“重置”后重新对齐。
- “对齐到本句”把当前句的起点对齐到按下时的播放位置，误差最多一句；应在句子开始处按，再用 ±0.5s 微调。片头广告段（第一个锚点之前）没有文字，按 0 偏移处理。
- 悬浮窗/页面在 fragment 停止期间发生外部 seek 时，要等下一个 `PlaybackPositionEvent` 才刷新。
- 换句高亮的 250ms 刷新只接在整屏转录页；弹窗与悬浮窗仍按 1 秒 `PlaybackPositionEvent` 更新（弹窗视为废弃，仅视频播放器入口保留）。
- 词级高亮只接在整屏转录页；悬浮窗/视频弹窗仍是句级。词时间轴只覆盖主语言（朗读语言），译文行没有逐词时间，不参与逐词状态。
- 词级只来自 JSON（TMLT sidecar）；VTT/SRT 没有词时间轴，行为与以前一致。
- TMLT 无 `end` 的注释段用 15 字/秒估算 0.5–10s，是估算值；当前 39 集语料中 5 个重复词（0.001%）被丢弃，不影响其余词对齐。
- 词级轮询 100ms，仍可能跳过极短词（语料中位词长 220ms、p10 100ms）；没有做词内进度/逐帧插值。当前词用假粗体突出文本、没有背景块；已读/未读词态覆盖全文，当前词所属句子只用于跟随定位，不再参与视觉着色。
- 悬浮窗不显示发言人（用户要求移除）；`TranscriptSegment.speaker` 仍供转录页使用。
- 转录页跟随 MVP 把当前句放在 45% 阅读线，但未给 RecyclerView 加首尾 anchor padding，因此第一条/最后一条 cue 只能滚到可滚范围，不能居中；若产品要求首尾也居中，再补 padding/spacer 并复跑设备场景（需同时处理 `applyTranscriptLayout()` 的上下留白）。
- 音频转录页的“跟随音频”开关与视频弹窗的其他增强：用户明确表示**暂不做**（“那不用管这个功能”），未被再次要求前不要实现或重提。
- `checkstyle`/`lint` 在 transcript 相关模块存在**既有**违规（未格式化历史代码），按 `AGENTS.md`「不修自己改动之外的告警」保持原样；只在被要求时跑 `./gradlew checkstyle lint`。

## 8. 更新本文档（完成标准）

1. 行为变了 → 更新 §1 对应行；入口增删 → 同步 §0/§3。
2. 代码动过锚点 → 重新 grep，确保 §3 每个行号仍落在对应符号上。
3. 测试变了 → 更新 §5；真机跑完 → 写 §5.2 结果（代码基线 + 脚本提交 + 日期）。
4. 完成标准：代码与本文档在同一提交，`git diff --stat` 两者都在；§1/§3/§5 无未更新项。

全局代码风格、编译、PR 约定见 [AGENTS.md](file:///D:/AntennaPod/AGENTS.md)。
相关记忆（`C:\Users\游梦\.qoder-cn\projects\D--AntennaPod\memory\`）：`transcript-feature-initiative.md`（设计决策与限制）、`transcript-test-corpus.md`（语料基线）、`checkstyle-transcript-baseline.md`、`test-device-network.md`。
