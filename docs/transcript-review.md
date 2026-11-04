# 转录功能评审：性能 / 代码 / UI / UX

> 基线：`d5d01399b`（2026-11-05，分支 `transcript-asr-word-timestamps`）
> 范围：解析与数据模型、整屏转录页与跟随、Adapter 与渲染、本地缓存与悬浮窗/视频弹窗、生成转录（ASR）、广告检测-跳过-字幕重对齐、设置/通知/i18n、设备测试脚本（仅在影响验证时）。
> 方法：五路只读评审 + 主审对全部 P0/P1 关键证据逐行复核；**本轮未改任何代码、未跑基准与真机**。
> 评级：**P0** 正确性/数据丢失/崩溃；**P1** 可量化性能或高频用户可感知的功能缺陷；**P2** 代码结构/一致性；**P3** 细节与风格。
> 计数：P0 × 3、P1 × 32、P2 × 20、P3 × 12。

---

## 0. 结论摘要

转录功能的分层总体健康：解析、纯逻辑（覆盖率/规划/分词/搜索/跟随曲线）都有单测，主线程没有网络与磁盘 IO，模块边界基本守住。真正的问题集中在五条线上：

1. **两个不可逆/静默改数据的交互**（P0-1、P0-2）：点选对齐模式会在关闭面板后继续生效；长按「对齐到本句」会无确认地清空整集所有逐段锚点。
2. **ASR 生成链路的结构性写放大与双写**（P0-3、P1-22、P1-23）：每定稿一段就全量重写整份 word-JSON；实时会话与后台 Worker 没有进程级互斥，可产生“已覆盖但无文字”的永久缺口。
3. **高频路径重复劳动**（P1-7、P1-13、P1-14、P1-15）：100/250ms tick 每个周期重复同步位置、重建整行 Span；bind 每行写两次文本；搜索每个按键全表重建并 `notifyDataSetChanged`。
4. **产物语义不统一**（P1-18、P1-25、P2-4）：官方转录与生成转录在“是否落盘、是否吃偏移、何时被遮蔽”上各有各的规则，导致离线失效、官方字幕晚到不生效、生成转录首帧错位。
5. **状态不可见**（P1-27、P1-28、P1-32）：控制条默认隐藏、所有失败态退化成同一句“无声音文字”、开关只用 alpha 表示，用户无法判断“当前是什么模式、为什么没有内容、下一步做什么”。

若只做五件事：修 P0-1/P0-2，修 P1-6（关闭搜索恢复跟随），把 P1-13/P1-14/P1-15 合并成一次渲染层重构，给 P1-18 补上落盘与并发合并，给 P1-22/P0-3 设计增量落盘。对应 ADR 见 §8。

---

## 1. P0 — 正确性、数据丢失、崩溃

### P0-1 点选对齐模式在关闭偏移面板后仍然生效，点击句子会静默改数据

- **证据**：`app/.../TranscriptPageFragment.java:1844-1854`（`toggleOffsetBar()` 只切面板可见性，不调用 `setSyncPickMode(false)`）；`:1178-1181`（`syncPickMode` 为真时，点击句子进入写偏移分支并 `return`，不再 seek）；`:2057-2058`（只有 `hideControlPanels()` 会退出点选模式）。
- **根因**：点选模式是独立于面板可见性的一个布尔状态，面板只是它的入口之一；退出路径只覆盖“收起一级控制条/进入沉浸/切集/成功选句”。
- **影响**：用户点顶部偏移图标收起面板后，以为回到正常浏览；下一次点击句子不会跳播，而是以当时播放位置为锚写入偏移，且只影响该句之后——数据被静默改写，用户往往到“高亮对不上”时才发现。
- **建议**：把“点选模式”与“偏移面板可见”绑定为一个状态：面板关闭即退出；点选期间在列表顶部显示常驻横条「点选正在播放的那一句 · 退出」，点击横条或返回键退出；写入成功后给出可撤销 Snackbar。状态机抽成 `TranscriptOffsetController`，用单测覆盖“面板关→模式关”“切集→模式关”“成功→模式关”。
- **验证**：单测覆盖四条退出路径；真机脚本增加“进入点选→收起面板→点击句子，断言未写偏好且发生 seek”。

### P0-2 长按「对齐到本句」无确认地清空整集所有逐段锚点

- **证据**：`TranscriptPageFragment.java:1806-1809`（`setOnLongClickListener` 直接 `alignFirstSentenceToNow()`）；`storage/preferences/.../UserPreferences.java:995-999`（`setTranscriptStartOffset` 新建空 `TranscriptTimeOffsets` 再写入，旧锚点全部丢弃）。
- **根因**：长按被当作“整集对齐”的快捷手势，但它是破坏性操作，既不确认也不可撤销；在点选模式里按住按钮想读文案，也会命中长按。
- **影响**：用户可能花十几分钟逐段校准（片头广告、中段广告、局部漂移），一次误长按全部清零；`§7` 明确记录了设备上曾残留多个实验锚点，说明这种状态真实存在。
- **建议**：二选一——(a) 移到偏移面板的溢出菜单，弹确认框并说明“将删除现有 N 个逐段校准”；(b) 仅当该集没有任何锚点时允许长按生效，否则提示改用点选。无论哪种，写入前保存一份可撤销快照，Snackbar「已整集对齐 · 撤销」恢复原锚点表。同时给按钮加长按提示（长按前 300ms 显示 tooltip 或首次长按弹说明）。
- **验证**：单测覆盖“有锚点时长按不生效/确认后替换/撤销恢复”；真机 offset 场景新增该断言。

### P0-3 生成转录：实时会话与收尾 Worker 可同时写同一文件，产生永久缺口

- **证据**：`app/.../AsrGenerationManager.java:64-78`（`start()` 只处理“已有实时会话”，不检查 Worker）；`app/.../AsrFinalizeWorker.java:48-58`（只在启动时检查 `isRunning`，之后不再校验）；两者都通过 `AsrTranscriptStore.save()` → `writeAtomically()` 整文件替换（`asr/.../AsrTranscriptStore.java:31-35,71-93`），各自持有内存快照与独立 coverage。
- **根因**：互斥是“轮询式”的，不是进程内按 mediaId 的互斥；两个写者都按自己的 pipeline 推导 coverage，后写者的 segments 可能更少、coverage 却更靠后。
- **影响**：产生“coverage 显示已覆盖、文件里没有文字”的永久缺口，后续续跑会跳过该区间；重叠期还会各自加载一份 163 MB 模型（PSS 670–720 MB → 约 1.3 GB），有 LMK 风险。
- **建议**：进程内 `mediaId → SessionLease` 互斥（实时会话与 Worker 共享），拿不到锁的 Worker 直接 `Result.retry()`；coverage 唯一来源改为“已落盘 segments 的并集”，不从 pipeline 推；写入前比对磁盘 mtime/段数，拒绝旧快照覆盖新快照。中期见 ADR 0003（增量落盘）。
- **验证**：单测模拟两写者交错，断言不会出现“coverage 含无文字区间”；真机强杀进程后由 Worker 续跑，比对段数与 coverage 一致。

---

## 2. P1 — 可量化性能与高频功能缺陷

### 2.1 解析与数据模型

#### P1-1 VTT 每个含 `<` 或 `&` 的行都会新建一个 Jsoup 文档

