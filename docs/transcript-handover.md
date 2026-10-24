# AntennaPod 转录（Transcript）功能交接文档

> 面向对象：接手这个 fork 转录功能的下一个 agent / 开发者。
> 基线：分支 `transcript-regression-hardening`，转录功能代码基线提交 `7e2b4e5b9`（2026-10-24），上游基线提交 `bbe756414`（Merge develop，2026-10-13）。
> 全量单测在该 HEAD 之前已跑通：`./gradlew --console=plain test` → `BUILD SUCCESSFUL`，3666 个任务，0 失败。

## 0. 30 秒上手

| 想做的事 | 命令 / 入口 |
|---|---|
| 改解析 / 分句 | `:parser:transcript`，先看 `VttTranscriptParser` 与 `TranscriptSentenceUtils` |
| 改转录页显示 | `:app` 的 `TranscriptAdapter.getDisplayInfo()` + `TranscriptPageFragment` |
| 改悬浮窗 | `:ui:transcript` 的 `TranscriptFloatingWindowService` |
| 改本地缓存/文件 | `:ui:transcript` 的 `TranscriptUtils` |
| 跑相关单测 | `./gradlew --console=plain :parser:transcript:test :model:test :ui:transcript:test :storage:preferences:test :app:test` |
| 编译 | `./gradlew :app:assembleDebug` |
| 真机验证（自动） | `bash scripts/transcript_device_test.sh`（见 §5.2） |

阅读顺序建议：§3 代码地图 → §4 数据/配置约定 → §1 功能总览 → §6 坑速查 → §7 未完成项。

## 1. 功能总览

### 1.1 展示入口

