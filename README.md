# 跨链消息桥接（本地双模拟链）

用两条运行在本地的模拟链（`CHAIN_A` / `CHAIN_B`）验证跨链消息的**发起、签名、提交、证明验证与执行**，
并在**链重组、乱序投递、重复/碰撞投递、并发处理、资源限额**等条件下保证确定的最终结果。

纯 Java 21 实现，无外部依赖（仅 JUnit 5 用于测试）；哈希用 SHA-256，签名用 ECDSA P-256。

## 快速开始

```bash
# 运行全部测试（27 个，覆盖重组 / 重复投递 / 并发投递 / 验证失败 / 资源限额）
mvn test

# 运行本地端到端演示（正常流、重复、超限重试、重组撤销、最终性回滚被拒）
mvn -q compile
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "target/classes:$(cat /tmp/cp.txt)" \
     com.github.lechandonga.crosschainbridge.demo.LocalDemo
```

## 模块结构

| 包 | 关键类型 | 职责 |
| --- | --- | --- |
| `model` | `BridgeMessage` `SignedMessage` `BlockHeader` `MerkleProof` `MessageProof` `MessageState` | 消息、区块头、证明与状态机等值对象 |
| `model` | `MessageStatus` `FailureReason` | 生命周期状态与**可区分**失败原因枚举 |
| `crypto` | `HashUtils` `MerkleTreeBuilder` `MessageSigner` | SHA-256、Merkle 包含证明、ECDSA 签名/验签 |
| `chain` | `SimulatedBlockchain` `ChainState` `FinalizedRollbackException` | 出块、最终性、重组；原子账务应用 |
| `lightclient` | `LightClient` | 区块头/状态根一致性、Merkle 覆盖、验证者仲裁 |
| `bridge` | `BridgeEndpoint` `TargetMailbox` `Relayer` `TwoChainSystem` | 发起/提交/执行/重试/撤销协调、重放防护、并发中继、装配 |
| `logging` | `DecisionLog` | 含**消息标识 + 判定结论 + 原因 + 依据**的结构化日志 |
| `demo` | `LocalDemo` | 本地可运行的端到端演示 |

## 消息生命周期

```
                    originate()                         dispatchBatch()
 发送方 ──构造──▶ CREATED ─────────▶ INCLUDED(来源链出块, 生成 Merkle 证明)
                                          │
                              submit(MessageProof) 到目标链
                                          ▼
            基本校验(字段/链标识/有效期) + 重放/碰撞检查
                                          │
                              轻客户端 LightClient.verify()
                                          ▼
                                       VERIFIED
                                  原子执行(单临界区)
                              ┌───────────┴────────────┐
                              ▼                        ▼
                          EXECUTED              RETRYABLE（requiredGas > feeLimit）
                     （终态，不可回滚）          │ retry()
                            ▲              ┌─────┼──────────────┐
                            │              ▼     ▼              ▼
                       来源链最终性       成功 EXECUTED  WINDOW/MESSAGE EXPIRED
                       推进后固化                    RETRIES_EXHAUSTED → FAILED_FINAL

 来源链重组 handleReorg(forkHeight):
   • 高度 > fork 且未最终：REVOKED（已执行的账务效果被原子补偿回滚；重新包含+重新验证后可再执行）
   • 已最终确定：保留为 finalizedKept；fork 高度低于来源最终高度时拒绝（FINALIZED_BLOCK_ROLLBACK_REJECTED）
```

状态枚举见 `MessageStatus`：`CREATED / INCLUDED / SUBMITTED / VERIFIED / EXECUTED /
RETRYABLE / REVOKED / REJECTED / FAILED_FINAL / EXPIRED`。

## 验证规则与可区分失败原因

目标端按固定顺序校验，**先失败先返回**，每类错误都有唯一的 `FailureReason`：