- **证据**：`parser/transcript/.../VttTranscriptParser.java:107-109`，`payloadLine.indexOf('<') < 0 && indexOf('&') < 0 ? payloadLine : Jsoup.parse(payloadLine).text()`。
- **影响**：`<i>…</i>`、`&amp;` 在播客字幕中很常见；每命中一行就构造一次 `Parser` + `Document` 树。5 MB / 约 2 万行语料意味着最多 2 万次对象树构建（量级以 §6 基准为准），解析在主线程之外，但会显著延长首次打开与每次强制刷新的时间，并制造大量垃圾。
- **建议**：手写一次扫描剥离标签并解码 5 个实体（`amp/lt/gt/quot/nbsp`），或复用一个 `Parser.parseFragment()`；对无标签行保持零分配快路径。补一条 2 万行合成语料的基准用例。
- **验证**：基准显示 5 MB VTT 解析时间与分配量下降一个量级；现有 `testBilingualWithVoiceAndMarkup` 等语义测试不变。

#### P1-2 同一份文本解析 2–3 遍

- **证据**：`ui/transcript/.../TranscriptUtils.java:283-290`（`storeTranscript` 为校验完整性调用 `parseTranscript` 后丢弃结果）；`:262-272`（真正加载时再 parse 一次）；`:274-280`（`hasBilingualSegments` 第三次遍历/派生）。
- **影响**：每次落盘/强制刷新都多花 1–2 次整份解析的 CPU 与 GC；对 8831 词的 JSON 或 5 MB VTT 是秒级开销的成倍放大。
- **建议**：`storeTranscript` 接收已解析对象或返回解析结果，走同一条 `cacheKey` 复用；`hasBilingualSegments` 在解析期一次性算出并随 `Transcript` 返回，不再二次遍历。
- **验证**：基准中“下载→校验→落盘→首屏”只解析一次；偏好/缓存行为不变。

#### P1-3 5 MB 转录在内存中至少三份拷贝，JSON 还建整棵树

- **证据**：`TranscriptUtils.java:46,59,67,82`（`StringBuilder` 预缓冲 + `readString` + `append` + `toString`）；`parser/transcript/.../JsonTranscriptParser.java:40`（`new JSONObject` 建全树，每个词是一个 `JSONArray`）。
- **影响**：UTF-16 下字符串峰值约 3×10 MB；8831 词 ≈ 8831 个 `JSONArray`，树本身 10–30 MB。IO 线程不卡 UI，但低端机在 ASR 常驻模型（PSS 670–720 MB）时叠加，存在 OOM 风险。
- **建议**：网络层直接 `byte[]` 读一次，按 charset 解码一次（删掉 `StringBuilder` 中间层）；JSON 改用 `JsonReader` 按 `segments` 流式消费，词级数组边读边建 `TranscriptWord`；解析完只保留 `Transcript`，不保留原始字符串。
- **验证**：§6 基准记录峰值 heap 与 PSS，目标 ≤ 2× 文件大小。

#### P1-4 一条坏数据毁掉整份转录（含 BOM 与 SRT 分隔符）

- **证据**：`JsonTranscriptParser.java:58,131,160`（任一段 `getJSONObject(i)` 抛异常即整份返回 null）；`SrtTranscriptParser.java:20`（时间码只认 `,`，`.` 分隔的 SRT 整份失败）；`TranscriptUtils.java:147`（`readFileToString` 不去 UTF-8 BOM），`:264-266`（无 type 时用 `text.trim().startsWith("{")` 嗅探，BOM 存在则失败）。
- **影响**：一条畸形段/一个 BOM 就让用户看到“无声音文字”，并且本地文件会被判为损坏后反复重下；错误只进日志。
- **建议**：逐段 `try/catch`，跳过坏段并记录计数；时间码正则 `[.,]`；读取时剥离 `\uFEFF`；嗅探前先 strip BOM；`printStackTrace()` 改为 `Log`。解析结果携带 `skippedSegments`，UI 可提示“已跳过 N 条无法解析的段落”。
- **验证**：新增“中间一条坏段”“BOM + JSON”“点号 SRT”三个单测。

#### P1-5 `audio_seconds` 没有合理性上限，单位错误会让整集错位

- **证据**：`JsonTranscriptParser.java:212-218` 只判 `> 0`；消费侧 `TranscriptPageFragment.java:1711-1718` 只判 `>= 0.0002`，无上限。
- **影响**：发布方把毫秒写成秒（或反之）时，比例校正会把整集高亮/seek/偏移成倍拉偏；844 已验证 +0.23% 就足以在 50 分钟处差 8 秒。
- **建议**：`audio_seconds` 限制在 `[0.9, 1.1] × 播放器时长` 或与末段 `endTime` 交叉校验，超出即丢弃并回退到 MP3 帧扫描；解析层记录被拒绝的原因。
- **验证**：单测覆盖 10×、0.1×、末段远大于 audio_seconds 三种异常；真机 844 仍按 3432.05 s 生效。

### 2.2 整屏页与跟随

#### P1-6 搜索定位后关闭搜索，跟随不会恢复，直到用户点击/调偏移/拖动/切集

- **证据**：`:1342-1353`（`jumpToSearchHit` 置 `userScrolling = true`、停跟随帧）；`:1277-1293`（`closeSearch` 只清查询、清搜索高亮、隐藏搜索条，不复位 `userScrolling`，不重新调度跟随帧，也不回位）；复位点只有 `:321`（拖拽 idle）、`:620`、`:666`、`:1200/:1208`、`:1762`。
- **影响**：关闭搜索后高亮继续移动但列表不动，「回到当前句」常驻，用户会认为跟随功能坏了；`refreshSearchAfterDisplayChange()` 每次切语言/逐句还会再次触发。
- **建议**：`closeSearch` 内复位 `userScrolling=false`、更新「回到当前句」可见性，并按当前高亮行 `scrollToPosition`/`scheduleFollowFrame`；把“搜索定位暂停跟随”建模成显式枚举（`FOLLOW / MANUAL / SEARCH_PAUSED`），退出搜索即回到 `FOLLOW`（若用户此前是手动模式则保持）。
- **验证**：单测覆盖状态机；真机 search 场景新增“关闭搜索后 3 秒内列表随高亮移动”。

#### P1-7 每个 tick 重复同步位置；暂停时 ticker 仍空转

- **证据**：`:204-241`（ticker 内 `adapter.updatePlaybackPosition(position)` 后立刻 `handlePlaybackPosition(position)`）；`:810-820`（`handlePlaybackPosition` 再次调用 `adapter.updatePlaybackPosition`）；`:238-239`（无论是否播放都 `scheduleFollowFrame()` 并 `postDelayed`）。
- **影响**：词级 10 Hz、句级 4 Hz 下高亮计算/绑定翻倍；暂停时仍以 4 Hz 唤醒主线程并注册/移除 `OnPreDraw`，静止画面持续耗电。
- **建议**：位置同步只保留一条路径（建议 ticker → 单一 `onPosition` 入口）；暂停且目标行未变化时停 ticker，仅保留 `PlaybackPositionEvent` 与恢复播放时重启；`scheduleFollowFrame` 只在“目标/误差/布局”变化时注册。
- **验证**：Perfetto 统计 paused 状态 30 s 的主线程唤醒次数与 `OnPreDraw` 注册次数，目标接近 0。

#### P1-8 阅读线用 root 高度、末项占位用列表高度，二者基准不一致

