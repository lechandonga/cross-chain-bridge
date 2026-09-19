package com.github.lechandonga.crosschainbridge.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/** 结构化判定日志：每条记录含消息标识、判定结论与依据；测试可读取。 */
public final class DecisionLog {

    /**
     * @param timestampMillis 判定时刻
     * @param messageId       跨链消息标识
     * @param decision        判定结论（ACCEPTED / REJECTED / RETRY / REVOKED / FINALIZED-KEPT ...）
     * @param reason          失败原因枚举名（成功为 NONE）
     * @param basis           判定依据的文字说明
     */
    public record Entry(long timestampMillis, String messageId, String decision, String reason, String basis) {
        public String render() {
            return "[messageId=" + (messageId == null ? "-" : messageId)
                    + "] decision=" + decision
                    + " reason=" + reason
                    + " basis=" + basis;
        }
    }

    private static final Logger JUL = Logger.getLogger("cross-chain-bridge");

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    public DecisionLog() {
    }

    public void log(String messageId, String decision, String reason, String basis) {
        Entry entry = new Entry(System.currentTimeMillis(), messageId, decision, reason, basis);
        entries.add(entry);
        JUL.info(entry.render());
    }

    public List<Entry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    public List<Entry> entriesFor(String messageId) {
        synchronized (entries) {
            return entries.stream().filter(e -> messageId.equals(e.messageId())).toList();
        }
    }
}
