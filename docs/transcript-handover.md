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
| 改生成转录 / 模型 | `:asr` 的 `AsrResultMapper`/`AsrTokenizer`/`PreReadPcmPipeline`，模型固定在 `AsrModelCatalog` |
| 改生成会话 / 兜底 | `:app` 的 `AsrGenerationManager`/`AsrGenerationController`/`AsrFinalizeWorker` |
| 跑相关单测 | `./gradlew --console=plain :asr:test :model:test :ui:transcript:testFreeDebugUnitTest :storage:preferences:testDebugUnitTest :playback:service:testPlayDebugUnitTest :app:testFreeDebugUnitTest` |
| 编译 | `./gradlew :app:assembleDebug` |
| 真机验证（自动 / 手工） | `bash scripts/transcript-tests/run.sh <场景>`（§5.2）；生成转录/流式/兜底/守卫按 §5.4 新增清单 |

阅读顺序建议：§3 代码地图 → §4 数据/配置约定 → §1 功能总览 → §6 坑速查 → §7 未完成项。

## 1. 功能总览

### 1.1 展示入口

| 能力 | 行为 | 位置 |
|---|---|---|
| 音频播放器转录页 | 播放器内的整屏转录列表，含控制条 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L83)，布局 [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml) |
| 视频/浮层转录弹窗 | `DialogFragment`，复用同一 adapter | [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java#L42)，布局 [transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml) |
| 单行渲染 | 发言人 + 正文，正文用可点击/可选词的 TextView | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L168)、[TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java)、布局 [transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml) |
| 悬浮窗（其他应用之上） | 只显示当前句的双语小窗 | [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L46)，布局 [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) |
| 菜单开关 | `transcript_menu_item`（进入转录页）、`floating_transcript_item`（悬浮窗，checkable）、`transcript_item`（旧入口） | [mediaplayer.xml](file:///D:/AntennaPod/app/src/main/res/menu/mediaplayer.xml#L5) |
| 外部拉起播放器并打开转录页 | `am start ... --ez open_player true --ez open_transcript true` | [MainActivity.java:740-745](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java#L740) |
| 播放服务驱动悬浮窗 | `TranscriptFloatingWindowService.start/stop` | [Media3PlaybackService.java:383](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L383)、[AudioPlayerFragment.java:297](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java#L297) |
| 服务注册（未导出） | `android:exported="false"` | [AndroidManifest.xml:301](file:///D:/AntennaPod/app/src/main/AndroidManifest.xml#L301) |

### 1.2 解析与双语

| 能力 | 行为 | 位置 |
|---|---|---|
| 三种格式 | `TranscriptParser.parse(text, type)` 按 MIME 分发 JSON / VTT / SRT | [TranscriptParser.java:12](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java#L12) |
| VTT 结构 | 忽略 `NOTE`/`STYLE`，解析 cue timing/`<v.speaker>` voice span，跳过非法或零时长 cue | [VttTranscriptParser.java:33](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L33) |
| 双语判定 | 双语 cue 占比过半即 `bilingual`；无双语 cue 时检测“交替单语言 cue”（两种文字体系严格交替，各 ≥2 条，≥4 cue） | `VttTranscriptParser.detectAlternatingPrimaryScript()` |
| 交替配对 | 主语言 cue + 下一条译文 cue 合并为一个 segment（`primary\ntranslation`） | [VttTranscriptParser.java:199](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L229) |
| 长双语 cue 不拆句 | 一条 cue 一个 segment（原文 + 换行 + 译文）；段内句子仍按**字符位置比例**估算时间，供高亮/点击/悬浮窗使用。逐句换行开启时，adapter 在显示层把同一 cue 按句配对交替（EN1/ZH1/EN2/ZH2），译文多则并入最后一行、少则重复最后一句 | [VttTranscriptParser.java:100](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L145)、[TranscriptAdapter.java:363](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L381) |
| 语言切换边界 | 按文字体系（Latin / CJK+假名+韩文 / 其他）判定主/译边界，纯符号行忽略 | [TranscriptSentenceUtils.java:22](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L22) |
| 句子切分 | `。！？…` 直接断句；`.` 需后随空白/引号，且**两侧都是数字时不断**（避免把 `2024.` 当小数）；收尾标点并入前句 | [TranscriptSentenceUtils.java:106](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L106) |
| SRT | 仅看首行做发言人识别；候选名需在文件内重复出现（或为唯一候选标签），否则整行保留为正文；支持纯发言人行、保留额外冒号后文本 | [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) |
| JSON（官方段落级） | `version/segments` + `startTime/endTime`（秒→毫秒）+`body`；无 `words` 时短段按 5s 合并；null/空 speaker 沿用上一位发言人（首段为空），`end <= start` 丢弃 | [JsonTranscriptParser.java:52](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L52) |
| JSON（词级：fork / 官方混合） | 任一段含非空 `words`，或首段为 `start`+`text` 且无 `startTime` 时，按 1:1 建段、不再 5s 合并。段字段支持 fork `start/end`（毫秒）+`text`，也支持官方 `startTime/endTime`（秒）+`body`；同时存在时官方字段优先，`body` 为空时回退 `text`。`words=[[词,起点ms,时长ms]]` 按文本顺序对齐（大小写不敏感，容错重复词，仅丢该词区间）；无 `end/endTime` 的注释段按 15 字/秒估算 0.5–10s | [JsonTranscriptParser.java:131](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L137)、[:147](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L153)、[:185](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L191) |
| 乱序容错 | `Transcript.addSegment` 稳定二分插入，不再因乱序抛异常 | [Transcript.java:13](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java#L13) |
| 重叠 cue | `TranscriptSegment.append` 取最大 endTime | [TranscriptSegment.java:26](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java#L26) |

UI 侧的 `TranscriptTextUtils` 是兼容层：分句/语言边界/句子时间转发到 `TranscriptSentenceUtils`，`progressAt` 为本地实现（保留旧入口，避免 `:app`/`:ui:transcript` 重复实现）。

### 1.3 显示控制

| 能力 | 行为 | 位置 |
|---|---|---|
| 语言模式三态 | 双语 / 仅原文 / 仅译文，循环切换并持久化 | `UserPreferences.TRANSCRIPT_LANGUAGE_MODE_*`（[UserPreferences.java:139](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L139)）、`TranscriptAdapter.setLanguageMode()` |
| 逐句换行 | 开启时同一条 cue 内按句配对交替（EN1/ZH1/EN2/ZH2）；关闭时原文段落 + 译文段落 | `isTranscriptSentencePerLine()`、`TranscriptPageFragment.toggleSentencePerLine()` |
| 排版面板 | 控制条 `Aa` 展开的卡片式面板：字号（正文 `16 + step*2` sp，时间戳同步 `14 + step*2` sp，`A−`/`A+` 圆形步进）、行距（1.0/1.15/1.3×）、左右边距（16/24/32dp）、上下留白（紧凑/标准/宽松，含列表首尾）、数值胶囊与恢复默认 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1301)、[TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L106) |
| 控制条与二级面板 | 工具栏转录按钮开合 `controlsBar`；收起一级控制条（或进入沉浸）时同步收起偏移/排版二级面板，重新打开保持收起 | `toggleControlsBar()`、`hideControlPanels()` |
| 分级高亮（两套，互不叠加） | **句级**（VTT/SRT/无词段）：所有段 `alpha=1`，不再整段半透明；当前句 accent+加粗、已播句 `transcript_text_strong`、未播句 `transcript_text_muted`，交错显示时同一对的主/译行同档；**全列表统一字号**。**词级**（JSON 带 `words`，参考 `apple_podcast_transcript_demo.html`）：不叠加句色/句粗；按播放头三态——已读 `transcript_text_strong`、未读 `transcript_text_muted`、当前词 strong + **假粗体**（不改变行宽、避免逐词重排），**没有背景色块**；译文/时间戳用 muted。当前词所属句子只用于跟随定位，不参与视觉着色。颜色 token 定义在主题属性（Light `#101418/#5A6675`，Dark `#F2F4F7/#9AA6B8`，对 `#F9FCFF/#21272B` 分别为 17.97/5.68 与 13.71/6.13:1；真黑继承 Dark）。整屏轮询词级 100ms、句级 250ms；四种语言/换行显示都由 `TranscriptWordMapper` 映射词区间 | `TranscriptAdapter.applyWordGradedHighlight()`/`FakeBoldSpan`/`applySentenceGradedHighlight()`/`sentenceIndexAtOffset()`、`transcript_text_strong`/`transcript_text_muted`、`TranscriptPageFragment.highlightUpdateInterval()` |
| 时长估算校正 | 优先使用 JSON 顶层 `audio_seconds`（TMLT 844 = 3432.05s，**流媒体也生效**）；没有该字段时，本地无 Xing/Info/VBRI 的 MP3 用帧扫描真实时长。与播放器时长差 ≥250ms 时按 `scale = 真实 / 播放器` 校正：播放器位置先换算成转录时间再做高亮/跟随/offset/loop，点击与 seek 反向换算；结果按媒体 id + 来源 + 时长缓存。VTT/SRT 的流媒体没有真实时长来源，保持原样（844.mp3：真 3432.0457s vs 播放器 3424.1774s，+0.2298%） | [Mp3DurationScanner.java](file:///D:/AntennaPod/parser/media/src/main/java/de/danoeh/antennapod/parser/media/Mp3DurationScanner.java)、`TranscriptPageFragment.loadTranscriptTimeScale()`/`toTranscriptTime()`/`toPlayerTime()` |
| 跟随音频 | 整屏页可见且播放中时以 250ms（词级转录 100ms）轮询位置；不在 RESUMED（ViewPager 暂存页）时由 1 秒 `PlaybackPositionEvent` 兜底，两条路径都会同步 Adapter 高亮。ticker 只刷新目标，滚动由 `OnPreDraw`（每帧布局完成、绘制前）驱动；预绘制监听器登记在 `ViewTreeObserver` 上，View 重挂载换了 observer 时重新注册（否则跟随会静默停止）。目标为当前句（主/译成对）中心；**词级转录改为当前词所在行**（新标点模型句子很长，句块中心会让当前词偏离阅读线），阅读线固定在**窗口垂直中点**（屏幕 50%，由 `readingLinePx()` 换算到列表坐标；`ANCHOR_RATIO` 只再用于末项占位）。目标行变化时**直接 snap 到阅读线**，不再播放追动画。ticker 每个周期都确保跟随帧存在（列表静止时也要 `postInvalidate`，否则 `OnPreDraw` 不触发，跟随会静默停住）；首次载入转录后立即启动跟随；补段（段数变化）时无条件吸附，且若 2 秒内没有真实滑动会清掉卡住的 `userScrolling`。目标行未布局时按 10dp/帧推进；初始定位/重开/seek/偏移/回到当前句在目标行不可见时 `scrollToPosition` + 下一帧 snap。用户拖动/暂停/离屏时停表；松手时若当前高亮行仍在屏幕内自动恢复跟随（滑页会带出一次竖直 drag），只有把高亮行滚出屏幕才保持手动模式（需点“回到当前句”/点击句子/调偏移恢复）。列表最后一项用 `ItemDecoration` 在下方加 55% 视口高的占位空间，末段也能滚到 45% 线（不能用 bottom padding，会压缩可见区域）。居中单位是当前句（主/译成对）；弹窗/悬浮窗仍按 1 秒事件 | `TranscriptPageFragment` 的 `highlightTicker`/`followPreDrawListener`/`scrollTowardsCurrentSentence`/`snapToError`/`areFollowAnimationsEnabled`/`stepTowardsRow`/`scrollToPosition`/`pendingSnapToHighlight`/`userScrolling`/`returnToCurrentSentence()`；策略与单测见 [TranscriptFollowMotion.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotion.java)、[TranscriptFollowMotionTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotionTest.java) |
| 沉浸模式 | 隐藏播放器其他区块，只看转录 | `btnImmersive`、提交 `0792497f4` |
| 回到当前句 | `btnBackToCurrent`，仅在偏离时可见 | [TranscriptPageFragment.java:1051](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1051) |
| 文内搜索 | 控制条放大镜打开搜索条：输入即搜（200ms 防抖）、命中背景高亮、当前命中更深、`N/M` 计数、上/下一条循环、关闭清除；只匹配当前显示文本（双语模式主/译都搜，语言模式或逐句开关变化后重算）；定位命中行时暂停自动跟随并显示“回到当前句”，**不改变播放位置**；浮窗与视频弹窗不提供搜索 | `TranscriptPageFragment.setupSearchControls()`([L1070](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L825))/`toggleSearchBar()`([L1101](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1101))/`runSearch()`([L1139](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L894))/`jumpToSearchHit()`([L1186](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L941))/`scheduleSearchScroll()`([L1214](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L969))、`TranscriptAdapter.setSearchQuery()`([L740](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L825))/`applySearchHighlights()`([L714](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L799))/`getSearchHitBounds()`([L789](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L874))、[TranscriptSearch.java:11](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearch.java#L11) |

### 1.4 时间操作

| 能力 | 行为 | 位置 |
|---|---|---|
| 点击跳转 | 有词时间轴时**点到哪个词就跳到哪个词**（起止取该词）；点在译文、词间或普通转录时回退到**所点句子**的起止区间（非整段），双语时按触摸落在主/译区域分别映射 | [TranscriptAdapter.java:625](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L183)、[:652](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L702) → `SegmentClickListener.onTranscriptClicked(pos, seg, start, end)` |
| 选词 | 长按选中文本触发 `onTranscriptWordSelected`（目前仅暂停播放，便于选词） | [TranscriptPageFragment.java:1075](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1075) |
| 偏移控制条 | 卡片式面板：标题栏「对齐到本句」+ 重置、偏移量胶囊；细调 ±0.5s、粗调 ±5/±10/±30s。**对齐到本句是两步式**：先点按钮进入点选模式（按钮变「点选句子」并弹提示），再点**正在播放的那一句**，即以它为锚（`offset = 播放位置 - 该句起点`，不跳播、不改变播放状态），只影响该句之后的段落；锚在第一句（起点≈0）时覆盖整集开头。点到的句子若正好是当前高亮显示的那句（当前 cue 时间落在该句区间内），不改数据，只提示向上滑到真正在播的那句。**长按按钮 = 把第一句对齐到当前位置并覆盖整集**（清空其他锚点），用于片头整段广告。位置取 MediaController 实时值（暂停后拖动进度条也准）。之所以不直接用“当前高亮句”，是因为片头广告这类大幅错位下高亮句本身就是错的 | `toggleOffsetBar()`、`setupOffsetButtons()`（[L1230](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1230)）、`syncOffsetToPickedSentence()`（[L1443](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1174)）、`applyOffsetChange()`（[L1601](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1198)） |
| 偏移持久化 | **按 media id + 媒体位置分段**存 JSON map：`{"<mediaId>": [{"p":起始媒体位置,"o":偏移毫秒}, ...]}`。Δ 时用 `offsetAtMediaTime(pos)`，句/段跳转与单段循环用 `offsetAtCueTime(cueStart)`（锚点换算成 `p-o` 的 cue 时间再比较，因此可以跳过片头广告）；± 微调只改当前所在段（无锚点时在 0 处建一个），重置删除该 media 全部锚点。旧的单数值偏好按“0 处锚点”读入，切集/拖动/回退不再需要重新校准。**广告切掉后字幕时间连续**：允许两个锚点落在同一 cue 时间（按媒体位置靠后的那个在该 cue 起生效），否则重对齐锚点会被丢掉 | [TranscriptTimeOffsets.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/TranscriptTimeOffsets.java)、[UserPreferences.java:966](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L966)、[DBWriter.java:216](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java#L216)、[TranscriptPageFragment.java:1601](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1601) |
| 跳过广告后自动重对齐 | 自动跳过一个**非激进**（`TIER_AUTO`）广告段后，按**实际跳过时的播放位置** `P` 与广告终点 `E` 计算：当前字幕时间 `t = P - offset(P)`，在 `E` 处写入新锚点 `offset = E - t`，跳过前后字幕停在同一句。片段与已有锚点重叠、或两个广告段首尾相接（分块扫描）时同样成立。片头广告已被现有偏移覆盖（`t < 0`）时不再加，避免把同一段广告算两遍；用户已在广告后手动对齐（`offset(E)` 与 `offset(P)` 不同）时不覆盖。激进模式才跳的标记段不调整。对应的 [Media3PlaybackService.java:630](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L661) 在跳过时调用，撤销时回退该锚点 | [TranscriptSync.java:36](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/TranscriptSync.java#L36)、[Media3PlaybackService.java:645](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L683) |
| 跳过广告的撤销 | 前台：Snackbar“已跳过 X 秒广告 / 撤销”；后台/锁屏：每次跳过在通知栏发一条低优先级“已跳过广告”通知（`ad_skip` 通道，10 秒后自动消失），动作按钮“撤销”经 `PendingIntent.getService` 直达播放服务（`onStartCommand` 处理 `ACTION_UNDO`，不依赖动态注册的广播接收器）。撤销 = 回到广告起点 + 本集该段标记为不再跳过 + 回退上一条字幕偏移。未授予 `POST_NOTIFICATIONS` 时不发通知（只保留 Snackbar），开启“检测并跳过广告”时会请求该权限 | [NotificationUtils.java:150](file:///D:/AntennaPod/ui/notifications/src/main/java/de/danoeh/antennapod/ui/notifications/NotificationUtils.java#L150)、[Media3PlaybackService.java:604](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L604)、[FeedSettingsPreferenceFragment.java:352](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/feed/preferences/FeedSettingsPreferenceFragment.java#L352) |
| 单段循环 | 循环当前 `TranscriptSegment` 区间（按该句的 cue 时间取偏移），偏移变化后若区间无效自动停止 | [TranscriptPageFragment.java:1328](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1328) |

### 1.5 悬浮窗细节

- 只显示当前句（不是全文），主/译两个 `ScrollTextView`（自定义 `ValueAnimator` 横向滚动，最高 300px/s），间距已收紧。
- **词级转录（JSON 带 `words`）时主行滚动按词时间轴驱动**：用当前句内各词的 `startChar/startTime` 建 `TranscriptScrollTimeline`（单调三次 Hermite 插值，速度在词边界连续），动画每帧取“当前读到的字符位置”，再按逐字前缀宽度换算成**亚字符像素**位移；**当前词尽量停在窗口水平中央**，句首/句尾自动夹到 0 与 `textWidth-contentWidth`（所以开头靠左、结尾靠右）。语速快则滚得快、长停顿/慢词自动放慢；句级转录与译文行仍用「整句剩余时长匀速」的老逻辑（最高 300px/s）。词级时间轴只有主语言，译文行不参与。
- 调整时间偏移后，悬浮窗立即按新偏移刷新当前句（监听 `prefTranscriptTimeOffsets`）。
- feed 刷新重载转录时保留旧内容直到新转录就绪；重载失败不再清空或退出悬浮窗（仅切集失败/无转录时停止）。
- 播放暂停时保持显示但停止滚动；播放结束、被关闭、当前集无转录时消失。
- 锁定/关闭按钮约 4s 无操作自动隐藏；隐藏时第一次点击只唤出控件，控件可见时点击打开转录页。
- 可拖动并水平吸附到最近左右边缘（纵向只做边界 clamp）；位置以 dp 存偏好（`prefFloatingTranscriptXDp/YDp/Locked`）。
- **不再显示发言人**（按需求移除，`TranscriptSegment` 仍保留 speaker 供转录页使用）。
- 竞态修复：后台 `Callable` 只返回 `FeedMedia`，`currentFeedId`/`lastPlaybackPosition`/`transcript` 一律在主线程回调里赋值，因此 `dispose()` 能真正阻止过期请求污染新剧集状态（[TranscriptFloatingWindowService.java:385](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L385)）。
- 位置刷新不依赖播放服务的 1 秒事件：播放中每 100ms 用 `positionBase + elapsed × speed` 外推（速度由相邻事件自校准，夹在 0.5–4），暂停/隐藏/销毁停表。

### 1.6 本地缓存与来源元数据

- 文件三件套：`<localFileUrl>.transcript`、`<...>.transcript.source`（内容 `type\nURL`）、`<...>.transcript.tmp`（原子写入临时文件）。
- 内存缓存：access-order LRU，最多 8 个，key = mediaId + type + URL + 本地文件 mtime（[TranscriptUtils.java:229](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java#L230)）。
- 下载与本地读取均限 5 MB；下载按响应 charset 解码。
- 本地文件只有在 `.source` 与当前条目一致时才使用；无 `.source` 的旧文件会被解析并**收编**（补写 sidecar）。
- 本地文件损坏/不可解析 → 回退到当前 URL 并覆盖本地文件。
- 强制刷新失败 → 依次回退内存缓存、仍然有效的本地文件。
- `storeTranscript` 先校验可解析才落盘，写 `.tmp` 后 rename（失败则删除目标再 rename，再失败则 copy），并写 sidecar。
- 删除媒体 / 发现本地文件缺失 → `FeedMedia.deleteTranscriptFiles()` 清三件套；同时删除该 media 的时间偏移（本地文件缺失路径见 [PlayActionButton.java:50](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/actionbutton/PlayActionButton.java#L50)）。

### 1.7 刷新联动

| 事件 | 触发方 | 谁响应 | 结果 |
|---|---|---|---|
| `PlaybackPositionEvent` | 播放服务（1 秒） | 弹窗/悬浮窗；整屏页仅在轮询未驱动（暂停/停止）时使用 | 高亮、滚动、句子时间；整屏页播放中由轮询独占，避免旧位置回退（词级 100ms、普通 250ms）；悬浮窗只用它校准播放速度，显示由自身 100ms 外推驱动 |
| `FeedItemEvent` | 条目更新 | 转录页 | 仅当转录来源 URL/类型变化或尚无数据时强制重载 |
| `FeedListUpdateEvent` | feed 更新（`contains(long)` 判定） | 转录页、弹窗、`AudioPlayerFragment`、悬浮窗 | 重新加载媒体信息/转录（悬浮窗重载失败时保留旧内容），菜单可见性刷新 |
| `PlayerStatusEvent` | 播放状态 | 转录页、弹窗、悬浮窗 | 跟随/滚动/暂停语义 |

## 2. 提交历史

完整列表：`git log --author=wenhao --oneline`。下表只保留影响当前实现的关键节点。生成转录（ASR）分支 `transcript-asr-word-timestamps` 的逐条提交索引见 [generated-transcripts-commits.md](generated-transcripts-commits.md)。

| 日期 | 节点 | 影响 |
|---|---|---|
| 2026-08-18 ~ 08-19 | `fdddd12ef`/`f70eeacf6`/`92e6fb82f` | 转录页、Adapter/滚动、`:ui:transcript` 模块与悬浮窗；播放器 ViewPager2 由上下滑动改为水平滑动（`audioplayer_fragment.xml`） |
| 2026-08-21 | `3069aa9df`/`0792497f4`/`2b24e787a`/`867088d88` | 双语 VTT、沉浸模式、固定高亮色、幂等加载 |
| 2026-09-11 ~ 10-13 | `3164027b6`/`7f4eaa113`/`bbe756414` | 上游 develop 合并 |
| 2026-10-22 ~ 10-23 | `a77c90ce7`/`8d8f5616f`/`9df9271a4`/`2ff80e94d`/`fa8e29b85` | 共享分句工具、偏移、分级高亮、图标、正确性修复与单测起步 |
| 2026-10-24 | `3128fe4a9` ~ `7e2b4e5b9` | 当前句悬浮窗、双语配对、细粒度偏移、解析/缓存/刷新加固 |
| 2026-10-24 | `35cf3c35e` | 交接文档与真机脚本 |
| 2026-11-04 | `d39bf0f69` + 本次提交 | 广告检测：分块指纹落库、两段式片头扫描与预热、激进跳过、扫描状态 UI；自动跳过后的字幕重对齐与通知栏撤销 |

## 3. 代码地图

### `:model` — 数据模型
| 文件 | 职责 |
|---|---|
| [Transcript.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java) | 段落列表、`addSegment` 有序插入并标记词级(L13)、`findSegmentIndexBefore`(L30)、`hasWordTimings`(L64)、`isBilingual`(L68) |
| [TranscriptSegment.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java) | 单段 start/end/words/speaker + 可选词时间轴，`append`(L26) 合并重叠 cue 并丢弃词时间轴，`getWordIndexAt`(L57) 二分找“最后已开始的词” |
| [TranscriptWord.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptWord.java) | 词级时间轴单元：`startChar/endChar`（相对段文本）+ `startTime/endTime` |
| [FeedItem.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedItem.java) | `podcastIndexTranscriptUrl/Type`，`transient transcript`(L48)，`updateFromOther` 来源变更失效(L181) |
| [FeedMedia.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedMedia.java) | `getTranscriptFileUrl`、`getGeneratedTranscriptFileUrl`（下载集用音频旁 sidecar；流式集用 `asr-transcripts/<id>.transcript`，有官方转录时为 null）、`getTranscriptMetadataFileUrl`、`deleteTranscriptFiles`（一并清理生成转录）、`setTranscript` |

### `:parser:transcript` — 解析
| 文件 | 职责 |
|---|---|
| [TranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java) | MIME 分发(L12)、`MIN_SPAN` |
| [TranscriptSentenceUtils.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java) | **分句/文字体系/句子时间的唯一实现**：`languageSplitIndex`(L22)、`scriptOf`(L64)、`chunkStarts`(L106)、`sentenceIndexAt`(L137)、`sentenceEndTime`(L148/152)、`sentenceStartTime`、`splitIntoSentences` |
| [VttTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java) | VTT 结构(L33)、双语判定(L83-L94)、`addAlternatingSegments`(L199)、一条 cue 一个 segment(L100)、`detectAlternatingPrimaryScript`(L196) |
| [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) | SRT + 发言人识别 |
| [JsonTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java) | 官方 `startTime/endTime/body` 合并(L52)；词级判定 `usesTimedSegments`(L137)；fork/官方混合 1:1 建段(L153)、字段优先级 `segmentStartTime`/`segmentEndTime`/`segmentText`(L191/L198/L207)、`audio_seconds`(L213)、无 `end` 估算(L220)、词对齐 `parseWordTimings`(L225)/`locateToken`(L257) |

### `:asr` — 生成转录（预读解码 + 流式识别）
| 文件 | 职责 |
|---|---|
| [PreReadPcmPipeline.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/PreReadPcmPipeline.java#L14) | 拉取式预读：跟随 playhead、seek 检测、只补缺口、窗口封顶、源结束；`next`(L62) |
| [MediaCodecPcmDecoder.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/MediaCodecPcmDecoder.java#L18) | MediaExtractor + MediaCodec → 16k mono 100ms chunks，按 PTS 裁剪/重采样 |
| [LookaheadPlanner.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/LookaheadPlanner.java)、[TranscriptCoverage.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/TranscriptCoverage.java)、[MediaRange.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/MediaRange.java) | 预读窗口/覆盖率/缺口纯逻辑 |
| [ChunkAssembler.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/ChunkAssembler.java#L9)、[PcmResampler.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/PcmResampler.java)、[PcmConverter.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/PcmConverter.java) | 定长 chunk 组装、4 阶低通 + 线性插值重采样、多声道→单声道 |
| [AsrGenerationSession.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrGenerationSession.java#L12) | 单线程驱动预读与识别，段随到随发；`run`(L57) |
| [SherpaStreamingRecognizer.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaStreamingRecognizer.java#L15) | endpoint 定稿、reset、流起点与 chunk 不连续重置；`enqueueResult`(L107) |
| [AsrResultMapper.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrResultMapper.java#L8) | `toSegment`(L20)：文本先 trim/合并空格/去标点前空格(`normalizeText` L69)，token 时间→媒体时间并**减 400ms 发射延迟**(L15)，全大写归一、句首大写/句末标点 |
| [AsrTokenizer.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTokenizer.java#L9) | SentencePiece token（`▁`/前导空格）→ 词区间与字符区间，CJK 一字一词；`tokenize`(L16)：段首游离 `▁.` 忽略、独立 `▁` 当词边界、标点 token 只能贴当前光标并附着前一个词、token 大小写不敏感兜底 |
| [AsrTranscriptWriter.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptWriter.java#L10)、[AsrTranscriptStore.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptStore.java#L20)、[AsrTranscriptConverter.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptConverter.java) | word-JSON 序列化（内嵌 `version/model/coverage`）、原子写读、与 `Transcript` 互转 |
| [SherpaRecognizerFactory.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaRecognizerFactory.java)、[SherpaModelSpec.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaModelSpec.java)、[AsrModelCatalog.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelCatalog.java)、[AsrModelStore.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelStore.java)、[ModelDownloader.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/ModelDownloader.java) | 模型规格/下载/安装（**必须 `new OnlineRecognizer(null, config)`**，非空 AssetManager 会让 native 进程崩溃）；`AsrModelCatalog` 现固定 x-asr 480ms 中英标点 int8；工厂自定义 `EndpointConfig`，rule3 最小语句 30s（L17），避免 20s 强制切词太频繁 |
| [GeneratedTranscriptCheckpoint.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/GeneratedTranscriptCheckpoint.java#L14)、[AsrModelDescriptor.java](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelDescriptor.java) | 续跑元数据（version 1 严格匹配）、模型描述 |

### `:ui:transcript` — 加载与悬浮窗
| 文件 | 职责 |
|---|---|
| [TranscriptUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java) | `loadTranscriptFromUrl`(L32)、`loadTranscript`(L84)、`loadLocalTranscript`(L136)、`cacheKey`(L229)、`parseTranscript`(L257)、`storeTranscript`(L274)、`.source` 读写与收编 |
| [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java) | `start`(L110)/`stop`(L127)、窗口与拖拽、`loadTranscript`(L381/385)、`onFeedListUpdateEvent`(L491)、`updateTranscript`(L511) + 句子缓存、`hideTranscript`(L661)；100ms 位置外推（`positionTick`/`estimatedPosition`） |
| [TranscriptTextUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptTextUtils.java) | 兼容层，分句/语言边界/句子时间转发 `TranscriptSentenceUtils`；`progressAt` 为本地实现 |
| [ScrollTextView.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/ScrollTextView.java) | 悬浮窗用自动横向滚动 TextView（`ValueAnimator`，句级 `startAutoScroll` 最高 300px/s；词级 `startTimedScroll` 按时间轴逐词推进，不设速度上限） |
| [TranscriptScrollTimeline.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptScrollTimeline.java) | 纯逻辑：词时间轴 → 句内字符位置（`of`/`charIndexAt`/`getEndTime`），悬浮窗词级滚动速度由它决定；单测 [TranscriptScrollTimelineTest.java](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptScrollTimelineTest.java) |
| [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) | `tv_floating_transcript`、`tv_floating_transcript_translation`、`btn_floating_lock`、`btn_floating_close` |

### `:app` — 页面与交互
| 文件 | 职责 |
|---|---|
| [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java) | 转录页全部逻辑（加载、事件、滚动、点击、搜索、循环、偏移、排版、字号、语言/换行）；词级轮询 `highlightUpdateInterval()`；MP3 时长比例校正；生成转录固定 `scale=1.0`/偏移 0、「生成/继续生成声音文字」、模型下载与 `AsrGenerationEvent` 刷新。**跟随**：`handlePlaybackPosition`(L741) 同步 Adapter 并调度跟随；`canAutoFollow`(L783) 用 `isShown()` 而非 RESUMED；`scheduleFollowFrame`(L804) 校验/重注册 `ViewTreeObserver`；`scrollTowardsCurrentSentence`(L843) 词级按当前词行居中；`applyTranscriptLayout`(L1757) 用 `ItemDecoration` 末项占位；`onMediaLoaded`(L547) 批量刷出时按段数变化 `notifyDataSetChanged` 并恢复跟随 |
| [AsrGenerationManager.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrGenerationManager.java#L25) | 进程级生成会话：等待播放服务切到目标 media、共享 `PlaybackServiceInterface` 位置、每秒检查切集、EventBus 发 `AsrGenerationEvent` |
| [AsrGenerationController.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrGenerationController.java#L41) | 单集生成：模型规格/转录存储/预读管线/识别器/`AsrGenerationSession`，每段落盘，结束时用 `Mp3DurationScanner` 补真实时长 |
| [AsrGenerationEvent.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrGenerationEvent.java#L7) | STARTED/SEGMENT/PROGRESS/COMPLETED/ERROR/STOPPED |
| [AsrFinalizeWorker.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrFinalizeWorker.java) | 进程死亡后的后台补全：前台通知、从 checkpoint 续跑、9 分钟预算、最多 5 次重试；只处理已下载单集 |
| [AsrFinalizeScheduler.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrFinalizeScheduler.java) | 会话开始时排 +15 分钟唯一任务，正常结束/停止时取消 |
| [TranscriptFollowMotion.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotion.java) | 跟随策略纯函数：8dp 起步/2dp 停、0.75 视口 snap、320–520ms 三次缓出、距离→时长（阅读线由页面按窗口中点计算，`ANCHOR_RATIO` 现仅用于末项占位）；单测 [TranscriptFollowMotionTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptFollowMotionTest.java) |
| [TranscriptSearch.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearch.java) | 搜索匹配纯函数：大小写不敏感、CJK 子串、非重叠多次命中；单测 [TranscriptSearchTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptSearchTest.java) |
| [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java) | `getDisplayInfo`(L311)、`applyWordGradedHighlight`(L497，当前行之前的行整行按已读着色)、`getSentenceBounds`(L647，词级返回当前词所在行)、`updatePlaybackPosition`(L938，生成转录偏移 0；词/句变化走 `refreshBoundRow`(L974) 就地刷新，不发 `notifyItemChanged`，避免列表末尾滚动被钳回)、句级高亮/FakeBoldSpan/词级点击/搜索/`SegmentClickListener` |
| [TranscriptWordMapper.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapper.java) | 纯函数：把段内词区间映射到显示文本（逐句换行/交错双语用 `ranges`，按句索引起搜；大小写不敏感、词边界校验）；单测 [TranscriptWordMapperTest.java](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapperTest.java) |
| [Mp3DurationScanner.java](file:///D:/AntennaPod/parser/media/src/main/java/de/danoeh/antennapod/parser/media/Mp3DurationScanner.java) | 纯 Java MP3 帧扫描：跳过 ID3v2/ID3v1，检测 Xing/Info/VBRI，按帧数/采样率算真实时长；单测 [Mp3DurationScannerTest.java](file:///D:/AntennaPod/parser/media/src/test/java/de/danoeh/antennapod/parser/media/Mp3DurationScannerTest.java) |
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
| [TranscriptTimeOffsets.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/TranscriptTimeOffsets.java) | 纯逻辑：偏移锚点表，`offsetAtMediaTime`/`offsetAtCueTime`、`setAnchor`（同位置 1s 内替换且保留更早起点）、`setOffsetForSegment`、按双时间轴 `normalize`（L126：丢弃 cue 时间倒退的锚点，**保留同一 cue 时间**的锚点，靠后媒体位置者在边界起生效） |
| [TranscriptSync.java](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/TranscriptSync.java#L12) | 自动跳过广告后的字幕重对齐：`alignAfterSkip`(L36) 按跳过位置写锚点并返回 `Shift`，`undo`(L55) 回退；只处理 `TIER_AUTO`，片头偏移已覆盖或用户已对齐时不改；单测 [TranscriptSyncTest.java](file:///D:/AntennaPod/playback/service/src/test/java/de/danoeh/antennapod/playback/service/internal/TranscriptSyncTest.java) |
| [Media3PlaybackService.java](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L661) | `adSkipTick`(每 250ms) 跳过并调用 `showSkipUndo`(L630)；`undoAdSkip`(L645) 回退并取消通知；`onStartCommand`(L574) 处理通知栏撤销动作；`AdSkipController` 决定哪些段可跳 |
| [NotificationUtils.java](file:///D:/AntennaPod/ui/notifications/src/main/java/de/danoeh/antennapod/ui/notifications/NotificationUtils.java#L150) | `ad_skip` 通道（L26/L143）与跳过通知：`showAdSkipUndo`(L150) / `cancelAdSkipUndo`(L165)，10 秒超时、撤销动作由调用方传入 `PendingIntent` |
| [FeedSettingsPreferenceFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/feed/preferences/FeedSettingsPreferenceFragment.java#L343) | 每 feed 的检测/激进开关与扫描状态摘要；开启检测且无通知权限时请求 `POST_NOTIFICATIONS`(L352) |
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
| `prefAsrLookaheadSeconds` | int/40 | 生成转录预读窗口（秒），10–120，按会话读取；必须 ≥ 分段长度（rule3 30s）才能让文字持续领先 |
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
| `:parser:transcript` | [VttTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParserTest.java)、[SrtTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParserTest.java)、[JsonTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParserTest.java) | 双语保留译文、非双语不误判、长 cue 不拆句（含数字句末）、null speaker、非法时间、乱序；TMLT 1:1 建段与词字符区间、重复词只丢该词、空 `end` 估算、旧格式无词时间轴；官方 `startTime/endTime/body` + `words` 混合格式保留词时间轴、任意段带 `words` 时都不合并；`audio_seconds` 解析 / 缺失为 0 |
| `:parser:media` | [Mp3DurationScannerTest](file:///D:/AntennaPod/parser/media/src/test/java/de/danoeh/antennapod/parser/media/Mp3DurationScannerTest.java) | 无 Xing 时按帧数算时长、识别 Xing、跳过 ID3v2/ID3v1、非 MP3 返回 null |
| `:asr` | [AsrTokenizerTest](file:///D:/AntennaPod/asr/src/test/java/de/danoeh/antennapod/asr/AsrTokenizerTest.java)、[AsrResultMapperTest](file:///D:/AntennaPod/asr/src/test/java/de/danoeh/antennapod/asr/AsrResultMapperTest.java)、[AsrPipelineSeamTest](file:///D:/AntennaPod/asr/src/test/java/de/danoeh/antennapod/asr/AsrPipelineSeamTest.java)、`PreReadPcmPipelineTest`、`LookaheadPlannerTest`、`TranscriptCoverageTest`、`PcmResamplerTest`、`ChunkAssemblerTest`、`AsrTranscriptWriterTest`、`AsrTranscriptStoreTest`、`ModelDownloaderTest`、`AsrGenerationSessionTest` 等（**112** 个，2026-11-05） | token→词区间（`▁`/前导空格边界、CJK 一字一词、缺失标点/byte token 跳过、整段回退；**段首游离 `▁.` 跳过、独立 `▁` 视为词边界、token 大小写不敏感匹配、标点附着前一个词**）；文本先 trim/合并空格/去掉标点前空格，再减 **400ms** 发射延迟、全大写归一、句首大写/句末标点；预读窗口/覆盖率/缺口、重采样与 chunk 组装、word-JSON 往返、模型解压安装（tar 长文件名用 `LONGFILE_POSIX`）、会话定稿/停止 |
| `:ui:transcript` | [TranscriptUtilsTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptUtilsTest.java)、[TranscriptFloatingWindowServiceTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowServiceTest.java)、[TranscriptScrollTimelineTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptScrollTimelineTest.java) | sidecar 写入/收编/过期、5MB、损坏回退 URL、强刷回退、原子写、悬浮窗过期请求竞态（可控调度队列 + `RxJavaPlugins.reset()`）；**流式生成转录从应用私有目录加载**；词时间轴按词推进字符位置、句内中途起播从当前词开始、相对显示句偏移、越界 clamp、无词时退化为匀速 |
| `:storage:preferences` | [UserPreferencesTest](file:///D:/AntennaPod/storage/preferences/src/test/java/de/danoeh/antennapod/storage/preferences/UserPreferencesTest.java) | 按媒体偏移、删除、旧值迁移；分段锚点从锚点位置起生效、取最近锚点、按 cue 时间查询可跳过片头广告、微调只改当前段、同位置替换保留更早起点、重置清空全部锚点；同一 cue 时间的锚点保留（`testAnchorsSharingACueTimeAreKept`，L136）；`asrLookaheadIsClamped`（生成转录预读窗口 10–120s） |
| `:playback:service` | [TranscriptSyncTest](file:///D:/AntennaPod/playback/service/src/test/java/de/danoeh/antennapod/playback/service/internal/TranscriptSyncTest.java) | 自动段跳过把偏移加长一个广告时长；标记段/片头已被偏移覆盖/用户已对齐时不改；重叠段按实际跳过位置续接当前 cue；撤销恢复原偏移、无原偏移时清空 |
| `:app` | [TranscriptTextUtilsTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextUtilsTest.java)、[TranscriptWordMapperTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptWordMapperTest.java)、[AsrFinalizeSchedulerTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/AsrFinalizeSchedulerTest.java) | 数字句末 chunkStarts、sentenceEndTime；词区间在逐句换行/交错双语中的映射、词内子串不误匹配；WorkManager 兜底任务按 mediaId 唯一、不同集互不影响、可取消（Robolectric + `work-testing`）；兜底完整性判定（覆盖到末尾/提前结束/缺失/未知时长）；运行中守卫判定（投屏/省电/正常态） |

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

2026-11-01 MP3 时长估算兼容（代码父基线 `4be375c42`；本次改动未提交）：844 JSON 带 `audio_seconds=3432.05`，844.mp3 无 Xing/Info；Media3 报 **3424.1774s**，差 **7.868s = 0.2298%**。优先用 JSON 时长（流媒体也可用），没有该字段才本地扫描 MP3 帧。`word --position 3000000` 真机 **18 PASS / 0 FAIL**（`run-20261101-080045`；早前同项 20/0 见 `run-20261101-074611`）：播放器显示 50:00 时当前词已到真实约 3007s 的 “Abraham”，50:50 时到 “Here”，证明比例校正生效；`--position 5000` 词级 18/0、VTT 18/0 回归通过，设备已还原。扫描器与 `audio_seconds` 单测见 §5.1。

2026-10-28 VTT 回归（词级/句级分流后，同一工作树）：`run.sh smoke highlight --skip-install --media-id 8878 --position 5000` **18 PASS / 0 FAIL**，双语逐句行、一条 cue 一行、句级三档高亮截图均通过，产物 `~/.qoder-cn/tmp/transcript-ondevice/run-20261028-091115/`（分流前的同项结果见 `run-20261028-085437`）。测试底座顺手修正三处跨轮串帧：`dump_ui` 每次先删 `/sdcard/window_dump.xml` 并重试；VTT 场景先暂停再取稳定 dump；备份后把本地转录缓存移走，强制本次从注入 URL 重新下载，teardown 再按 sha256 原样写回。

2026-10-29 分段偏移 + 「对齐到本句」（代码基线 `4be375c42` + 未提交工作树；脚本同工作树）：单测 `./gradlew --console=plain :storage:preferences:test` **15/15**（新增：锚点从位置起生效、cue 时间查表可跳过片头广告、微调只改当前段、同位置替换保留更早起点、首句锚定覆盖整集开头、编辑夹紧不越过相邻段、重置清空、旧值迁移），`:app:test` 与 `./gradlew :app:assembleDebug` 通过。真机 Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）`bash scripts/transcript-tests/run.sh offset --media-id 1 --position 5000`：**20 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`。断言链：重置 0 → +30 显示 +30 → 「对齐到本句」胶囊 +5 且 `prefTranscriptTimeOffsets` 写成锚点数组 → 段内 +30 变 +35（只改当前段）→ 重置后锚点清空。截图 `offset_07.png` 确认标题栏「对齐到本句」+「重置」与胶囊排版正常。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261029-060434/`。
本轮首跑失败过一次：测试运行期间编辑了 `run.sh`，bash 按字节偏移续读脚本，重复执行初始化并把 `BACKUP_OK` 重置，导致 teardown 跳过恢复、设备被留脏（媒体 1 的转录 URL 指向 `127.0.0.1:8765`）。已把该 URL 按 10-28 干净备份改回 `https://lzoreal.github.io/TMLT/transcripts/zh/899.vtt`，并核对 `integrity_check=ok`、测试 URL 0 条、偏好与备份逐字节一致；`adb reverse tcp:8765` 为运行前既有，未改动。见 §6 最后一行。

2026-10-29 两步式点选对齐 + 长按整集对齐 + 实时播放位置（代码基线 `1da04a427` + 未提交工作树；脚本同工作树）：单测 `:storage:preferences:test` **16/16**（新增 `testStartOffsetReplacesEveryAnchor`），`./gradlew :app:assembleDebug` 通过。真机 Redmi 2407FRK8EC `run.sh offset --media-id 1 --position 5000`：**21 PASS / 0 FAIL**，`restored integrity: ok`、`test urls left: 0`。断言链：重置 → +30 → 进点选模式 → 点“已显示的那句”被拦下（偏移不变、仍在点选模式）→ 段内 +30 只改当前段（+30 → +60）→ 长按按第一句对齐整集（胶囊 +70.1、只剩 0 处锚点）→ 重置清空。产物：`~/.qoder-cn/tmp/transcript-ondevice/run-20261029-065626/`。此前设备上的 `{p:2959,o:1919},{p:17797,o:6037},{p:114307,o:-23}` 是旧单点版留下的实验锚点，长按一次即可覆盖。

2026-11-04 广告跳过与字幕联动（父基线 `a62649fd6` + 本次工作树；脚本未改）在 Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）上验证：单测 `:storage:preferences:testDebugUnitTest` **17/17**、`:playback:service:testFreeDebugUnitTest` **8/8**，`:app:assembleDebug` 通过。真机日志（`M3PlaybackService`）确认自动段跳过（`Skipped ad to 99914/111050`）与前台 Snackbar；通知最初不出现，`dumpsys package` 显示 `POST_NOTIFICATIONS: granted=false`、`dumpsys notification` 里应用 `importance=NONE`，授予后恢复 `DEFAULT`（代码已加权限判断，并在开启检测时请求）。首版重对齐在片头已有手动锚点时会把同一段广告算两遍（11128 的 `p=0,o=99600`），已改为按实际跳过位置计算并允许同 cue 时间的锚点；单测覆盖重叠段与首尾相接。**撤销通知与中段广告偏移的最终真机复测在提交后进行**。

2026-11-05 审查修复后的真机回归（代码基线 `82c210810`）：Redmi 2407FRK8EC 上重新安装并全量抓 logcat，media 8057 自动跳过 `409 -> 31754`（`tier=0 seg=128-31504`）、`skip undo shift=true`、`AdSkipController 1/1 skippable aggressive=true`，跳过后无异常/崩溃/生成失败；此前报告的“跳过广告时出现错误”在本构建未复现。同轮代码审查修复（`:asr:test` 111/111、`:app:assembleDebug` 通过）：停止会话不再回调 `COMPLETED`（避免误报完成并把事件记到切换后的新 mediaId）；后台收尾在窗口已覆盖时跳到首个缺口而不是空转；checkpoint 只覆盖识别器已定稿的音频；转录保存改为唯一临时文件 + rename 覆盖（消除缺文件窗口）；词时间夹在流区间内（不再回卷到上一段）；收尾 worker 每 5 秒让位于实时会话；守卫停止保留兜底排期；模型下载线程改用局部引用避免页面销毁后 NPE。

2026-11-05 中文生成与居中真机迭代（代码基线 `c2e2c7d5f` + 未提交工作树）：① 中文高亮：分词把标点并回前一个中文字，`JsonTranscriptParser.locateToken` 容忍词间中文标点（旧转录也能恢复），`TranscriptWordMapper` 以汉字/中文标点为边界逐字映射；真机确认逐字高亮正常。② 居中：临时把当前词染红做判别，确认「当前词不在中线」根因是列表静止时 `OnPreDraw` 不再触发、跟随帧被吞；修复为 ticker 每周期重新确保跟随帧、首次载入即跟随、目标行变化直接吸附到**窗口垂直中点**。连续 30 秒截帧验证：跨行/跨段时红字落在中线 ±30px（y≈1338/1384，中线 1356）。③ 期间还修了：MIUI 省电守卫（`POWER_SAVE_MODE_OPEN`）、进程重启后 `currentMediaId=-1` 导致「继续生成」按钮缺失（持久化 id 兜底 + 服务侧同步）、模型下载 100% 后显示「正在解压并校验模型…」。**仍未验证**：WorkManager 后台收尾、in-app 模型下载端到端、投屏/温控守卫。

2026-11-05 模型下载与后台收尾真机（代码基线 `bf9175347`）：① in-app 下载端到端通过——把已装模型目录改名后，转录页提示 134 MB、下载到临时目录、逐文件 SHA-256 校验、解压安装（encoder 155,278,641 / decoder 11,309,084 / joiner 2,581,422 / tokens 58,806 字节），随后自动开始生成；旧的中断临时目录已清理。② 后台收尾：`am force-stop` 会把应用置于 stopped 状态，JobScheduler 任务被暂缓，15 分钟后不会自行拉起；`am kill` 对带前台播放服务的进程无效，需先暂停播放再用 `run-as … kill -9`。进程死亡后到点的任务在**下次打开 App** 时立即执行（`AsrFinalizeWorker run media=8056 attempt=1`）。首次执行暴露并修复了续跑卡死：起点落在已覆盖区间内导致窗口全被覆盖、`decodedEnd` 不变、`onCaughtUp` 被去重，现改为从最大覆盖末尾（277.96s）开始（`AsrGenerationController` + `PreReadPcmPipeline.coverageEndMs()`，新增单测）。本机 MIUI 会暂缓后台 Job，因此“完全无人值守自动收尾”无法在本设备验证；“打开应用后补全”路径可用。

2026-11-02 悬浮窗词级滚动（工作树，父基线 `4be375c42` + 未提交工作树；脚本未改）：新增 `TranscriptScrollTimeline`，词级转录（JSON 带 `words`）时悬浮窗主行按当前词的时间/字符位置推进，替代「整句剩余时长匀速」；句级转录与译文行不变。真机反馈“不够平滑、要居中”后同轮修正：①字符位置改单调三次 Hermite（词边界速度连续）②位移按逐字前缀宽度做亚字符插值（不再整字跳）③`scrollX = clamp(当前字前缀宽 - contentWidth/2, 0, textWidth-contentWidth)`，当前词居中、首尾夹边。`./gradlew --console=plain :ui:transcript:testPlayDebugUnitTest` 全绿（`TranscriptScrollTimelineTest` 6 例，含“单调且无速度突变”），`:app:installPlayDebug` 通过。真机（2407FRK8EC，844 词级 JSON）：句首靠左、句中两端同时被裁切、切句回句首，未见崩溃；逐帧平滑度未做录屏量化。

2026-11-04 在 Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）手工验证 x-asr 480ms 中英标点模型（未跑脚本套件，代码基线为当轮未提交工作树）：词级分词/标点/标点空格、段首游离 `▁.`、独立 `▁`、词级高亮、45% 居中（当前词行）、批量刷出后继续跟随、暂停/恢复均通过；`rule3=30s`、预读窗口 40s、400ms 回拨（用户体感刚好）。同时发现：开启“跳过广告”时会出现错误（未复现定位，列为下一优先项）。

### 5.3 语料基线（人工比对用）

真实双语语料：`D:\MyTranscript\site\the-rest-is-history\transcripts\zh\`（远端镜像 `https://lzoreal.github.io/TMLT/transcripts/zh/`）。
`1._Greatness.vtt` 共 62 个 cue，每个 cue 两行（拉丁英文 + CJK 中文）；一条 cue 一行后应得到 **62** 个 segment（逐句拆行时代的 316 行基线已废弃）。合成 fixture 不足以证明双语渲染正确，需用该语料在真机核对。

词级语料：`D:\TMLT\docs\timings\<集号>.json`（与 `D:\TMLT\docs\transcripts\<集号>.vtt` 同源的 sidecar）。`844.json` 有 317 段、8831 个词；全部 39 集共 11954 段、357964 个词，其中 826 段是无词注释（`[INAUDIBLE]` 等，`end=null`），解析对齐后仅 5 个重复词被丢弃（0.001%，见 §7）。844 首段 `start=880ms`、首个词 `From@1120ms`，可直接用 `word` 场景验证首屏词级高亮。

### 5.4 手工验收清单

- [ ] 转录页一条 cue 一行、整段同字号高亮（颜色/加粗/透明度）；时间戳字号随 Aa 档位同步；段内三档（已播/当前/未播）；逐句换行开启时主/译按句交替
- [ ] 词级 JSON：当前词全色+加粗、无背景，已读 strong、未读 muted，随音频逐词移动（100ms 刷新）；点击词从该词起播；仅原文/逐句换行/双语交替下词态不跑偏；无词注释段与无词时间轴的转录回退句级高亮/点击
- [ ] 颜色：Light/Dark/TrueBlack 分别截图；strong/muted 对比度 ≥4.5:1；句级当前/已播/未播三档可区分且未读不低到不可读；不靠颜色单独表达状态（当前词同时加粗）
- [ ] 长 MP3（无 Xing）：在 50% 与接近结尾处核对当前词/句与音频一致；点击远处句子/单段循环 seek 落点正确；开头 5s 仍对齐
- [ ] 语言三态循环（双语 / 仅原文 / 仅译文）与逐句换行开关
- [ ] 字号放大/缩小后高亮行同步
- [ ] 排版面板：字号/行距/左右边距/上下留白各档生效，“恢复默认”回到标准，强制停止后重启仍保留
- [ ] 点击句子从该句起点播到该句终点；单段循环可开关
- [ ] 文内搜索：放大镜打开搜索条，输入主/译文本有命中背景高亮与 `N/M` 计数；上/下一条定位到命中行并暂停跟随；无匹配显示提示；关闭清除高亮
- [ ] 偏移粗调/细调/重置，切集后再切回仍保留；删除单集后偏移消失
- [ ] 分段偏移：片头广告（如 0–100s）在真实内容处**长按**「对齐到本句」，第一句立即对上且不跳播；中间广告后点「对齐到本句」→ 点正在播放的那一句，往回拖动到广告前段落时高亮仍正确；点错（点到已显示的那句）有提示且不改数据；段内 ± 只改当前段，重置一次清空
- [ ] 滑页/拖动后的跟随：松手时当前高亮行仍在屏幕内会自动恢复跟随，滚出屏幕才保持手动模式（“回到当前句”恢复）；跟随把当前词所在行（句级为主/译成对）放在视口 45% 阅读线；列表末段用占位空间也能居中，首句受顶部空间限制
- [ ] 收起一级控制条或进入沉浸时，偏移/排版二级面板一并收起
- [ ] 换句高亮在句边界约 250ms 内切换，无整行淡入淡出；相邻句与换段滚动为 320–520ms 单向三次缓出（距离越远时长越长），远跳/离屏跨段瞬时到位；系统关闭动画时直接跳位
- [ ] feed 刷新后（转录 URL 变化）页面/弹窗/悬浮窗/菜单自动更新
- [ ] 悬浮窗只显示当前句、双语间距、暂停不滚动、锁定/关闭 4s 自动隐藏、拖动水平吸附、点击回转录页；词级转录时主行滚动跟着当前词走（快词快滚、长停顿变慢），当前词停在窗口中央、句首靠左/句尾靠右，切句不跳回句首
- [ ] 断网 + 已有本地文件时可离线显示；本地文件损坏时能重新下载

**2026-11-04 起新增（生成转录 / x-asr 标点模型 / 流式 / 兜底）**

- [ ] 广告跳过（开启）：播放有广告的已下载集，抓 `adb logcat -v time M3PlaybackService:V AndroidRuntime:E "*:S"`；每次跳应有 `skip media=… from=… to=… tier=… seg=…` 与 `skip undo shift=…` 各一条；记录任何报错/重复提示/崩溃
- [ ] 流式生成：未下载单集播放中打开转录页→生成；日志 `AsrGeneration start … streaming=true`；转录写入 `files/asr-transcripts/<mediaId>.transcript`；暂停/继续、切集停止正常
- [ ] WorkManager 兜底：生成中 `adb shell am force-stop de.danoeh.antennapod.debug`；约 15 分钟后出现 `asr_finalize` 前台通知，`AsrFinalizeWorker` 从 checkpoint 继续；正常完成/手动停止不应留下待执行任务
- [ ] 运行中守卫：生成中开始投屏/开启省电/触发严重温控 → 会话停止并 toast 原因；条件解除后可重新生成
- [ ] 模型下载：清空 `files/asr-models` 后点生成，提示含 134 MB；下载后逐文件 SHA-256 校验通过（篡改/截断应拒绝安装）
- [ ] 中文单集：标点、分词（无粘连/无单词语段）、逐词高亮正常
- [ ] 跟随回归：滑页误触后自动恢复；批量刷出后继续跟随；暂停/恢复、切集/切页返回仍接近 45%；末段可居中

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
| 长音频越往后 transcript 越落后，点击跳转也偏 | MP3 无 Xing/Info 头，Media3 用字节/码率估时长（844.mp3 真 3432.0457s vs 报 3424.1774s，+0.2298%）；位置/seek map 同比例偏 | JSON 有 `audio_seconds` 直接用（流媒体也生效）；没有该字段时本地 MP3 帧扫描。VTT/SRT 流媒体没有来源，仍需下载后重开或手动校准 |
| `--ez open_player` 没反应、UI dump 停在首页 | 备份偏好里 `currentlyPlayingMedia`/`lastPlayedFeedMediaId` 为 `-1`，播放器底栏锁定，`setState(EXPANDED)` 被忽略 | 脚本现在会注入这两个值（及 `lastPlayedFeedId`）；手工测时先确保播放器有当前媒体 |
| 播放中 `uiautomator dump` 一直返回旧位置（如始终 40s）或空 | 持续播放/动画时 uiautomator 等不到 idle，dump 失败后读到上一次的 `/sdcard/window_dump.xml` | 位置断言用 `adb shell dumpsys media_session`；需要文本断言时先 `tt_pause_playback` 再 dump。`dump_ui` 已先删旧文件并重试 3 次 |
| VTT 场景初始页显示上一集旧转录 | 测试前未清掉上一轮写入的本地 `.transcript`，sidecar 有时被判为当前来源 | `backup_transcript_cache` 现在备份后会把三件套移走，强制从注入 URL 下载，teardown 原样写回 |
| 点一次播放反而停住 | 应用已经在播放，`butPlay` 实际是“暂停” | 先读 MediaSession 位置，>5000ms 视为播放中，不要再点 |
| 运行中出现莫名报错（如 `run.sh: line N: TT_SOURCED: command not found`）、跑了两次初始化、设备被留脏 | 运行期间编辑了 `run.sh`/`lib.sh`/场景脚本：bash 按字节偏移续读脚本，读到错位行（`tt_init_env` 会重置 `BACKUP_OK`，teardown 因而跳过恢复） | 测试期间不要改脚本；若已发生，按 `run-*/Antennapod.db.orig` 与 `prefs.orig.xml` 手工回滚并核对 `test urls left: 0` |
| 跳过了广告但通知栏没有“撤销”，检测进度也没有通知 | Android 13+ 未授予 `POST_NOTIFICATIONS`，通知被静默丢弃；`dumpsys notification` 里应用 `AppSettings importance=NONE` | 打开播客设置里的“检测并跳过广告”会请求权限（`FeedSettingsPreferenceFragment`）；被拒后到系统设置开启。前台 Snackbar 撤销不受影响 |

## 7. 已知限制 / 未完成

- 搜索只在整屏转录页；浮窗和视频弹窗没有搜索。
- 搜索只匹配当前显示文本（语言模式决定主/译范围），是大小写不敏感的子串匹配，没有整词/正则/跨集搜索；命中只定位、不自动 seek，点击命中句才按现有逻辑跳播。
- 搜索输入在 200ms 防抖后于主线程扫描全部 segment 的显示文本；超长转录（数千段）未做后台索引，首次搜索可能有短暂卡顿。
- 搜索状态不持久化：切集、转录重载、页面重建（旋转）后清空。
- SRT：首行冒号前的文字只有全文件重复出现（或为唯一候选标签）时才当发言人；一次性的散文标签（如 `Note: ...`）会保留在正文里。
- 「点选对齐」的**成功改偏移**路径没有设备自动断言：`1._Greatness.vtt` 每个 cue 占满整屏，`adb input swipe` 会被列表跟随和文本选区吃掉，滚不到另一句；写锚点与刷新高亮与长按共用 `setTranscriptTimeOffset`/`applyOffsetChange`，由长按断言覆盖，选句本身靠人工验收。
- 偏移锚点按**媒体位置**保存。同一集重新下载后（DAI 重新拼广告、时长变化）锚点全部失效；目前没有文件指纹校验，需手动“重置”后重新对齐。
- 自动重对齐只处理 `TIER_AUTO`（非激进）段；激进模式才跳的标记段、以及片头广告已被现有偏移覆盖（`播放位置 - 偏移 < 0`）的段不改；用户已在广告后手动对齐时也不覆盖。
- 撤销广告跳过沿用旧逻辑：播放回到广告起点，并把该段在本集标记为“不再跳过”，所以撤销后同一广告不会再次自动跳过。通知栏撤销 10 秒后消失；连续跳过会覆盖上一条，只能撤销最近一次。未获通知权限时后台没有撤销入口，只剩前台 Snackbar。
- “对齐到本句”把当前句的起点对齐到按下时的播放位置，误差最多一句；应在句子开始处按，再用 ±0.5s 微调。片头广告段（第一个锚点之前）没有文字，按 0 偏移处理。
- 悬浮窗/页面在 fragment 停止期间发生外部 seek 时，要等下一个 `PlaybackPositionEvent` 才刷新。
- 换句高亮的 250ms 刷新只接在整屏转录页；弹窗与悬浮窗仍按 1 秒 `PlaybackPositionEvent` 更新（弹窗视为废弃，仅视频播放器入口保留）。
- 词级高亮只接在整屏转录页；悬浮窗/视频弹窗仍是句级（悬浮窗只用词时间轴驱动横向滚动速度，不做逐词着色）。词时间轴只覆盖主语言（朗读语言），译文行没有逐词时间，不参与逐词状态，也不参与词级滚动。
- 悬浮窗的刷新不依赖播放服务的 1 秒 `PlaybackPositionEvent`：窗口可见且播放中时自己起 100ms 定时器，用 `positionBase + elapsed × speed` 外推位置（speed 由相邻两次事件的位置差/时间差自校准，夹在 0.5–4），所以换句/滚动不会比整屏转录页（100ms/250ms 轮询）晚一秒。暂停、隐藏、`onDestroy` 都会停表。
- 词级只来自 JSON（TMLT sidecar）；VTT/SRT 没有词时间轴，行为与以前一致。
- TMLT 无 `end` 的注释段用 15 字/秒估算 0.5–10s，是估算值；当前 39 集语料中 5 个重复词（0.001%）被丢弃，不影响其余词对齐。
- 词级轮询 100ms，仍可能跳过极短词（语料中位词长 220ms、p10 100ms）；没有做词内进度/逐帧插值。当前词用假粗体突出文本、没有背景块；已读/未读词态覆盖全文，当前词所属句子只用于跟随定位，不再参与视觉着色。
- 时长比例校正优先用 JSON `audio_seconds`（流媒体也生效）；没有该字段时只对**本地下载**、**无 Xing/Info/VBRI** 的 MP3 帧扫描。VTT/SRT 流媒体、未下载、扫描失败或带 Xing 的文件仍按播放器时长；若 Media3 的位置本身是帧精确、只有时长元数据错，比例校正会反向拉偏（844 已验证是位置按比例偏）。
- 悬浮窗不显示发言人（用户要求移除）；`TranscriptSegment.speaker` 仍供转录页使用。
- 转录页跟随 MVP 把当前句放在 45% 阅读线，但未给 RecyclerView 加首尾 anchor padding，因此第一条/最后一条 cue 只能滚到可滚范围，不能居中；若产品要求首尾也居中，再补 padding/spacer 并复跑设备场景（需同时处理 `applyTranscriptLayout()` 的上下留白）。
- 音频转录页的“跟随音频”开关与视频弹窗的其他增强：用户明确表示**暂不做**（“那不用管这个功能”），未被再次要求前不要实现或重提。
- `checkstyle`/`lint` 在 transcript 相关模块存在**既有**违规（未格式化历史代码），按 `AGENTS.md`「不修自己改动之外的告警」保持原样；只在被要求时跑 `./gradlew checkstyle lint`。

- **生成转录（ASR）已打通模块、转录页与进程级续跑（已下载单集 + 播放中的流式单集），真机已完成识别、词级高亮与时间对齐验证**：仅本播放器；从本地文件/`SimpleCache` 预读解码（领先播放头 10–120s，默认 40s），不复用播放 PCM 抽头；官方转录优先；单语、不翻译；统一使用同一个中英双语标点模型（见下一条），不再按 `feed.language` 分模型；增量 checkpoint、只补缺口不重识别；缺口由 UI 动态标注；生成转录隐藏时间偏移控制；仅 arm64-v8a；投屏时禁用。决策见 [ADR 0001](file:///D:/AntennaPod/docs/adr/0001-generated-transcripts-decode-ahead-of-playhead.md)、[ADR 0002](file:///D:/AntennaPod/docs/adr/0002-generated-transcripts-are-gap-aware-artifacts.md)，术语见 [GLOSSARY.md](file:///D:/AntennaPod/GLOSSARY.md)。**已实现**：[PreReadPcmPipeline](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/PreReadPcmPipeline.java#L1)（拉取式：跟随 playhead、seek 检测、只补缺口、窗口封顶、源结束）、[MediaCodecPcmDecoder](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/MediaCodecPcmDecoder.java#L1)（MediaExtractor + MediaCodec → 16k mono 100ms chunks）、[CacheAudioSource](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/CacheAudioSource.java#L1)/[CacheMediaDataSource](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/CacheMediaDataSource.java#L1)（SimpleCache span 随机读、等待播放器缓存与 content-length）、[LookaheadPlanner](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/LookaheadPlanner.java#L1)、[TranscriptCoverage](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/TranscriptCoverage.java#L1)、[PcmResampler](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/PcmResampler.java#L1)、[ChunkAssembler](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/ChunkAssembler.java#L1)；word-JSON 管线 [AsrWord](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrWord.java#L1)/[AsrSegment](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrSegment.java#L1)/[AsrTokenizer](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTokenizer.java#L1)/[AsrTranscriptWriter](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptWriter.java#L1)；sherpa 封装 [AsrResultMapper](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrResultMapper.java#L1)/[StreamingAsr](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/StreamingAsr.java#L1)/[SherpaModelSpec](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaModelSpec.java#L1)/[SherpaRecognizerFactory](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaRecognizerFactory.java#L1)/[SherpaStreamingRecognizer](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/SherpaStreamingRecognizer.java#L1)；运行循环 [AsrGenerationSession](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrGenerationSession.java#L1)；模型管理 [AsrModelDescriptor](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelDescriptor.java#L1)/[AsrModelCatalog](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelCatalog.java#L1)/[AsrModelStore](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrModelStore.java#L1)/[ModelDownloader](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/ModelDownloader.java#L1)；续跑元数据 [GeneratedTranscriptCheckpoint](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/GeneratedTranscriptCheckpoint.java#L1)；落盘 [AsrTranscriptStore](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptStore.java#L1)/[AsrTranscriptConverter](file:///D:/AntennaPod/asr/src/main/java/de/danoeh/antennapod/asr/AsrTranscriptConverter.java#L1)（checkpoint 内嵌在同一 word-JSON 的 `version/model/coverage` 键，不新增 sidecar）；app 接线 [AsrGenerationController](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrGenerationController.java#L1) + 转录页「生成声音文字」按钮与模型下载确认（[TranscriptPageFragment](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L1)、[transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml#L1)）；生成 `audio_seconds` 时对无 Xing 的本地 MP3 用 `Mp3DurationScanner` 真实时长，避免整集漂移；**词级时间按实测流式发射延迟回拨**：流式 zipformer 的 token 时间戳是发射帧，比真实起音晚约 400ms（2026-11-04 用 14056/14049 两集的 2–7kHz 起音检测测得中位数 400/434ms；回拨后新生成段中位 +37ms；480ms 标点模型实测 140–380ms、噪声大，暂沿用 400ms，用户体感确认刚好），`AsrResultMapper.TOKEN_EMISSION_LAG_MS` 在映射时统一减掉；生成转录强制 `scale=1.0`、偏移 0 并隐藏偏移控制；[AsrGenerationManager](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/AsrGenerationManager.java#L1) 为进程级会话（页面销毁后仍在后台继续，用播放服务共享的 mediaId/position 作播放头，EventBus `AsrGenerationEvent` 回传，切集自动停止），转录页底部「继续生成声音文字」可续跑未完成的生成转录；预读窗口 [UserPreferences.getAsrLookaheadSeconds](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L1) + 播放设置 `SeekBarPreference`（10–120s，默认 40s，按会话读取；必须 ≥ rule3 分段长度）；投屏/省电/严重温控守卫（投屏隐藏按钮，省电/温控点击提示）；`:asr` 已加入 `settings.gradle`。**验证**：`./gradlew --console=plain :asr:test` **112/112**、`:asr:checkstyle` 通过、`:app:assembleDebug` 通过；APK 只含 `lib/arm64-v8a/libsherpa-onnx-*.so`（解压前约 9.2MB），`:ui:transcript:testFreeDebugUnitTest` 通过、`:storage:preferences:testDebugUnitTest` **18/18**；2026-11-04 在 Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）真机跑通 MediaCodec 解码 + sherpa 识别（14056/14049，后台续跑、暂停/重开高亮、继续按钮），并用拉回的 MP3 与转录 JSON 做离线起音比对验证时间对齐。调试标签：`AsrSync`（页面 raw/tx/段/词起止时间）、`AsrSherpa`（流起点、token 首尾时间、映射后首尾）、`AsrGeneration`（段、播放头、覆盖率）、`AsrManager`（播放头心跳）。**流式单集**：已把 `SimpleCache` 接到生成入口——播放中的流式单集用 `CacheAudioSource`（key = `getStreamUrl()`）预读已缓存字节，转录写到应用私有 `files/asr-transcripts/<mediaId>.transcript`（`FeedMedia.getGeneratedTranscriptFileUrl()`）；只在“该集正在播放（缓存有数据）”时允许生成，缓存不足会等待/超时；**真机未验证**；进程被杀后已加 WorkManager 兜底：会话开始时排一个 +15 分钟的唯一任务（`AsrFinalizeScheduler`），进程死亡后由 `AsrFinalizeWorker` 带前台通知从 checkpoint 续跑剩余缺口（每次 ≤9 分钟，失败/超时指数退避，最多 5 次），正常完成/停止会取消；只覆盖已下载单集，真机未验证；投屏/省电/严重温控做启动守卫，并在运行中每秒检查（`AsrGenerationManager.guardStopReason`）主动停止会话、页面 toast 原因（真机未验证）；模型下载对话框已显示精确体积（134 MB，取自 descriptor）；`CacheAudioSource` 仅有假 Cache 单测，真实 SimpleCache/设备未验证。**待处理**：`:asr:prepareSherpaAar` 已从官方 v2.0.1 URL 下载 AAR 并按 SHA-256 校验（`libs/` 仍被忽略，无需入库）；上游未发布逐文件 SHA-256，catalog 已按 2026-11-04 本地解包结果 pin 四个文件（encoder/decoder/joiner/tokens）的 SHA-256，下载器逐文件校验，失败即拒绝安装；SpotBugs 因本机网络（代理 `127.0.0.1:7897` 拒绝连接）未能运行。`TranscriptUtils` 已对无 type 的本地 JSON 按 `{` 嗅探（`TranscriptUtilsTest.testGeneratedJsonWithoutTypeIsParsed` 通过），生成转录可被现有加载器读取；`:ui:transcript:testFreeDebugUnitTest` 通过，`:ui:transcript:checkstyle` 仍有既有违规，按 AGENTS 未修；`:app:checkstyle` 仍有 AudioPlayerFragment/TranscriptPageFragment 等既有违规，新增 `AsrGenerationController` 的无用导入已修，未动其他既有告警。

- **2026-11-04 模型与跟随迁移（x-asr 480ms 标点模型）**：识别模型从 2023 年的 en-20M/zh-14M 换成单个 `sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`（压缩 134MB、解压 163MB，encoder.int8 155MB），中英双语、自带标点与大小写；设备实测 CPU ≈53%、PSS ≈670–720MB（旧模型约 300MB 级），仅 arm64、仍要求已下载单集。配套改动：① rule3 强制切分 20s→**30s**，预读窗口默认 30s→**40s**（窗口 < 分段长度时每轮会有约 20s 文字追不上播放，实测 rule3=50s 时明显）；② 文本先 trim/合并空格/去除标点前空格，`AsrTokenizer` 处理新模型的三类 token 怪癖（段首游离 `▁.`、独立 `▁` 表示空格、标点 token 附着前一个词）并加大小写不敏感兜底；③ 跟随/居中修复：词级改按**当前词所在行**居中（长句按句块中心会让当前词偏离）、末项用 `ItemDecoration` 加 55% 视口占位（bottom padding 会压缩可见区）、可见性改用 `isShown()`（ViewPager 页可能 STARTED 而非 RESUMED）、松手时高亮行仍在屏幕内自动恢复跟随（滑页会带出竖直 drag）、View 重挂载换 `ViewTreeObserver` 时重新注册预绘制监听器（否则跟随静默停止）、位置事件与轮询都同步 Adapter、批量刷出转录时检测段数变化并 `notifyDataSetChanged`+恢复跟随；④ 生成转录存在性判定（播放器工具栏、单集菜单、悬浮窗启动）统一为“官方转录 URL 或本地 `.transcript` 文件非空”，此前只认 URL，导致生成转录的悬浮窗开关和单集菜单项失效；⑤ 语言路由清理：删除 `AsrModelLanguage` 与 `forLanguage`，catalog/store/downloader/controller 只接受单模型，模型描述不再带语种。**已知问题（未修/未验证）**：开启“跳过广告”时出现错误（2026-11-05 用加日志的构建复测未复现：media 8057 跳过 409→31754ms、字幕移位成功、日志无异常，继续观察；审查遗留未修：流式会话在播放服务释放 `SimpleCache` 后会报错、缓存/解码超时被当作致命错误直接结束会话、下载单集的官方字幕晚到仍会被本地生成文件遮蔽、兜底重试次数会被“实时会话占用”消耗、流式单集的生成转录随条目删除不会清理）；已在 `Media3PlaybackService` 加 `skip media/from/to/tier/seg` 与 `skip undo shift` 日志，抓 `M3PlaybackService` 标签即可）；rule3=30s 仍会偶发把词切断（`reply`+`Ing.`、`Ba.`+`Yswater`、`dete`+`Ction.`）；新模型的 in-app 下载/校验链路未端到端验证（测试时是 adb 预推模型）；中文识别效果未测；内存偏高，低端机需评估 160ms/960ms 档位或更小模型；高频调试日志已降级：`AsrSync`/`AsrFollow`/`AsrManager` tick/`AsrGeneration` caughtUp 为 `Log.d`，`AsrSherpa` 每段落一条仍为 `Log.i`，`M3PlaybackService` 跳过日志保留 `Log.i` 供排查。

## 8. 更新本文档（完成标准）

1. 行为变了 → 更新 §1 对应行；入口增删 → 同步 §0/§3。
2. 代码动过锚点 → 重新 grep，确保 §3 每个行号仍落在对应符号上。
3. 测试变了 → 更新 §5；真机跑完 → 写 §5.2 结果（代码基线 + 脚本提交 + 日期）。
4. 完成标准：代码与本文档在同一提交，`git diff --stat` 两者都在；§1/§3/§5 无未更新项。

全局代码风格、编译、PR 约定见 [AGENTS.md](file:///D:/AntennaPod/AGENTS.md)。
相关记忆（`C:\Users\游梦\.qoder-cn\projects\D--AntennaPod\memory\`）：`transcript-feature-initiative.md`（设计决策与限制）、`transcript-test-corpus.md`（语料基线）、`checkstyle-transcript-baseline.md`、`test-device-network.md`。