- **证据**：`:1057-1064`（`rootView.height * ANCHOR_RATIO` 再减列表屏幕 Y）；`:1936-1938`（`recyclerView.height * (1 - ANCHOR_RATIO)`）；`ANCHOR_RATIO = 0.40`。
- **影响**：`controlsBar`（48dp）、搜索条、偏移/排版面板存在时，root 与列表高度/位置不同，锚点与底部占位相差几十 dp；末段永远差一截到不了阅读线，跟随看起来“卡在底部”。
- **建议**：统一由 `TranscriptFollowMotion.readingLine(recyclerView)` 输出，以列表可见区为基准；末项占位用同一函数反算。把“控制条/面板可见性”作为输入参数，避免隐式依赖 root。
- **验证**：单测给定 root/list 尺寸与面板高度，断言锚点与占位互补；真机在控制条展开/收起两种状态各录一次末段跟随。

#### P1-9 单段循环在越过终点后每个 tick 都发起一次 seek

- **证据**：`:1543-1546`（`position >= endTime` 时直接 `bindToMedia3Service(...seekTo...)`，无 pending 标志、无冷却）。
- **影响**：seek 尚未生效的 100–250 ms 内会重复 bind + seek，制造抖动与日志噪声；Media3 的异步 seek 在慢设备上可能持续多个 tick。
- **建议**：加 `loopSeekPending` 与 300 ms 冷却，位置回到 `startTime` 附近或收到 seek 完成回调后清除；与 `TranscriptFollowController` 的“目标变化”共享同一状态。
- **验证**：单测模拟 5 个 tick 仍越过终点，断言只发一次 seek。

#### P1-10 目标行未布局时用 `setAdapter(null)+setAdapter(adapter)` 全量重绑

- **证据**：`:962-968`、`:1092-1109`（`recoverStaleLayout`，仅 1 s 节流）。
- **影响**：500 段时每次约全表重绑；跟随失败场景下每秒一次，恰好发生在 ASR 批量写段/高度抖动时，造成明显掉帧与闪烁。
- **建议**：先 `notifyItemRangeChanged`/`invalidateItemDecorations`/`requestLayout` 组合恢复 position；全量重挂作为最后手段并加上次数上限与退避；根因（见 P2-15）修掉后应可删除该路径。
- **验证**：日志统计 `recover stale layout` 次数，目标为 0；ASR 连续 60 s 录屏无整表闪烁。

#### P1-11 每个 tick 多次读取偏好并重算偏移

- **证据**：`:826`（`transcriptOffsetAt`）、`:1760`、`:1780`、`:1792-1797`；`applyOffsetChange` 还额外做全表 `findSegmentIndexBefore`。
- **影响**：10 Hz 下每 tick 2–4 次偏好读取；若内部按锚点解析 JSON 则更重，且与 `updatePlaybackPosition` 内的偏移读取重复。
- **建议**：缓存“当前段 → 偏移”和“最后一个锚点”，只在段索引/锚点版本变化时重算；把偏移查询并入 `updatePlaybackPosition` 的一次计算，返回值给 UI 复用。
- **验证**：`TranscriptTimeOffsets` 基准/计数测试，10 Hz × 60 s 的调用次数下降到每段一次。

#### P1-12 搜索定位的 `OnPreDraw` 监听不随 `onPause` 移除

- **证据**：`:1422`（注册 `searchScrollListener`）；`:1426-1437`（只在成功定位/尝试超限时移除）；`:427-434`（`onPause` 只移除 ticker 与搜索防抖，未移除该监听）。
- **影响**：切页/退后台再回来时，监听仍可能存活并按下一次布局滚动到旧 `searchScrollPosition`（最多 20 帧 `postInvalidateOnAnimation`）。
- **建议**：`onPause`/`onStopView` 调 `stopSearchScrollFrame`；监听首行判断页面可见性；搜索滚动也纳入统一的状态机。
- **验证**：Robolectric 模拟 `onPause` 后触发 pre-draw，断言不再滚动。

### 2.3 Adapter 与渲染

#### P1-13 词级高亮每个 tick 重建整行文本与全部词的 Span

- **证据**：`TranscriptAdapter.java:505-527`（每个刷新都 `new SpannableString(info.text)`，为段内**每个词**新建 `ForegroundColorSpan`，当前词再建 `FakeBoldSpan`，最后 `setContentText` → `setText`）。
- **影响**：一个 1000 词的 cue 每 tick 新建约 1000 个 Span 与一份完整 `SpannableString`；词级 10 Hz 即每秒万级对象，并触发整行 measure/layout。长 cue（715 语料单 cue 数十秒）是最坏情况。
- **建议**：ViewHolder 持有一个 `SpannableStringBuilder`，bind 时只写一次；tick 只对“离开的词/进入的词”做 `removeSpan/setSpan`，其余 Span 复用并缓存；必要时用自定义 `CharacterStyle` 数组按区间切换，而不是逐词一个对象。配合 P1-7 消除重复 tick。
- **验证**：Robolectric/宏基准统计每 tick 的 Span 分配数与 `setText` 次数，目标 O(1)；真机 Perfetto 看词级播放时 `Choreographer` 掉帧。

#### P1-14 每次 bind 写两次文本

- **证据**：`TranscriptAdapter.java:240-274`（三个分支内各调用一次 `setContentText`），随后 `:273-274` `highlightViewHolder` + `bindText`，`bindText` 在非高亮/高亮分支再次 `setContentText`（`:299-309`）。
- **影响**：一屏 15 行 = 30 次 `setText`/行测量；首屏与滚动时额外一帧工作量。
- **建议**：bind 只计算一次 `DisplayInfo` 与最终可见状态，末尾统一写一次 `setText`；`highlightViewHolder` 的 alpha/typeface 也并入同一次视觉状态写入（见 P2-7）。
- **验证**：统计一次 bind 的 `setText` 次数，目标 1。

#### P1-15 搜索每个按键全表重建，且用 `notifyDataSetChanged` 全量重绑

- **证据**：`TranscriptAdapter.java:826-831`（`setSearchQuery` → `rebuildSearchHits` → `notifyDataSetChanged`）；`:900-915`（遍历全部 segment，对每段 `getDisplayInfo`）；`:800-824`（每行 `applySearchHighlights` 再遍历全部命中）。
- **影响**：2000 段、数千命中时，每次按键重建数千 `DisplayInfo`（`displayCache` 仅 64 条，被整体冲刷）、数万 `SearchHit`，并全表重绑；200 ms 防抖只能减少次数，不能降低单次成本。
- **建议**：只在可见段上做高亮；命中索引按 position 建桶（`Map<Integer, List<Hit>>`），`notifyItemRangeChanged(position, 1, PAYLOAD_SEARCH)` 只刷命中行；`DisplayInfo` 缓存改为按 transcript 全量/更大容量；搜索匹配只做子串扫描，不构建显示文本（与 P2-2 的分层一起做）。
- **验证**：2000 段语料基准，单次按键 ≤ 50 ms；`notifyDataSetChanged` 调用次数为 0（搜索路径）。

#### P1-16 点击与无障碍 seek 复用 Adapter 级旧坐标，TalkBack/performClick 恒回第一句

- **证据**：`TranscriptAdapter.java:172-178`（`lastTouchX/lastTouchY` 是 Adapter 字段，只在真实 `ACTION_DOWN` 更新）；`:179-186`（`onClick` 用 `sentenceTimesAtTouch`）；`:703-709`（该方法直接读 `lastTouchX/lastTouchY`）。
- **影响**：TalkBack `performClick`、键盘/遥控器、或上一行残留坐标都会用旧值计算，轻则跳到错误句子，重则恒回第一句；这正是“点击跳转偶发不准”的来源之一。
- **建议**：坐标存到 ViewHolder（`onTouch` 时写入该 holder），`onClick` 无坐标时回退到段起点；为词/句增加 `AccessibilityAction`（“从此句播放”“从此词播放”），不再依赖坐标。
- **验证**：Robolectric 触发 `performClick` 无触摸事件，断言跳到段起点；TalkBack 手工验收。

