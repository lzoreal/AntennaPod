# 生成转录采用增量日志、词级覆盖真相与单写者租约

**Status**: accepted

生成转录在识别过程中持续产出段落。旧的实现每定稿一段就把整份 word-JSON 原子重写一次（O(n²) 写放大），并允许实时会话与 WorkManager 收尾任务同时写同一个文件；覆盖区间取自解码管线而不是已落盘的文字，因此两个写者交错时会出现“coverage 已覆盖、文件里却没有文字”的永久缺口。现在把写入拆成两层：每次定稿只向 `<transcript>.journal` 追加一行段 JSON（append + fsync），每 20 段、每 30 秒或生成结束时用 `AsrTranscriptStore.save()` 把内存中的全部段落 compaction 成原有 word-JSON 快照并清空 journal；读取端（`AsrTranscriptStore` 与 `TranscriptUtils`）先读快照再回放 journal，并按段签名去重，所以“写完快照、未清 journal”时崩溃也不会重复。覆盖区间只从已持久化的词区间合并得出（短停顿 ≤2 s 合并），不再相信管线推导的“已解码”范围。同一 mediaId 的实时会话与 Worker 共享进程内 `AsrSessionLease`，拿不到租约的 Worker 直接 `Result.retry()`，实时会话在等待窗口内重试。checkpoint 额外记录音频字节数与头/尾 64 KiB 指纹，音频被替换或时长偏差超过 1 秒时丢弃旧进度。

**Considered Options**: 继续整份重写（实现最简单，但 3 小时集累计数百 MB 写入，且末段单次阻塞 50–100 ms）；只在内存里累积、结束时一次写入（进程死亡丢失全部进度）；用 SQLite 保存段落（与现有 `.transcript` sidecar、导出与“生成转录=一个文件”的约定冲突）；让 Worker 靠轮询 `AsrGenerationManager.isRunning` 互斥（检查与开写之间有窗口，正是 P0-3 的成因）。

**Consequences**: 生成转录产物仍是单个可解析的 word-JSON，journal 只是崩溃恢复的附属文件（删除媒体时一并清理）；负载峰值写放大降到最终大小量级；快照与 journal 的一致性由“先快照后清日志 + 回放去重”保证；覆盖区间不再包含未定稿的静音尾段，续跑会重新解码少量静音，但不会跳过无文字区间；任何新写者都必须经过 `AsrGenerationController` 获取租约。决策对应评审 P0-3、P1-22、P1-23、P2-11，实现见 `AsrTranscriptStore`、`TranscriptJournal`、`AsrGenerationController` 与 `AsrSessionLease`。