| 能力 | 行为 | 位置 |
|---|---|---|
| 音频播放器转录页 | 播放器内的整屏转录列表，含控制条 | [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L50)，布局 [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml) |
| 视频/浮层转录弹窗 | `DialogFragment`，复用同一 adapter | [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java#L42)，布局 [transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml) |
| 单行渲染 | 发言人 + 正文，正文用可点击/可选词的 TextView | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L163)、[TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java)、布局 [transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml) |
| 悬浮窗（其他应用之上） | 只显示当前句的双语小窗 | [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L45)，布局 [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) |
| 菜单开关 | `transcript_menu_item`（进入转录页）、`floating_transcript_item`（悬浮窗，checkable）、`transcript_item`（旧入口） | [mediaplayer.xml](file:///D:/AntennaPod/app/src/main/res/menu/mediaplayer.xml#L5) |
| 外部拉起播放器并打开转录页 | `am start ... --ez open_player true --ez open_transcript true` | [MainActivity.java:740-745](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java#L740) |
| 播放服务驱动悬浮窗 | `TranscriptFloatingWindowService.start/stop` | [Media3PlaybackService.java:317](file:///D:/AntennaPod/playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java#L317)、[AudioPlayerFragment.java:248](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java#L248) |
| 服务注册（未导出） | `android:exported="false"` | [AndroidManifest.xml:299](file:///D:/AntennaPod/app/src/main/AndroidManifest.xml#L299) |

### 1.2 解析与双语

| 能力 | 行为 | 位置 |
|---|---|---|
| 三种格式 | `TranscriptParser.parse(text, type)` 按 MIME 分发 JSON / VTT / SRT | [TranscriptParser.java:12](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java#L12) |
| VTT 结构 | 解析 `NOTE`/`STYLE`/cue timing/`<v.speaker>` voice span，跳过非法或零时长 cue | [VttTranscriptParser.java:33](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L33) |
| 双语判定 | 双语 cue 占比过半即 `bilingual`；无双语 cue 时检测“交替单语言 cue”（两种文字体系严格交替，各 ≥2 条，≥4 cue） | `VttTranscriptParser.detectAlternatingPrimaryScript()` |
| 交替配对 | 主语言 cue + 下一条译文 cue 合并为一行 `primary\ntranslation` | [VttTranscriptParser.java:211](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L211) |
| 长双语 cue 逐句拆分 | 两行 cue 按主语言句子拆成多行，译文按序号 1:1 配对：译文多则并入最后一行，少则重复最后一句；行时间按**字符位置比例**分配 | [VttTranscriptParser.java:257](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java#L257) |
| 语言切换边界 | 按文字体系（Latin / CJK+假名+韩文 / 其他）判定主/译边界，纯符号行忽略 | [TranscriptSentenceUtils.java:22](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L22) |
| 句子切分 | `。！？…` 直接断句；`.` 需后随空白/引号，且**两侧都是数字时不断**（避免把 `2024.` 当小数）；收尾标点并入前句 | [TranscriptSentenceUtils.java:106](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java#L106) |
| SRT | 仅看第一行做发言人识别，支持纯发言人行、保留额外冒号后文本（已知缺陷见 §7） | [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) |
| JSON | `startTime/endTime`（秒→毫秒），null speaker 视为无发言人，`end <= start` 丢弃 | [JsonTranscriptParser.java:40](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java#L40) |
| 乱序容错 | `Transcript.addSegment` 稳定二分插入，不再因乱序抛异常 | [Transcript.java:12](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java#L12) |
| 重叠 cue | `TranscriptSegment.append` 取最大 endTime | [TranscriptSegment.java:16](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java#L16) |

UI 侧的 `TranscriptTextUtils` 已全部转发到 `TranscriptSentenceUtils`（保留旧入口，避免 `:app`/`:ui:transcript` 重复实现）。

### 1.3 显示控制

| 能力 | 行为 | 位置 |
|---|---|---|
| 语言模式三态 | 双语 / 仅原文 / 仅译文，循环切换并持久化 | `UserPreferences.TRANSCRIPT_LANGUAGE_MODE_*`（[UserPreferences.java:136](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L136)）、`TranscriptAdapter.setLanguageMode()` |
| 逐句换行 | 每个句子独占一行（双语时主/译成对） | `isTranscriptSentencePerLine()`、`TranscriptPageFragment.toggleSentencePerLine()` |
| 字号 | 步进制（`16 + step*2` sp，高亮 `19 + step*2`），缓存在 adapter 字段 | [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L333) |
| 分级高亮 | 当前段加粗+全亮，非当前段半透明；避免动态取色导致文字消失 | `TranscriptAdapter.highlightViewHolder()` |
| 跟随音频 | 播放时滚动到当前句；用户手动滚动时暂停跟随，回到当前句后恢复 | `TranscriptPageFragment` 的 `userScrolling`/`returnToCurrentSentence()`/`resumeFollowingIfHighlightVisible()` |
| 沉浸模式 | 隐藏播放器其他区块，只看转录 | `btnImmersive`、提交 `0792497f4` |
| 回到当前句 | `btnBackToCurrent`，仅在偏离时可见 | [TranscriptPageFragment.java:518](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L518) |

### 1.4 时间操作

| 能力 | 行为 | 位置 |
|---|---|---|
| 点击跳转 | 点击落到**所点句子**的起止区间（非整段），双语时按触摸落在主/译区域分别映射 | [TranscriptAdapter.java:308](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java#L308) → `SegmentClickListener.onTranscriptClicked(pos, seg, start, end)` |
| 选词 | 长按选中文本触发 `onTranscriptWordSelected` | [TranscriptPageFragment.java:539](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L539) |
| 偏移控制条 | 粗调 ±1/±5s、细调 ±100/±500ms、重置、当前偏移显示 | `toggleOffsetBar()`、`setupOffsetButtons()`（[TranscriptPageFragment.java:659](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L659)） |
| 偏移持久化 | **按 media id** 存 JSON map；旧的单槽偏好首次读取时迁移；删除媒体/条目时移除；范围 clamp 到 ±时长 | [UserPreferences.java:952-999](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java#L952)、[DBWriter.java:121](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java#L121) |
| 单句循环 | 循环当前句区间，偏移变化后若区间无效自动停止 | [TranscriptPageFragment.java:547](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java#L547) |

### 1.5 悬浮窗细节

- 只显示当前句（不是全文），主/译两个 `ScrollTextView`（marquee 滚动），间距已收紧。
- 播放暂停时保持显示但停止滚动；播放结束、被关闭、当前集无转录时消失。
- 锁定/关闭按钮约 4s 无操作自动隐藏；隐藏时第一次点击只唤出控件，控件可见时点击打开转录页。
- 可拖动并吸附到最近屏幕边缘；位置以 dp 存偏好（`prefFloatingTranscriptXDp/YDp/Locked`）。
- **不再显示发言人**（按需求移除，`TranscriptSegment` 仍保留 speaker 供转录页使用）。
- 竞态修复：后台 `Callable` 只返回 `FeedMedia`，`currentFeedId`/`lastPlaybackPosition`/`transcript` 一律在主线程回调里赋值，因此 `dispose()` 能真正阻止过期请求污染新剧集状态（[TranscriptFloatingWindowService.java:360](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java#L360)）。

### 1.6 本地缓存与来源元数据

- 文件三件套：`<localFileUrl>.transcript`、`<...>.transcript.source`（内容 `type\nURL`）、`<...>.transcript.tmp`（原子写入临时文件）。
- 内存缓存：access-order LRU，最多 8 个，key = mediaId + type + URL + 本地文件 mtime（[TranscriptUtils.java:229](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java#L229)）。
- 下载与本地读取均限 5 MB；下载按响应 charset 解码。
- 本地文件只有在 `.source` 与当前条目一致时才使用；无 `.source` 的旧文件会被解析并**收编**（补写 sidecar）。
- 本地文件损坏/不可解析 → 回退到当前 URL 并覆盖本地文件。
- 强制刷新失败 → 依次回退内存缓存、仍然有效的本地文件。
- `storeTranscript` 先校验可解析才落盘，写 `.tmp` 后 rename（失败则删除目标再 rename，再失败则 copy），并写 sidecar。
- 删除媒体 / 发现本地文件缺失 → `FeedMedia.deleteTranscriptFiles()` 清三件套；同时删除该 media 的时间偏移。

### 1.7 刷新联动

| 事件 | 触发方 | 谁响应 | 结果 |
|---|---|---|---|
| `PlaybackPositionEvent` | 播放服务 | 转录页/弹窗/悬浮窗 | 高亮、滚动、句子时间 |
| `FeedItemEvent` | 条目更新 | 转录页 | 仅当转录来源 URL/类型变化或尚无数据时强制重载 |
| `FeedListUpdateEvent` | feed 更新（`contains(long)` 判定） | 转录页、弹窗、`AudioPlayerFragment`、悬浮窗 | 重新加载媒体信息/转录，菜单可见性刷新 |
| `PlayerStatusEvent` | 播放状态 | 转录页、弹窗、悬浮窗 | 跟随/滚动/暂停语义 |

## 2. 提交时间线（作者 `wenhao`，共 25 个提交）

| SHA | 日期 | 主题 | 要点 |
|---|---|---|---|
| `fdddd12ef` | 2026-08-18 | transcript_page | 新建音频播放器转录页 `TranscriptPageFragment` + 布局，`AudioPlayerFragment` 接入 |
| `f70eeacf6` | 2026-08-18 | transcript_page | Adapter 绑定与滚动细节 |
| `92e6fb82f` | 2026-08-19 | 悬浮窗 transcript | 新建 `:ui:transcript` 模块、`TranscriptFloatingWindowService`(256 行)、`ScrollTextView`、悬浮窗偏好与字符串 |
| `3069aa9df` | 2026-08-21 | 双语vtt | VTT 多行 cue 合并为 `primary\ntranslation`，`Transcript.isBilingual`，弹窗/页面布局调整 |
| `0792497f4` | 2026-08-21 | 沉浸模式 | 播放器沉浸模式、翻译菜单项、全屏图标 |
| `2b24e787a` | 2026-08-21 | 修复动态取色导致非高亮脚本消失 | 高亮改用固定色 + alpha，不再用主题动态取色 |
| `867088d88` | 2026-08-21 | 播放暂停同一文件不再刷新字幕；长字幕显示优化 | 幂等加载 + proguard keep 规则 |
| `3164027b6`/`7f4eaa113`/`bbe756414` | 09-11 ~ 10-13 | Merge develop | 上游同步（无功能改动） |
| `a77c90ce7` | 2026-10-22 | op | 新增 `TranscriptTextUtils`（分句/语言边界/句子时间映射）、偏移控制条布局 |
| `8d8f5616f` | 2026-10-22 | op | 时间偏移偏好、播放服务启停悬浮窗、菜单重构、悬浮窗精简 |
| `9df9271a4` | 2026-10-22 | 分级高亮 | Adapter 分级高亮（当前句/当前段/其他），句子级索引 |
| `fa8e29b85` | 2026-10-23 | fix correctness, harden playback integration, add tests | 首轮系统性正确性修复 + 单测起步 |
| `2ff80e94d` | 2026-10-23 | 统一控制条按钮样式，文字按钮换图标 | `ic_transcript_*` 图标 |
| `3128fe4a9` | 2026-10-24 | 悬浮窗只显示当前句 | 句子级裁剪 + 时间估算 |
| `c96ae59e4` | 2026-10-24 | 控制条打磨 + 悬浮窗双语 | 主/译双 `ScrollTextView` |
| `1a6e5d07e` | 2026-10-24 | 滚动暂停跟随、悬浮窗点击回页、细粒度偏移 | `userScrolling`、细调按钮 |
| `c6aae3a20` | 2026-10-24 | 短滚动返回后位置错误 | 以 `PlaybackPositionEvent` 为准，不再读页面缓存 position |
| `f5f9f61ff` | 2026-10-24 | 统一按文字体系判定的语言边界 | `scriptOf`/`languageSplitIndex` 合并为一套 |
| `db72bb0ee` | 2026-10-24 | 高亮、悬浮窗、解析器边界用例 | 生命周期与解析修复 |
| `e0d5702bd` | 2026-10-24 | 悬浮窗/解析/双语加固 | 交替双语、乱序 cue、缓存 |
| `d2ea9742e` | 2026-10-24 | 位置、解析、悬浮窗 bug | 句子时间、偏移 |
| `bc5d42b06` | 2026-10-24 | 逐句双语配对模式 + 悬浮窗控件 | `addSentencePairs` 1:1 配对、锁定/关闭自动隐藏 |
| `7e2b4e5b9` | 2026-10-24 | harden parsing, caching, refresh and floating window | 本分支最新：竞态、`.source`、按媒体偏移、`FeedListUpdateEvent`、5MB、原子写入、共享分句工具、悬浮窗句子缓存等（提交信息按文件详述） |

## 3. 代码地图

### `:model` — 数据模型
| 文件 | 职责 |
|---|---|
| [Transcript.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/Transcript.java) | 段落列表、`addSegment` 有序插入(L12)、`findSegmentIndexBefore`、`isBilingual`(L60) |
| [TranscriptSegment.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/TranscriptSegment.java) | 单段 start/end/words/speaker，`append`(L16) 合并重叠 cue |
| [FeedItem.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedItem.java) | `podcastIndexTranscriptUrl/Type`，`transient transcript`(L48)，`updateFromOther` 来源变更失效(L181) |
| [FeedMedia.java](file:///D:/AntennaPod/model/src/main/java/de/danoeh/antennapod/model/feed/FeedMedia.java) | `getTranscriptFileUrl`(L501)、`getTranscriptMetadataFileUrl`(L508)、`deleteTranscriptFiles`(L513)、`setTranscript` |

### `:parser:transcript` — 解析
| 文件 | 职责 |
|---|---|
| [TranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptParser.java) | MIME 分发(L12)、`MIN_SPAN` |
| [TranscriptSentenceUtils.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/TranscriptSentenceUtils.java) | **分句/文字体系/句子时间的唯一实现**：`languageSplitIndex`(L22)、`scriptOf`(L64)、`chunkStarts`(L106)、`sentenceIndexAt`(L137)、`sentenceEndTime`(L148/152)、`sentenceStartTime`、`splitIntoSentences` |
| [VttTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParser.java) | VTT 结构(L33)、双语判定、`addAlternatingSegments`(L211)、`addBilingualCue`(L245)、`addSentencePairs`(L257)、`sentenceRanges` |
| [SrtTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParser.java) | SRT + 发言人识别 |
| [JsonTranscriptParser.java](file:///D:/AntennaPod/parser/transcript/src/main/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParser.java) | JSON 词级/段级 |

### `:ui:transcript` — 加载与悬浮窗
| 文件 | 职责 |
|---|---|
| [TranscriptUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptUtils.java) | `loadTranscriptFromUrl`(L32)、`loadTranscript`(L84)、`loadLocalTranscript`(L136)、`cacheKey`(L229)、`parseTranscript`(L257)、`storeTranscript`(L274)、`.source` 读写与收编 |
| [TranscriptFloatingWindowService.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowService.java) | `start`(L85)/`stop`(L102)、窗口与拖拽、`loadTranscript`(L356/360)、`onFeedListUpdateEvent`(L415)、`updateTranscript`(L432) + 句子缓存、`hideTranscript`(L554) |
| [TranscriptTextUtils.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/TranscriptTextUtils.java) | 兼容层，全部转发 `TranscriptSentenceUtils` |
| [ScrollTextView.java](file:///D:/AntennaPod/ui/transcript/src/main/java/de/danoeh/antennapod/ui/transcript/ScrollTextView.java) | 悬浮窗用 marquee TextView |
| [floating_transcript_layout.xml](file:///D:/AntennaPod/ui/transcript/src/main/res/layout/floating_transcript_layout.xml) | `tv_floating_transcript`、`tv_floating_transcript_translation`、`btn_floating_lock`、`btn_floating_close` |

### `:app` — 页面与交互
| 文件 | 职责 |
|---|---|
| [TranscriptPageFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptPageFragment.java) | 转录页全部逻辑（加载 L167/L233、事件 L295-L336、滚动 L406、点击 L479、循环 L547、偏移 L612-L704、语言/换行/字号 L731-L761） |
| [TranscriptAdapter.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptAdapter.java) | `onCreateViewHolder`(L97 绑定监听器)、`getDisplayInfo`(L211 显示缓存)、高亮、句子时间(L308)、`SegmentClickListener`(L450) |
| [TranscriptDialogFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptDialogFragment.java) | 视频弹窗：`onTranscriptClicked`(L106)、`onFeedListUpdateEvent`(L153)、`loadMediaInfo`(L173/L177)、`scrollToPosition` |
| [TranscriptTextView.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextView.java) | 点击 + 选词 TextView |
| [AudioPlayerFragment.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/AudioPlayerFragment.java) | 悬浮窗开关(L248/L250)、菜单可见性、`FeedListUpdateEvent` 重载 |
| [PlayActionButton.java](file:///D:/AntennaPod/app/src/main/java/de/danoeh/antennapod/actionbutton/PlayActionButton.java) | 本地文件缺失时一并清理转录文件 |
| 布局 | [transcript_page_fragment.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_page_fragment.xml)（`recyclerView`/`controlsBar`/`offsetControls`/`btnBackToCurrent`/`btnImmersive`/`btnLineBreakToggle`/`btnTranslationToggle`/`btnFontLarger`/`btnFontSmaller`/`btnSegmentLoop`/`btnOffsetToggle`/`txtOffsetValue`/`emptyView`/`progLoading`）、[transcript_dialog.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_dialog.xml)（`transcript_list`/`followAudioCheckbox`）、[transcript_item.xml](file:///D:/AntennaPod/app/src/main/res/layout/transcript_item.xml)（`speaker`/`content`） |
| 图标 | `app/src/main/res/drawable/`：`ic_transcript_*`、`ic_fullscreen*.xml`、`ic_floating_transcript.xml`、`bg_transcript_offset_button.xml`；`ui/transcript/src/main/res/drawable/`：`bg_floating_control.xml`、`ic_transcript_{close,lock,unlock}.xml` |

### 其他
| 文件 | 职责 |
|---|---|
| [UserPreferences.java](file:///D:/AntennaPod/storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/UserPreferences.java) | 所有转录偏好（L129-L141 keys，L935-L1034 getters/setters） |
| [DBWriter.java](file:///D:/AntennaPod/storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java) | 删除媒体/条目时清理转录文件与偏移(L121/L133/L217) |
| [FeedListUpdateEvent.java](file:///D:/AntennaPod/event/src/main/java/de/danoeh/antennapod/event/FeedListUpdateEvent.java) | `contains(Feed)` / `contains(long)` |
| [strings.xml](file:///D:/AntennaPod/ui/i18n/src/main/res/values/strings.xml) | `transcript_*`、`pref_floating_transcript_*`、`show_transcript`、`no_transcript_label` |
| DB 列 | `FeedItems.podcastindex_transcript_url`、`FeedItems.podcastindex_transcript_type`（**不在 FeedMedia**） |

## 4. 偏好键与默认值

| key | 类型/默认 | 含义 |
|---|---|---|
| `prefFloatingTranscript` | bool/false | 悬浮窗开关 |
| `prefFloatingTranscriptXDp/YDp` | int/-1 | 悬浮窗位置 |
| `prefFloatingTranscriptLocked` | bool/false | 是否锁定（禁拖） |
| `prefTranscriptLanguageMode` | int/0 | 0 双语、1 仅原文、2 仅译文 |
| `prefTranscriptSentencePerLine` | bool/true | 逐句成行 |
| `prefTranscriptFontSize` | int/0 | 字号步进 |
| `prefTranscriptTimeOffsets` | string JSON | mediaId → 偏移毫秒（当前实现） |
| `prefTranscriptTimeOffset` + `prefTranscriptTimeOffsetMedia` | long | 旧单槽值，读取时迁移到上表 |

## 5. 测试

### 5.1 单元测试

按 `AGENTS.md`：`./gradlew --console=plain` + 模块的 `:test` 任务。

| 模块 | 测试文件 | 覆盖点 |
|---|---|---|
| `:model` | [TranscriptTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptTest.java)、[TranscriptSegmentTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/TranscriptSegmentTest.java)、[FeedItemTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedItemTest.java)、[FeedMediaTest](file:///D:/AntennaPod/model/src/test/java/de/danoeh/antennapod/model/feed/FeedMediaTest.java) | 乱序插入、append 单调、来源变更失效、序列化忽略 transcript、三文件清理 |
| `:parser:transcript` | [VttTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/VttTranscriptParserTest.java)、[SrtTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/SrtTranscriptParserTest.java)、[JsonTranscriptParserTest](file:///D:/AntennaPod/parser/transcript/src/test/java/de/danoeh/antennapod/parser/transcript/JsonTranscriptParserTest.java) | 双语保留译文、非双语不误判、数字句末拆分、null speaker、非法时间、乱序 |
| `:ui:transcript` | [TranscriptUtilsTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptUtilsTest.java)、[TranscriptFloatingWindowServiceTest](file:///D:/AntennaPod/ui/transcript/src/test/java/de/danoeh/antennapod/ui/transcript/TranscriptFloatingWindowServiceTest.java) | sidecar 写入/收编/过期、5MB、损坏回退 URL、强刷回退、原子写、悬浮窗过期请求竞态（可控调度队列 + `RxJavaPlugins.reset()`） |
| `:storage:preferences` | [UserPreferencesTest](file:///D:/AntennaPod/storage/preferences/src/test/java/de/danoeh/antennapod/storage/preferences/UserPreferencesTest.java) | 按媒体偏移、删除、旧值迁移 |
| `:app` | [TranscriptTextUtilsTest](file:///D:/AntennaPod/app/src/test/java/de/danoeh/antennapod/ui/screen/playback/TranscriptTextUtilsTest.java) | 数字句末 chunkStarts、sentenceEndTime |

注意：`TranscriptFloatingWindowServiceTest` 里 Mockito 静态 mock 只对创建它的线程可见，跨 IO 线程的竞态必须用同一可控调度器（`Schedulers.from(executor)` + `trampoline`）复现，否则测试会假通过。

### 5.2 真机自动验证脚本

[scripts/transcript_device_test.sh](file:///D:/AntennaPod/scripts/transcript_device_test.sh)（Git Bash 运行，从仓库根目录调用）。

```bash
bash scripts/transcript_device_test.sh \
  --vtt-dir "D:/MyTranscript/site/the-rest-is-history/transcripts/zh" \
  --vtt-file "1._Greatness.vtt" \
  --media-id 1 --position 5000
```

执行内容（任一步失败即恢复设备后退出；`INT`/`TERM` 也触发恢复）：

1. `preflight`：设备、包、`run-as`、VTT 文件存在性。
2. `snapshot_state`：记录现有 `adb reverse`、`wifi_on`、`mobile_data`。
3. `backup_device`：`force-stop` 后用 `adb exec-out` 备份主库 + 实际存在的 `-journal/-wal/-shm` + 偏好；`integrity_check`；若未给 `--media-id` 则从偏好的 `currentlyPlayingMedia` 推断。
4. `prepare_db`：复制备份，改 `FeedItems.podcastindex_transcript_url/type` 指向 `http://127.0.0.1:<port>/<vtt>`，可选重置 `FeedMedia.position`，打印 before/after。
5. `start_server`：后台 `python -m http.server --bind 127.0.0.1`，本机自测可访问，再 `adb reverse`（若测试前已有同端口转发则不重复添加、结束时也不删除）；`--isolate-network` 时关闭 Wi-Fi/移动数据。
6. `push_db`：`adb exec-in run-as ... "cat > databases/Antennapod.db"` 写入（**不能**用 `adb shell ... >`，会产出 malformed 镜像），删除遗留侧车文件，回读校验 URL。
7. `install_app`：`gradlew -p <repo> --console=plain :app:installPlayDebug`（`--skip-install` 跳过）。
8. `launch_page`：`am start ... --ez open_player true --ez open_transcript true` 冷启动一次（只负责恢复播放上下文），`KEYCODE_MEDIA_PLAY` 起播，再 `am start` 同一 intent 走 `onNewIntent` 才会真正展开抽屉并切到转录页；随后抓 `screencap`、`uiautomator dump`、`logcat -d`。
9. `check_page`：无 `FATAL EXCEPTION`；dump 中至少 1 个 `:id/content` 行（转录行专属 id，别用 `recyclerView`/`android:id/content`，首页也有）；至少 1 行同时含拉丁与 CJK 文本（双语断言）；日志有 `Done Downloading transcript`。
10. `check_floating`：检查/尝试授予 `SYSTEM_ALERT_WINDOW`；用 `dumpsys activity services` 判断 `TranscriptFloatingWindowService` 是否在跑（悬浮窗是独立 window，`uiautomator dump` 看不到它，只能靠截图人工核对）；未运行时才走溢出菜单 → “悬浮窗transcript/Floating transcript” 的点击路径，再截图。
11. `restore_device`：还原数据库（含侧车）与偏好、确认无 `127.0.0.1` 残留、按需移除端口转发、停掉本地服务、按原值恢复网络开关、重启应用。
12. 产物：`~/.qoder-cn/tmp/transcript-ondevice/run-<时间戳>/`（`results.txt`、`01_transcript_page.png`、`04_floating.png`、`transcript.log`、各阶段 UI dump 与数据库副本）。

其他用法：
- 只恢复：`bash scripts/transcript_device_test.sh --restore-only --work <目录>`。
- 保留现场继续手测：`--keep`（脚本会打印后续恢复命令）。
- 多设备：`--serial <序列号>`。

2026-10-24 在代码基线 `7e2b4e5b9` 上、Redmi 2407FRK8EC（`4LSGLZEYOFCQBIQC`）的完整跑通结果：11/11 PASS，`restored integrity: ok`、`test urls left: 0`、偏好与备份逐字节一致。截图里可见 `1._Greatness.vtt` 第 1 个 cue（00:00:00–00:00:49）拆出的逐句双语行、当前句蓝色高亮，以及悬浮窗对同一句的滚动显示。

### 5.3 语料基线（人工比对用）

真实双语语料：`D:\MyTranscript\site\the-rest-is-history\transcripts\zh\`（远端镜像 `https://lzoreal.github.io/TMLT/transcripts/zh/`）。
`1._Greatness.vtt` 共 62 个 cue，每个 cue 两行（拉丁英文 + CJK 中文）；数字句末修复后应得到 **316** 行逐句结果（旧基线 313）。合成 fixture 不足以证明双语渲染正确，需用该语料在真机核对。

### 5.4 手工验收清单

- [ ] 转录页逐句双语行、当前句加粗高亮、其余半透明
- [ ] 语言三态循环（双语 / 仅原文 / 仅译文）与逐句换行开关
- [ ] 字号放大/缩小后高亮行同步
- [ ] 点击句子从该句起点播到该句终点；单句循环可开关
- [ ] 偏移粗调/细调/重置，切集后再切回仍保留；删除单集后偏移消失
- [ ] 手动滚动暂停跟随，“回到当前句”恢复；首段也能滚动到位
- [ ] feed 刷新后（转录 URL 变化）页面/弹窗/悬浮窗/菜单自动更新
- [ ] 悬浮窗只显示当前句、双语间距、暂停不滚动、锁定/关闭 4s 自动隐藏、拖动吸附、点击回转录页
- [ ] 断网 + 已有本地文件时可离线显示；本地文件损坏时能重新下载

## 6. 已知坑速查

| 现象 | 原因 | 处理 |
|---|---|---|
| `database disk image is malformed` | 用 `adb shell ... > databases/Antennapod.db` 写库 | 用 `adb exec-in run-as ... sh -c "cat > ..."` 并回读 `integrity_check` |
| `cat: C:/Program: No such file...` | Git Bash 转换了 `/sdcard/...` | 命令前加 `MSYS_NO_PATHCONV=1` |
| 启动后停在首页 | `-e open_player true` 是字符串 extra | 用 `--ez` |
| 传了 `--ez` 仍停在首页 | 同时传 `episode_id` 会优先走单集页 | 打开播放器时不要传 |
| 冷启动时 `--ez` 不生效 | 播放器还没 attach，底部抽屉处于锁定态，`setState(EXPANDED)` 被忽略 | 等首启完成后再发一次同一 intent，走 `onNewIntent` |
| `reverse: inaccessible or not found` | `adb reverse` 是主机侧命令，被塞进了 `adb shell` | 直接 `adb reverse`，不要经 `adb shell` |
| `Can't start from invalid source` | 悬浮窗服务 `exported=false` | 只能从播放器溢出菜单开启 |
| `no such column: transcript_url` | 列在 `FeedItems` 不在 `FeedMedia` | 用 `podcastindex_transcript_url/_type` |
| 日志有 `Unsatisfiable Request (only-if-cached)` | 正常缓存回退 | 看后续 `Done Downloading` |
| 悬浮窗不出现 | 无悬浮窗权限 / 未从菜单开 / 当前集无转录 | 查 `appops`、菜单勾选、DB 中 URL |
| UI dump 里找不到 `tv_floating_transcript` | 悬浮窗是独立 window，`uiautomator dump` 只导出活动窗口 | 用 `dumpsys activity services` 判断服务 + 截图人工核对 |
| 流媒体播放失败 | 测试机 DNS 被污染（环境问题，非 App bug） | 用本地 VTT + `adb reverse`，或 `--isolate-network` |
| 播放器加载的不是目标单集 | 转录页打开 `currentlyPlayingMedia` | 先在 UI 播放该集，或用脚本推断的 media id |

## 7. 已知限制 / 未完成

- 没有转录搜索/过滤。
- SRT：首行散文中的冒号（如 `Note: ...`）仍可能被当成发言人。
- 悬浮窗/页面在 fragment 停止期间发生外部 seek 时，要等下一个 `PlaybackPositionEvent` 才刷新。
- 悬浮窗不显示发言人（用户要求移除）；`TranscriptSegment.speaker` 仍供转录页使用。
- 音频转录页的“跟随音频”开关与视频弹窗的其他增强：用户明确表示**暂不做**（“那不用管这个功能”），未被再次要求前不要实现或重提。
- `checkstyle`/`lint` 在 transcript 相关模块存在**既有**违规（未格式化历史代码），按 `AGENTS.md`「不修自己改动之外的告警」保持原样；只在被要求时跑 `./gradlew checkstyle lint`。

## 8. 工作约定（务必先读 [AGENTS.md](file:///D:/AntennaPod/AGENTS.md)）

- 最小 diff：不改无关代码、不重命名、不重排、默认不加注释；新增用户可见字符串只写 `ui/i18n/src/main/res/values/strings.xml`，**不动** `values-*/`。
- 类不要写全限定名，用 import。
- 先 `./gradlew :app:assembleDebug` 确认无编译错误，再跑测试/真机；**不要过滤编译输出**，不要自编编译命令。
- 安装并启动：`./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1`。
- 不在 `develop`/`master` 直接提交，新分支提交；PR 前读 `.github/pull_request_template.md`，描述 2-8 句、`Closes: #<n>`，不写测试指引，创建后不再改描述；不要每个 review comment 都回复，留一条汇总。
- 提交/推送/建 PR 只在用户明确要求时做。
- 相关记忆（`C:\Users\游梦\.qoder-cn\projects\D--AntennaPod\memory\`）：`transcript-feature-initiative.md`（设计决策与限制）、`transcript-test-corpus.md`（语料基线）、`checkstyle-transcript-baseline.md`、`test-device-network.md`。