#### P1-17 词级 tick 会清掉用户的长按选词

- **证据**：`TranscriptAdapter.java:505-527` + `:796-798`（每 tick `setText` 新 `CharSequence`）；`TranscriptTextView` 的选择区间依附于文本。
- **影响**：用户长按选词后，下一个 100 ms tick 就把选区清空，实际几乎无法完成选词/复制。
- **建议**：`hasSelection()` 时跳过该行刷新（或只更新未选中区间）；更彻底的做法是 P1-13 的“原地改 Span”，不替换 CharSequence。选词期间临时停跟随帧。
- **验证**：真机长按保持 2 s，选区不消失；复制内容正确。

### 2.4 缓存与悬浮窗

#### P1-18 普通加载路径从不落盘，离线与跨进程缓存形同虚设

- **证据**：`ui/transcript/.../TranscriptUtils.java:123-130`（`storeLocal = staleLocalTranscript || (forceRefresh && getTranscriptFileUrl() != null)`）；生产调用点 `TranscriptPageFragment.java:568`、`TranscriptDialogFragment.java:197`、`TranscriptFloatingWindowService.java:409` 均传 `false`；只有 `net/download/.../MediaDownloadedHandler.java:68-72` 在媒体下载完成时落盘。
- **影响**：流式集、或“下载完成后才第一次打开转录页”的集，只在内存里缓存（进程重启即失）；离线时如果没有 `.transcript` 就无内容可显示；每次冷启动重新下载 ≤ 5 MB。
- **建议**：只要 `getTranscriptFileUrl() != null` 就落盘（首次成功解析后），失败不影响本次显示；保留 `forceRefresh` 覆盖旧文件的语义。补一条“加载一次后文件存在且 `.source` 正确”的单测。
- **验证**：单测断言普通加载后三件套存在；真机断网重开转录页仍可见。

#### P1-19 同一 URL 并发加载无合并、写入无锁，可能写出损坏缓存

- **证据**：`TranscriptUtils.java:85-135`（两路各自执行 `FORCE_CACHE`→`FORCE_NETWORK`→parse→store）；`:292-317`（两路同时打开同一个 `.tmp` 写入）。
- **影响**：播放服务自动起悬浮窗与转录页加载经常同时发生：2×5 MB 流量、2×解析、峰值内存翻倍；两个 `FileOutputStream` 截断同一 `.tmp` 可产生损坏文件，下一轮被判不可解析再重下。
- **建议**：按 `cacheKey` 做 single-flight（`ConcurrentHashMap<key, Future>`），后到者共享结果；`storeTranscript` 按 mediaId 加锁并使用唯一临时文件（`createTempFile`）+ rename；sidecar 同样原子写。
- **验证**：并发单测（两个线程同时加载）断言只下载一次、文件可解析；真机冷启动抓日志确认仅一次 `Downloading transcript URL`。

#### P1-20 悬浮窗按屏幕刷新率满帧重绘，且每帧画两遍

- **证据**：`ui/transcript/.../ScrollTextView.java:99-121`（`ValueAnimator` 无帧率门控，每帧 `setScrollX`）；`:138-161`（`onDraw` 先 STROKE 描边再 FILL 填充，两次 `super.onDraw`）。
- **影响**：120 Hz 屏每秒 120 次整窗合成；300 px/s 时每帧仅移动 2.5 px，30 fps 视觉等价。overlay 窗口每帧还要跨进程合成，是悬浮窗外放时的主要耗电项之一。
- **建议**：自建 30 fps 的 `postOnAnimation` 门控（或 `scrollX` 未变即跳过）；描边与填充合并为一次绘制（用 `Paint.setStyle(STROKE_AND_FILL)` 或预生成 shader）；`ValueAnimator` 改为 `ObjectAnimator` + `setFrameDelay` 也行，但要显式限帧。
- **验证**：真机 Perfetto 统计悬浮窗播放 60 s 的 `onDraw` 次数与 GPU 帧数，目标 ≤ 30 fps。

#### P1-21 悬浮窗息屏/不可见时 100 ms 定时器不停

- **证据**：`TranscriptFloatingWindowService.java:88-101`（只判断 `playing/windowHidden/transcript`）、`:477-483`（`postDelayed(100ms)`）。
- **影响**：熄屏外放 1 小时约 3.6 万次主线程唤醒，每次读偏好 + 二分查找 + 更新文本；息屏时窗口不可见，这些工作完全浪费（Choreographer 是否仍跑需实测）。
- **建议**：监听 `ACTION_SCREEN_OFF/ON` 与窗口 `isShown`，息屏停表、亮屏恢复；`onTrimMemory`/后台时降频到 1 s；把外推逻辑抽成纯类，便于单测。
- **验证**：真机 `batterystats` 对比息屏 1 小时的 wakeups 与 CPU 时间。

### 2.5 生成转录（ASR）与广告联动

#### P1-22 每定稿一段就全量重写整份 word-JSON（O(n²) 写放大）

- **证据**：`app/.../AsrGenerationController.java:205-219`（`onSegment` 内 `segments.add()` 后立即 `save()`，在识别线程同步执行）；`:255-272`（`save` → `transcriptStore.save(segments, …)`）；`asr/.../AsrTranscriptStore.java:31-35`（每次序列化全部 segments）、`:71-93`（临时文件 + rename）。
- **影响**：844（317 段、8831 词、约 250 KB）累计约写 40 MB；3 小时集（约 1000 段、750 KB）累计 350–400 MB 闪存写入 + 1000 次 create/rename；末段每次 save 估计阻塞识别 50–100 ms（估算，待基准）。这既是闪存磨损，也是长集“越生成越慢”的来源。
- **建议**：改为追加日志（每段 append 一行 JSON/长度前缀）+ 周期性 compaction（每 N 段或每 30 s 合并一次），完成时写最终文件；`onSegment` 只 append 并更新内存；rename 前 `fsync`，保证 checkpoint 与已定稿段一致。需要 ADR 0003。
- **验证**：基准统计 1000 次 save 的总字节数与耗时；目标写放大 ≤ 2× 最终大小、单次 append ≤ 5 ms。

#### P1-23 生成期间 UI 每秒全量重读、重解析整份转录

- **证据**：`TranscriptPageFragment.java:2119-2126`（`SEGMENT` 事件按 `ASR_RELOAD_INTERVAL_MS = 1000` 调 `reloadGeneratedTranscript()`）；`:2441-2445`（`loadTranscript(true)`）；`TranscriptUtils.java:104-113`（mtime 变化导致 LRU key 失效，整份重读）。
- **影响**：3 小时集每秒读 750 KB、重建全部 `TranscriptSegment`/`TranscriptWord`，主线程 `notifyDataSetChanged`；持续 GC 抖动，正发生在用户观看生成过程时。
- **建议**：`AsrGenerationEvent.SEGMENT` 直接携带新段（或段区间），Adapter 增量 `notifyItemRangeInserted`；UI 只在“切集/完成/失败/用户主动刷新”时全量重读。事件合并为每 250–500 ms 一批，避免事件风暴。
- **验证**：生成 60 s 内 `TranscriptUtils.loadTranscript` 调用次数从约 60 降到 ≤ 3。