| 顺序 | 检查内容 | 失败原因 | 是否改变目标链状态 |
| --- | --- | --- | --- |
| 1 | 消息/证明非空，`messageId`、`payload`、链标识、重试策略非空；有效期区间合法 | `NULL_OR_BLANK_FIELD` | 否 |
| 2 | 来源/目标非同一链且互为对端链 | `INVALID_CHAIN_ID` | 否 |
| 3 | 当前时间 ≤ `validUntilMillis`（发起与提交都检查） | `MESSAGE_EXPIRED` | 否 |
| 4 | 目标链与接收端点一致 | `TARGET_CHAIN_MISMATCH` | 否 |
| 5 | 同 id 已执行 → 来源指纹相同 | `DUPLICATE_MESSAGE` | 否（幂等） |
| 5 | 同 id 已执行/占用，但 `(来源链, nonce)` 不同 | `MESSAGE_ID_COLLISION` | 否 |
| 6 | 轻客户端：区块头已同步，头哈希/状态根/交易根一致 | `HEADER_STATE_ROOT_MISMATCH` | 否（信箱释放，可重交） |
| 7 | 轻客户端：证明叶子 == 消息哈希，高度匹配，Merkle 重算根 == 交易根 | `PROOF_NOT_COVERING_MESSAGE` | 否 |
| 8 | 轻客户端：去重后的有效验证者签名数 ≥ 仲裁阈值 | `INSUFFICIENT_VALIDATOR_QUORUM` | 否 |
| 9 | 原子执行（余额/载荷） | `EXECUTION_ERROR`（余额不足） | 否（临界区内整体不落账） |
| 10 | 端点接口被同一线程重入 | `REENTRANCY_DETECTED` | 否 |

**资源限额与重试**：`requiredGas > feeLimit(+追加额度)` 时进入 `RETRYABLE`，消息与状态保留、不执行账务；
可通过 `addFeeTopUp` 模拟发送方补足费用后 `retry()`。重试有两个确定性终态：

- 超过 `RetryPolicy.retryWindowMillis`（自首次提交起）→ `FAILED_FINAL / RETRY_WINDOW_EXPIRED`；
- 超过 `RetryPolicy.maxRetries` → `FAILED_FINAL / RETRIES_EXHAUSTED`；
- 消息自身 `validUntilMillis` 先到期 → `EXPIRED / MESSAGE_EXPIRED`。

## 重组与最终性语义

- `SimulatedBlockchain.reorg(forkHeight, newBlocks)`：保留到分叉点，截断其后的块并接新链；
  若 `forkHeight < finalizedHeight`，直接抛 `FinalizedRollbackException`。
- 新规范链的区块头通过 `syncCanonicalHeaders`（或 `LightClient.ingestHeader`）替换对端轻客户端中的旧头。
- `BridgeEndpoint.handleReorg` 返回 `ReorgResult`，**明确区分两类结果**：
  - `revoked`：落在替换区间且未最终的消息（即使已在目标端执行，账务也会经补偿器反向回滚，
    补偿策略可通过 `setRevocationCompensator` 替换）；
  - `finalizedKept`：已最终确定、不得回滚的消息；
  - `rollbackAttempted=true` / `FINALIZED_BLOCK_ROLLBACK_REJECTED`：分叉点低于来源最终高度。
- 被撤销消息在新链上重新包含后，可用**新的区块头与 Merkle 证明**重新提交，再次验证通过才执行。

## 并发、重放与重入

- `TargetMailbox` 以消息 id 做两阶段占用（`reserve → markExecuted/release/forgetAfterReorg`），
  并记录 `(来源链, nonce)` 来源指纹用于碰撞检测。
- `BridgeEndpoint` 用单把 `ReentrantLock` 串行化同一端点的提交/执行/重试/重组；
  临界区内“先全部校验、后一次落账”，因此不存在半更新中间状态，同线程重入直接被拒绝。
- `Relayer` 按任意下标序列（可乱序、可重复）并发投递；无论顺序如何，每条消息恰好执行一次，
  账务结果与投递顺序无关（见 `ConcurrencyAndRetryTest`，16 线程重复提交同一消息仅 1 次成功）。

## 日志

每次判定都通过 `DecisionLog` 记录，格式形如：

```
[messageId=m-1] decision=EXECUTED reason=NONE basis=payload applied atomically on endpointB h=1
[messageId=m-1] decision=REJECTED reason=DUPLICATE_MESSAGE basis=message already executed on endpointB
```

`DecisionLog.entriesFor(messageId)` 可供测试断言：每条记录都带消息标识、判定结论、失败原因与判定依据。

## 测试索引

| 测试类 | 覆盖场景 |
| --- | --- |
| `HappyPathTest` | A→B、B→A 正常执行，重复投递幂等 |
| `ValidationFailureTest` | 字段缺失、链标识非法、过期、目标不匹配、Merkle 不覆盖、状态根不一致、未知头、仲裁不足 |
| `ReorgTest` | 未最终撤销+账务回滚+重新包含执行；最终区块回滚被拒；分叉点上下消息区分处理 |
| `ConcurrencyAndRetryTest` | 乱序/重复并发投递、16 线程并发、资源超限可重试、补足费用成功、重试耗尽、窗口过期、消息过期 |
| `CollisionTest` | 同 id 不同 nonce / 不同来源链的碰撞拒绝 |
| `DecisionLogTest` | 日志包含消息标识与判定依据，不同失败路径可区分 |
