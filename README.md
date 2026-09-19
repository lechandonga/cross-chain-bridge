# 跨链消息桥接（本地双链模拟）

在本地运行的两条模拟链（`chain-a` 来源链、`chain-b` 目标链）之间验证跨链消息的
**提交 → 验证 → 执行** 全流程，保证在链重组、乱序与重复投递下仍得到确定的最终结果。

## 模块结构

```
com.github.lechandonga.bridge
├── model        消息模型：CrossChainMessage / MessageStatus / RejectionReason / RetryPolicy
├── chain        模拟链：SimulatedChain（出块、重组、最终性）、Block / BlockHeader
├── lightclient  轻客户端：LightClient（区块头与包含证明验证）、MerkleProof
├── bridge       桥接端点：Bridge（提交校验、执行、重放防护、重试）、BridgeResult
└── relayer      中继器：Relayer（多通道并发投递）
```

## 消息生命周期

```
INITIATED → SUBMITTED → VERIFIED → EXECUTED → FINALIZED
                              ├→ RETRYABLE → (重试耗尽) FAILED_FINAL
                              └→ (重组回滚) REVERTED → 可重新提交验证执行
任意阶段校验失败 → REJECTED（携带可区分的 RejectionReason）
```

1. **发起**：发起方构造 `CrossChainMessage`，声明目标链、有效期
   （`validFrom`/`validUntil`，以目标链高度为时钟）与重试策略（`RetryPolicy.maxRetries`），
   并对内容哈希签名（模拟格式 `sig:<sender>:<contentHash>`）。
2. **提交**：消息在来源链登记 `outbox:<id> = contentHash` 并随验证者签名出块。
3. **验证**：目标链 `Bridge.submit` 依次校验字段、链标识、有效期、签名、重放防护，
   再由轻客户端验证区块头（验证者签名达阈值）与 Merkle 包含证明
   （证明覆盖 `outbox:<id>` 且重算根等于区块头状态根）。
4. **执行**：通过全部校验后原子执行载荷（`transfer:<account>:<amount>`）并收取手续费，
   状态写入目标链并固化到新区块。
5. **最终性**：执行所在高度被 `finalizeUpTo` 覆盖后，消息进入 `FINALIZED`，不可回滚。

## 验证规则与失败原因

| 规则 | 失败原因 |
|---|---|
| 必填字段缺失（id/链标识/发送方/载荷/重试策略） | `MISSING_FIELD` |
| 声明目标链与实际目标链不一致 | `CHAIN_ID_MISMATCH` |
| `validUntil` 早于当前高度 | `EXPIRED` |
| `validFrom` 晚于当前高度 | `NOT_YET_VALID` |
| 签名与内容哈希不匹配 | `INVALID_SIGNATURE` |
| 证明未覆盖 `outbox:<id>` 或证明值与内容哈希不符 | `PROOF_NOT_COVERING` |
| 证明重算根与区块头状态根不一致 | `STATE_ROOT_MISMATCH` |
| 有效验证者签名数低于阈值 | `INSUFFICIENT_VALIDATORS` |
| 同一消息重复投递 | `REPLAY` |
| 相同 id 不同内容 | `ID_COLLISION` |
| 执行消耗超过 `gasLimit` | 进入 `RETRYABLE`（非终态） |
| 重试次数耗尽 | `FAILED_FINAL` + `RETRIES_EXHAUSTED` |
| 重组触及已最终确定区块 | 链拒绝重组（`reorg` 返回 -1） |

所有被拒绝的消息**不改变目标链任何状态**；每次判定都输出含消息标识与判定依据的日志。

## 重组与最终性

- 消息状态与账务写入链状态，重组（`SimulatedChain.reorg`）回滚区块时自动撤销；
  `Bridge.onReorg` 将受影响消息标记为 `REVERTED`，可重新提交验证执行。
- 重组深度触及已最终确定高度时，链拒绝重组，`FINALIZED` 消息及其账务不受影响。
- 两种情形通过 `MessageStatus.REVERTED` 与 `MessageStatus.FINALIZED` 明确区分。

## 并发与一致性

- `Bridge` 的所有状态变更在 `synchronized` 方法内原子完成，接口重入不会产生半更新状态。
- 多通道并发投递（`Relayer.deliverChannelAsync`）下，账务操作可交换且幂等防护保证
  每条消息只执行一次，处理顺序不一致时最终状态一致。

## 本地验证方法

```bash
# 编译
mvn compile

# 运行全部测试（23 个用例，覆盖重组、重复投递、并发投递、验证失败、资源限额）
mvn test

# 单独运行某类场景
mvn test -Dtest=ReorgFinalityTest        # 重组与最终性
mvn test -Dtest=ReplayProtectionTest     # 重复投递与重放防护
mvn test -Dtest=ConcurrencyTest          # 并发投递与顺序无关性
mvn test -Dtest=LightClientVerificationTest  # 轻客户端验证失败
mvn test -Dtest=GasAndRetryTest          # 手续费与资源限额
mvn test -Dtest=SubmissionValidationTest # 提交校验
```

测试日志（`java.util.logging`）中包含消息标识与判定依据，例如：

```
msg=r1 重组撤销: 执行高度=1 当前链头=0 判定=REVERTED
msg=p1 拒绝: 原因=REPLAY 依据=消息已投递过: id=p1
msg=g2 重试耗尽: 判定=FAILED_FINAL 原因=RETRIES_EXHAUSTED
```