#### P1-24 缓存等待/超时与缓存释放被当作致命错误，一次抖动即终止生成

- **证据**：`asr/.../CacheMediaDataSource.java:46-68`、`MediaCodecPcmDecoder.java:104-127`、`AsrGenerationSession.java:66-71`（任何 `IOException` 直通 `onError`）；`playback/service/.../Media3PlaybackService.java:648`（`onDestroy` 先 `releaseCache()`，流式会话仍在跑）。
- **影响**：网络抖动、退后台、播放服务销毁都会永久结束本次生成；用户看到“生成失败”，已生成部分虽保留但缺口被跳过（配合 P2-10 更糟）。
- **建议**：区分“可重试的等待/IO”（退避后继续）与“不可恢复的错误”；`releaseCache` 前等待/取消会话；会话对缓存失效做重新绑定；把错误分成 `RETRYABLE/FATAL` 两类上报给 UI。
- **验证**：单测用可控数据源模拟超时/释放，断言会话重试而非结束；真机退后台 30 s 再回前台，生成继续。

#### P1-25 生成文件会遮蔽晚到的官方字幕，并把生成内容收编成“官方来源”

- **证据**：`model/.../FeedMedia.java:513-521`（官方 URL 存在时 `getGeneratedTranscriptFileUrl()` 仍返回同一 sidecar）；`TranscriptUtils.java:104-113`（先读本地文件）、`:164-172`（`loadLocalTranscript` 会把生成文件写成 `.source = 官方 type/url`）。
- **影响**：发布方补发官方转录后永远不生效；删除下载/条目时连带删除数小时识别结果；用户无法区分“这是官方字幕还是机器生成”。
- **建议**：来源优先级显式化：官方 URL 存在时以官方为准，生成文件保留但降级为离线回退；`.source` 记录真实来源（`generated` vs `published`），不得把生成内容收编为官方；删除策略分开（删下载不删生成，除非用户选择）。
- **验证**：单测覆盖“生成后官方 URL 出现→加载官方”；真机切官方 URL 后页面来源提示变化。

#### P1-26 会话暂停/空闲不释放模型与 CPU

- **证据**：`app/.../AsrGenerationManager.java:38-59`（只处理投屏/省电/温控/切集守卫）、`:205-225`（未覆盖“暂停/长时间无播放”）；模型 PSS 670–720 MB、CPU ≈ 53%（交接文档实测）。
- **影响**：用户暂停后模型常驻、每 50 ms 空转；低端机更容易被 LMK 杀掉，也影响其他应用。
- **建议**：暂停 ≥ N 秒（如 30 s）关闭识别器/解码器，保留 checkpoint；恢复播放时按需重新加载并从 checkpoint 续跑；提供“省内存模式”（160 ms/960 ms 档或更小模型）。
- **验证**：真机暂停 5 分钟后 PSS 回落到基线 ±100 MB；恢复后从缺口继续。

### 2.6 UI / UX

#### P1-27 控制条默认隐藏，七个功能不可发现

- **证据**：`app/src/main/res/layout/transcript_page_fragment.xml:8-14`（`controlsBar` `visibility="gone"`）；`AudioPlayerFragment.java:274-283` 唯一提示是图标 alpha 0.5。
- **影响**：首次进入只看得到列表；偏移、排版、循环、逐句、语言、搜索、沉浸全部藏在一个“看不出可点”的图标后面；用户很难发现“点句子可 seek”“还有搜索”。
- **建议**：默认展开控制条（至少首次访问展开一次并记住偏好），或把高频入口（搜索、Aa、偏移）外露、其余进溢出菜单；工具栏转录按钮加首次使用引导。
- **验证**：首次安装用户路径测试；控制条展开态截图。

#### P1-28 所有失败状态都退化成同一句「无声音文字」

- **证据**：`TranscriptPageFragment.java:566-587`（加载异常只 `Log.e`，`onError` → `showEmptyState`）；`:646-657`（无转录/空段同样 `showEmptyState`）。
- **影响**：下载失败、解析失败、离线、确实没有官方转录四种情况不可区分，且没有重试入口，用户只能退出重进。
- **建议**：四态 UI：`加载中 / 无官方转录（可生成）/ 加载失败（原因 + 重试）/ 离线（有缓存可用）`；错误态提供“重试”“生成声音文字”“检查网络”对应动作；解析失败显示“已跳过 N 条坏段”。
- **验证**：四种状态各一张截图 + 单测状态映射。

#### P1-29 生成按钮被前置条件提前隐藏，四条解释文案不可达

- **证据**：`TranscriptPageFragment.java:2161-2175`（`available = emptyState 可见 && support`，`support` 要求 `hasSource && !casting && isSupported`）；`：2272-2301` 的 `not_supported/needs_download/casting` toast 因此无法触达。
- **影响**：未下载或设备不支持时，用户看到的是“无声音文字”死路，不知道可以下载本集或换设备。
- **建议**：按钮始终显示，点击后按原因提示，并提供对应动作（“下载本集”“了解为什么不可用”）；不可用时用禁用态 + 说明，而不是消失。
- **验证**：未下载/投屏/不支持三种设备状态下各走一遍。

#### P1-30 悬浮窗是 90% 屏宽、72dp 高的触摸黑洞

- **证据**：`ui/transcript/.../TranscriptFloatingWindowService.java:185`（`windowWidth = screenWidth * 0.9`）；`:207-223`（对 `ACTION_DOWN` 一律 `return true`）；`:244-255`（控件隐藏后第一次抬起只唤出控件，第二次抬起直接打开转录页）；`floating_transcript_layout.xml:7-13`（控件条 `invisible` 仍占 32dp）。
- **影响**：永久挡住一条 90% 宽、约 72dp 高的区域；空白处点击被吞，且“第二次点击”会跳转，误触成本高。
- **建议**：窗口 `wrap_content` 贴住文字宽度；控件条 `GONE` 时高度为 0；只有文字/手柄区域消费触摸；把锁定/关闭放进长按菜单或从文字区滑动；隐藏时点击直接打开转录页（去掉“两次点击”状态机）。
- **验证**：真机在悬浮窗下方放置可点击控件，确认空白区不吞点击。

#### P1-31 控制条固定 48dp 单行、右对齐，无滚动，200% 字号会截断

- **证据**：`transcript_page_fragment.xml:8-77`（6 个 48dp + 沉浸 40dp；双语模式再加一个，共 7 个）；无 `HorizontalScrollView`、无溢出菜单。
- **影响**：≤320dp 宽或大字体下 `Aa`/`¶` 垂直截断，最左侧“偏移”被挤出且无法到达。
- **建议**：两段式：主入口 3 个（搜索、Aa、偏移）+ `⋯` 溢出；控制条可横向滚动并保证首尾可达；用 `minWidth`/`wrap_content` 适应字体缩放。
- **验证**：320dp/200% 字号截图；TalkBack 顺序可达。

#### P1-32 开关状态只用 alpha，TalkBack 不知道开/关

- **证据**：`TranscriptPageFragment.java:1856-1862`（偏移面板）、`:2038-2043`（逐句/语言）、`:1516-1522`（循环）；`btnSearch` 常驻 0.5（`:1253`），与真正 disabled 的 `btnOffsetReset`（`:1799`）共用同一视觉。
- **影响**：用户无法判断逐句换行/循环/语言当前态；TalkBack 读不出 `checked/selected`。
- **建议**：使用选中背景（`bg` 或 `Tonal` 态）+ `setSelected/setChecked` + `stateDescription`；disabled 用独立视觉；图标按钮成对提供开/关图标。
- **验证**：TalkBack 朗读“逐句换行，已开启”；颜色对比度检查。

---

## 3. P2 — 代码结构与一致性

| ID | 问题 | 证据 | 建议 |
|---|---|---|---|
| P2-1 | `TranscriptPageFragment` 2453 行，同时承载 ticker、跟随、搜索、偏移、排版、ASR、沉浸七套状态机 | `TranscriptPageFragment.java:1-2453` | 拆 `TranscriptFollowController`、`TranscriptSearchController`、`TranscriptOffsetController`、`TranscriptLayoutApplier`、`TranscriptPositionSource`、`TranscriptAsrCoordinator`；纯逻辑保留现有测试 |
| P2-2 | Adapter 无分层：DisplayInfo、偏移映射、Span 写入、搜索全挤在一起 | `TranscriptAdapter.java` 全文 | 抽出 `DisplayInfoBuilder`、`OffsetMapper`、`HighlightWriter`；Adapter 只负责 ViewHolder 生命周期 |
| P2-3 | 整屏页/视频弹窗/悬浮窗三份加载、偏移、跟随逻辑，行为不一致 | `TranscriptDialogFragment.java:108-171`（seek 无 MP3 比例换算、生成转录仍套偏移）；`TranscriptFloatingWindowService.java:381-430` | 共享 `TranscriptSession`（来源、偏移、比例、当前句），三处只做渲染 |
| P2-4 | 生成转录在 `setMedia` 首帧仍减偏移，而 `updatePlaybackPosition` 才特判 0 | `TranscriptAdapter.java:220-222` vs `:943-945` | 偏移策略收口到 `OffsetMapper`，生成转录恒为 0 |
| P2-5 | 句级高亮用 `StyleSpan(BOLD)`（影响度量、换句重折行），词级用 `FakeBoldSpan` | `TranscriptAdapter.java:544-549` vs `:567-568` | 句级也改 `FakeBoldSpan`，并去掉行级整体 BOLD |
| P2-6 | `wordEndTime` 对每个词线性回扫，最坏 O(词数²) | `TranscriptAdapter.java:530-542` | 构建 DisplayInfo 时一次预计算 `wordEnds` |
| P2-7 | 行级 alpha 0.45/0.3 立即被 bind 覆盖成 1（死代码）；timecode 颜色复用不清 | `:777-794` vs `:300-309,501-504` | 单一 `applyVisualState(holder, state)` 写入 |
| P2-8 | 模型承载显示态；`append` 静默清空词轴；`setSpeakers` 直接持有外部集合 | `model/.../Transcript.java:58`；`TranscriptSegment.java:26-30` | 显示态从段集合派生；模型只保留领域字段；`append` 明确返回“已丢弃词轴” |
| P2-9 | 缓存 key 无内容版本（无本地文件时 mtime=0）；`copyFile` 回退可能留半文件 | `TranscriptUtils.java:230-241,299-308` | key 加入 `.source`/内容哈希；copy 也走 `.tmp`+rename |
| P2-10 | 悬浮窗用 NPE 当控制流（`Callable` 返回 null → onError） | `TranscriptFloatingWindowService.java:404-444` | 改用 `Maybe`/`Optional`，区分“无转录”与“加载失败” |
| P2-11 | checkpoint 只校验 version，不校验音频时长/指纹 | `AsrGenerationController.java:140-151`；`GeneratedTranscriptCheckpoint.java:86-110` | 时长偏差 > 1 s 即丢弃 coverage；记录文件大小/DAI 指纹 |
| P2-12 | 搜索状态不持久（旋转/切集清空，EditText 文本残留但计数为 0） | `TranscriptPageFragment.java:178`；无 `onSaveInstanceState` | 保存 `searchActive/query/hitIndex/segmentLooping/immersive/pickMode`，或升到 ViewModel |
| P2-13 | 单句段（`primaryStarts.length < 3`）没有句内高亮 | `TranscriptAdapter.java:475,573` | 去掉门槛，短句也按当前句着色 |
| P2-14 | RecyclerView 无 stable id，高亮/搜索走全量 `notifyDataSetChanged` | `TranscriptAdapter.java:1015-1054` | `setHasStableIds(true)`（段起始时间）+ payload 局部刷新 |
| P2-15 | `applyLayout` 每次 bind 重算尺寸；`verticalSpacing` 同时加到 speaker 上下 margin（视觉 2×） | `TranscriptAdapter.java:107-137,250` | holder 创建时一次性套用；修正 2× |
| P2-16 | ASR 日志每段 2 条 `Log.i` + coverage 全量打印，长集约 4000 行 | `asr/.../SherpaStreamingRecognizer.java:113-135`；`AsrGenerationController.java:210-214,267-268` | 每段一条 `Log.d`，`Log.i` 只保留开始/结束/失败 |
| P2-17 | 点选对齐“成功写偏移”路径无设备自动断言；`TranscriptPageFragment` 无单测 | `docs/transcript-handover.md` §7；`app/src/test/...` | 给 `TranscriptOffsetController` 写单测；设备脚本用可滚动语料 |
| P2-18 | 跟随暂停/手动模式无持久指示 | `TranscriptPageFragment.java:1219-1224`；`transcript_page_fragment.xml:203-216` | 控制条显示“跟随已暂停 · 恢复”，或常驻轻提示 |
| P2-19 | 二级面板出现在列表下方，触发图标在上方；搜索条却在上方 | `transcript_page_fragment.xml:250-447` vs `:198-216` | 统一锚在控制条下缘或改 bottom sheet |
| P2-20 | 广告跳过撤销沿用“回到广告起点 + 本集不再跳过”，与“撤销字幕偏移”耦合；切集不清 `lastTranscriptShift` | `playback/service/.../Media3PlaybackService.java:604-645`；`TranscriptSync.java:36-52` | 撤销拆成两个独立动作（回位置 / 恢复偏移），切集清理待撤销状态 |

---

## 4. P3 — 细节与风格

| ID | 问题 | 证据 | 建议 |
|---|---|---|---|
| P3-1 | 每帧 `new int[2]` × 2 与 `getLocationOnScreen` | `TranscriptPageFragment.java:1059-1062` | 复用字段级数组；锚点只随布局变化重算 |
| P3-2 | `FOLLOW_MAX_STEP_DP` 被 `Math.max(10dp, 半屏)` 遮盖；注释仍写 45% | `:961,1088,1935` | 删除死常量；注释改 40% |
| P3-3 | `androidx.appcompat.R` 遮蔽项目 `R` | `TranscriptAdapter.java:22` | 显式导入项目 `R` |
| P3-4 | 依赖 `org.jsoup.internal.StringUtil` 内部 API | `TranscriptAdapter.java:42` | 换 `TextUtils.isEmpty` |
| P3-5 | `transcript_item.xml` 的 `id=speaker` 实际承载 timecode+speaker；speaker 为 null 时前段比较有 NPE 风险 | `transcript_item.xml:9`；`TranscriptAdapter.java:253-254` | 重命名 id，空 speaker 用 `TextUtils.equals` |
| P3-6 | 排版面板把 `sp/dp` 直接展示给用户 | `transcript_page_fragment.xml:497-509` | 改为“小/标准/大”等语义档位 + 预览 |
| P3-7 | `transcript_line_break_toggle`、`transcript_segment_loop_toggle` 已无引用 | `ui/i18n/.../strings.xml` | 清理未引用字符串（先确认无翻译引用） |
| P3-8 | 悬浮窗锁定/关闭 32dp 相邻无间距 | `floating_transcript_layout.xml:15-34` | 增加间距或改为长按菜单，降低误触点关闭 |
| P3-9 | 无平板/横屏/RTL 适配；列表无最大行宽 | `transcript_item.xml`（`match_parent`） | 加 `sw600dp`/`layout-land`/`layout-rtl` 与最大宽度约束 |
| P3-10 | 中文术语漂移：`字幕偏移` vs `声音文字*`；`对齐到本句` vs `正在播放的那一行` | `ui/i18n/.../strings.xml:228,231,343,347,348` | 统一为“声音文字 / 正在播放的那一行” |
| P3-11 | 英文 `Shift transcript %1$d seconds earlier` 缺复数；`Back to current sentence` 与 `Align to this line` 不一致 | `strings.xml:303,332,343` | 加 plurals；统一 “line/sentence” |
| P3-12 | `TranscriptParser` 注释“10 seconds”与 `MAX_SPAN=8000` 不符 | `TranscriptParser.java:9-10` | 修正注释 |

---

## 5. §7 已知限制逐条复评

| # | §7 条目 | 现状 | 处理建议 |
|---|---|---|---|
| 1 | 搜索只在整屏页 | 仍存在 | 设计取舍；如做悬浮窗搜索，复用 `TranscriptSearch` 即可 |
| 2 | 搜索只匹配当前显示文本、子串、不自动 seek | 仍存在 | 取舍；可在 P1-15 重构后考虑“整词/正则”开关 |
| 3 | 搜索主线程全量扫描，超长转录可能卡顿 | 仍存在 | **P1-15 修复** |
| 4 | 搜索状态不持久 | 仍存在 | **P2-12 修复** |
| 5 | SRT 发言人启发式 | 仍存在 | 取舍；坏数据容错见 P1-4 |
| 6 | 点选对齐成功路径无设备断言 | 仍存在 | **P2-17**；补可滚动语料 |
| 7 | 偏移锚点按媒体位置，重下载后失效 | 仍存在 | 加音频指纹（P2-11 同源） |
| 8 | 自动重对齐只处理 `TIER_AUTO` | 仍存在 | 取舍；文档保留 |
| 9 | 撤销广告跳过 = 回到起点 + 本集不再跳过 | 仍存在 | **P2-20** 拆分动作 |
| 10 | 对齐到本句误差最多一句 | 仍存在 | 设计；可用词级时间轴改善 |
| 11 | Fragment stopped 期间外部 seek 要等事件 | 仍存在 | 可接受；恢复时主动 `getCurrentPosition` |
| 12 | 250ms 刷新只接整屏页 | 仍存在 | 设计；弹窗已废弃，悬浮窗有独立 tick |
| 13 | 词级只接整屏页；词轴只主语言 | 仍存在 | 设计；悬浮窗仅用词轴驱动滚动 |
| 14 | 悬浮窗 100ms 外推 | 仍存在 | **P1-20/P1-21** |
| 15 | VTT/SRT 无词轴 | 仍存在 | 格式限制 |
| 16 | TMLT 注释段时长估算、5 个重复词丢弃 | 仍存在 | 可接受；建议 UI 标注“估算” |
| 17 | 词级轮询 100ms 可能跳过极短词 | 仍存在 | **P1-13** 改为区间 Span 后可提高刷新频率而不增成本 |
| 18 | 时长比例校正的适用边界 | 仍存在 | **P1-5** 增加上限与交叉校验 |
| 19 | 悬浮窗不显示发言人 | 设计 | 保留 |
| 20 | 跟随 MVP 45%、未加首尾 padding | **文档过期**：代码为 `ANCHOR_RATIO=0.40`，且已有末项 `ItemDecoration` 占位 | 更新 §7 与 §5.4 的 45% 表述 |
| 21 | 跟随音频开关/视频弹窗增强暂不做 | 仍存在 | 用户明确不做，保留 |
| 22 | `checkstyle/lint` 既有违规 | 仍存在 | 按 AGENTS 保持 |
| 23 | ASR 长条目中的“未修项” | 仍存在（见 P1-24/P1-25/P2-11） | 逐项对应本报告 |
| 24 | 2026-11-04 模型迁移的已知问题 | 仍存在（流式 cache 释放、超时致命、官方字幕遮蔽、兜底重试消耗、流式生成文件不清理） | P1-24/P1-25/P2-11；流式生成文件清理需补 |
| 25 | GLOSSARY「预读窗口默认 30 秒」 | **文档过期**：代码与交接文档为 40 秒 | 修正为 40 秒 |
| 26 | 交接文档 §1.6「下载与本地读取均限 5MB、storeTranscript 落盘」 | **表述需补充**：普通加载不落盘 | 改为“下载/强制刷新/媒体下载完成时落盘” |

---

## 6. 测量与验证方案

本轮未运行基准与真机，以下为可直接执行的方案；P1 条目在实施时须产出对应数字。

### 6.1 离线基准（无需设备）

1. **解析基准**（`:parser:transcript`，临时 JUnit）：
   - 合成 5 MB / 2 万行 VTT（含 30% `<i>` 与 `&amp;`），记录 `System.nanoTime` 与峰值 heap；
   - 合成 317 段 / 8831 词 JSON（与 844 同规模），记录解析时间与对象数；
   - 对照“手写标签剥离 / `JsonReader` 流式”两个候选实现。
2. **写放大基准**（`:asr`）：用计数 `OutputStream` 包住 `AsrTranscriptStore`，模拟 100 段、317 段、1000 段，统计累计写入字节、`create/rename` 次数、单次 `save` 耗时。
3. **渲染基准**（Robolectric/宏基准）：构造 1000 词段，统计一次 `applyWordGradedHighlight` 的 Span 分配数与 `setText` 次数；对比“原地改 Span”实现。
4. **搜索基准**：2000 段语料，统计单次 `setSearchQuery` 的耗时、`DisplayInfo` 构建次数、`notifyDataSetChanged` 次数。

### 6.2 真机采集（需一次设备窗口）

- 帧时间：Perfetto `gfx`/`view` trace，词级播放 60 s，统计 `Choreographer` 掉帧与主线程切片；对比控制条展开/收起、屏幕 60/120 Hz。
- 内存：`dumpsys meminfo de.danoeh.antennapod.debug`，分别在“仅播放”“打开转录页”“生成转录中”“暂停 5 分钟后”采样 PSS；目标生成中 ≤ 500 MB。
- CPU/耗电：`dumpsys cpuinfo` + `batterystats`，对比悬浮窗息屏/亮屏 30 分钟、词级页面前台 30 分钟。
- 正确性：搜索关闭后跟随、点选模式退出、生成转录首帧、断网离线、官方字幕晚到。
- StrictMode：`detectDiskReads/DetectDiskWrites` 打开后走一遍加载/生成，确认无主线程 IO。

### 6.3 验收阈值（建议）

| 指标 | 目标 |
|---|---|
| 5 MB VTT 解析（中端机） | ≤ 500 ms，峰值 heap ≤ 2× 文件 |
| 单 tick 主线程工作 | p95 ≤ 1 ms，无持续分配增长 |
| 搜索（2000 段） | 单次按键 ≤ 50 ms，无 `notifyDataSetChanged` |
| ASR 写放大 | ≤ 2× 最终文件，单次 append ≤ 5 ms |
| 生成中 PSS | ≤ 500 MB |
| 悬浮窗绘制 | ≤ 30 fps 主动重绘，息屏 0 tick |
| 暂停前台唤醒 | 30 s 内主线程唤醒 ≤ 5 次 |

---

## 7. 机会清单（新功能，不计入主清单）

以下不属于“优化”，但评审中反复出现，供后续决定：

1. 悬浮窗/视频弹窗内搜索（复用 `TranscriptSearch`）。
2. 搜索按整词/正则/跨集（需要索引）。
3. 导出转录（TXT/SRT/VTT/JSON）与分享当前句。
4. 自动跟随开关（现有“跟随暂停”是隐式状态）。
5. 翻译（生成转录目前单语；官方双语已支持）。
6. 说话人分离（`TranscriptSegment.speaker` 已存在，生成转录未使用）。
7. 书签/笔记锚定到句/词。
8. 生成队列与离线批量生成（当前只处理当前播放集）。
9. 小模型/低内存档位（160 ms/960 ms）。
10. 转录缺口可视化与“补齐缺口”入口（覆盖率已有数据）。

---

## 8. 文档、术语与 ADR

### 8.1 需要修正的文档

1. `GLOSSARY.md`：预读窗口默认值 30 → **40 秒**；补充“偏移锚点（Offset anchor）”与“点选对齐（Pick mode）”两个术语，避免与“当前高亮句”混淆。
2. `docs/transcript-handover.md` §7：跟随阅读线 45% → **40%**；“未加首尾 padding”已过时（末项 `ItemDecoration` 已实现），改为“首句仍受顶部空间限制”。
3. `docs/transcript-handover.md` §1.6：补充“普通加载只进内存，落盘发生在下载完成/强制刷新”；`§5.2` 最近结果中的 45% 表述同步。
4. `docs/transcript-handover.md` §7：把 ASR“已知问题（未修）”逐项映射到本报告 P1-24/P1-25/P2-11，避免重复“未验证/未修”描述。

### 8.2 建议的 ADR（只对难以回退的决策）

- **ADR 0003：生成转录采用增量日志 + 周期 compaction**（替代每段全量重写；决定 checkpoint 与文件的原子关系、崩溃恢复语义）。
- **ADR 0004：转录显示状态单一数据源**（ticker/PlaybackPositionEvent/跟随/搜索暂停统一为 `TranscriptPositionSource` + `FollowState`，决定 100/250 ms 刷新与暂停语义）。
- **ADR 0005：转录产物来源与缓存语义**（官方 vs 生成、落盘时机、`.source` 真值、删除策略、偏移适用性）。

### 8.3 建议实施批次

| 批次 | 内容 | 理由 |
|---|---|---|
| 1 | P0-1、P0-2、P1-6、P1-17、P2-12 | 不可逆/静默改数据与“跟随坏了”的体感，改动小、验证直接 |
| 2 | P1-13、P1-14、P1-15、P2-2、P2-5、P2-6、P2-15 | 渲染层一次重构，覆盖 tick/bind/搜索三条高频路径 |
| 3 | P1-18、P1-19、P2-9、P1-25、P2-3 | 缓存与产物语义统一，打通离线与官方/生成优先级 |
| 4 | P0-3、P1-22、P1-23、P2-11（含 ADR 0003） | 生成链路写入模型重构，风险最高，需要基准与真机 |
| 5 | P1-20、P1-21、P1-30、P2-10 | 悬浮窗绘制与触摸/耗电 |
| 6 | P1-27、P1-28、P1-29、P1-31、P1-32、P2-18、P2-19 | 可发现性与状态反馈 |
| 7 | P2-1、P2-4、P2-7、P2-8、P2-13、P2-14、P2-16、P2-17、P2-20、P3 全部 | 结构清理与长期可维护性 |

**实施进度（2026-11-05，工作树未提交；真机验证待用户发起）**：批次 1–4 已完成，批次 4 的决策见 [ADR 0003](file:///D:/AntennaPod/docs/adr/0003-generated-transcript-append-journal-and-session-lease.md)；批次 5 完成 P1-20（30 fps 门控）、P1-21（息屏停表）、P1-30 的触控部分（控件条 GONE、单击打开、去掉两次点击）、P2-10（`Maybe`）；批次 6 完成 P1-27（默认展开）、P1-28（失败文案 + 重试）、P1-29（入口常显 + 原因提示）、P1-31（横向滚动/`Aa` 自适应）、P1-32（`setSelected` + 无障碍状态），P2-18 给“回到当前句”胶囊补上“跟随已暂停”状态，P2-19 已把偏移/排版面板移到控制条下缘；批次 7 完成 P2-4、P2-8、P2-13、P2-14、P2-16、P2-20（切集清理待撤销状态）、P3-1、P3-2、P3-4、P3-5、P3-8、P3-12、P3-10（中文统一“声音文字 / 正在播放的那一行”）、P3-11（英文复数与 line 用词）、P3-6（字号/行距/边距改为语义档位，列表实时预览）、P3-7（清理未引用字符串）、P3-9（列表 720dp 最大行宽，横竖屏按宽度重新排版），P3-3 复核为误报；P2-17 补 `TranscriptTimeOffsetsTest` 单测，并在 offset 真机场景覆盖确认对话框/锚点替换/重置；P2-2 已拆出 `DisplayInfo`、`TranscriptDisplayInfoBuilder`（构建 + LRU 缓存）与 `TranscriptHighlightWriter`（句/词 span 写入，带 `TranscriptHighlightWriterTest`），偏移映射不另设 `OffsetMapper`（已由 `TranscriptTiming` 统一）；P2-1 已把 Fragment 拆为 `TranscriptFollowController`、`TranscriptOffsetController`、`TranscriptSearchController`、`TranscriptLayoutController`、`TranscriptAsrCoordinator`、`TranscriptPositionSource`；P2-3 新增共享 `TranscriptSession`（页面/弹窗/悬浮窗统一换算，带 `TranscriptSessionTest`）。真机验证（2026-11-05，Redmi 2407FRK8EC）：smoke/highlight/layout/offset/floating/search/word 全 PASS，设备按字节还原；同时修复静音音频 `adb push` 与 MSYS 路径转换两个测试底座缺陷。结构重构完成后（同日）再次全量真机回归：smoke/highlight/layout/offset/floating/search 75 项断言全 PASS，word 场景全 PASS，设备按字节还原；期间修复搜索控制器漏 `attach`、词级 Span 仅改内部颜色不重绘、测试未关闭 `temporarySkipSilence` 三处问题。单测验证：`:asr:testDebugUnitTest`、`:ui:transcript:testPlayDebugUnitTest`、`:model:testDebugUnitTest`、`:storage:preferences:testDebugUnitTest --tests TranscriptTimeOffsetsTest`、`:app:testPlayDebugUnitTest --tests Transcript*`（含 `TranscriptHighlightWriterTest`、`TranscriptSessionTest`）全绿，`:app:assembleDebug` 通过。

---

## 9. 评审边界与未做的事

- 未修改任何代码、未提交、未跑 `./gradlew` 构建与单测、未跑真机脚本；本报告的所有“量化”均为代码事实 + 数据规模推导，实测数字按 §6 采集。
- 未覆盖上游 AntennaPod 公共代码（播放器、下载、通知渠道）本身的既有问题，只覆盖转录功能接线处。
- 未评估视觉设计的美学质量，只评估信息架构、可发现性、状态反馈、无障碍与适配。
- 生成转录的真机结论沿用交接文档 §5.2/§7 的最近记录；本轮没有新增设备证据。
