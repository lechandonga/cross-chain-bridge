# 跨链消息桥接（本地双链模拟）

在本地运行的两条模拟链（`chain-a` 来源链、`chain-b` 目标链）之间验证跨链消息的
**提交 → 验证 → 执行** 全流程，保证在凭证伪造、重入、链重组、乱序与重复投递下
仍得到确定的最终结果。

## 模块结构

```
com.github.lechandonga.bridge
├── model        消息模型：CrossChainMessage / MessageStatus / RejectionReason / RetryPolicy
├── crypto       密码学凭证：Signing（Ed25519）、KeyDirectory/SimpleKeyDirectory、CredentialVerifier
├── chain        模拟链：SimulatedChain（出块、重组、最终性）、Block / BlockHeader
├── lightclient  轻客户端：LightClient（区块头、授权与包含证明验证）、MerkleProof
├── bridge       桥接端点：Bridge（凭证校验、重入防护、执行、重放防护、重试、重组）
└── relayer      中继器：Relayer（多通道并发投递）
```

## 消息生命周期

```
INITIATED → SUBMITTED → VERIFIED → EXECUTED → FINALIZED
                              ├→ RETRYABLE → (重试耗尽) FAILED_FINAL
                              └→ (重组回滚) REVERTED → 重新完整验证后可再执行
任意阶段校验失败 → REJECTED（携带可区分的 RejectionReason）
```

1. **发起**：发起方构造 `CrossChainMessage`，声明目标链、有效期
   （`validFrom`/`validUntil`，以目标链高度为时钟）与重试策略（`RetryPolicy.maxRetries`）。
2. **凭证**：发送方用自己的 **Ed25519 私钥**对消息内容哈希（`contentHash`）签名，
   凭证为签名的 Base64 编码，写入 `CrossChainMessage.signature`。公钥事先注册到
   桥接的 `KeyDirectory`。**仅掌握消息内容无法构造有效凭证**，凭证与发送方身份密码学绑定。
3. **提交**：消息在来源链登记 `outbox:<id> = contentHash`，并由验证者用各自私钥
   对区块头签名后出块（阈值多签）。
4. **验证**：目标链 `Bridge.submit` 依次校验字段、链标识、有效期、重放/碰撞，再做
   **完整来源验证**：发送方凭证、来源区块头（含撤销名单、授权去重）、Merkle 包含证明、
   证明值与消息内容哈希一致。
5. **执行**：通过全部校验后原子执行载荷（`transfer:<account>:<amount>`）并收取手续费，
   状态写入目标链并固化到新区块。
6. **最终性**：执行所在高度被 `finalizeUpTo` 覆盖后进入 `FINALIZED`，不可回滚。

## 凭证验证规则（不可伪造）

发送方凭证（`CredentialVerifier`）的失败口径：

| 情形 | 失败原因 |
|---|---|
| 凭证为空、无法解析、签名被篡改，且不匹配任何已注册身份 | `CREDENTIAL_MALFORMED` |
| 凭证密码学有效，但由**另一个已注册身份**的私钥作出（冒充发送方） | `CREDENTIAL_SENDER_MISMATCH` |

来源链区块头授权（`LightClient.verifyHeader`）保持同一口径，且每个签名都必须通过
验证者公钥校验，重复与伪造的授权都**不能参与凑够门槛**：

| 情形 | 失败原因 |
|---|---|
| 签名不属于任何已注册验证者（伪造） | `CREDENTIAL_MALFORMED` |
| 同一验证者在同一区块头中被重复计入 | `DUPLICATE_AUTHORIZATION` |
| 去重后的有效授权数仍低于阈值 | `INSUFFICIENT_VALIDATORS` |
| 区块头已被来源链重组撤销/替换（`markHeaderRevoked`） | `SOURCE_HEADER_REVOKED` |

> 旧的 `sig:<sender>:<hash>` 拼接式"签名"任何人都能伪造，已被真实非对称签名取代；
> `INVALID_SIGNATURE` 仅作为兼容常量保留，新流程使用上表中更细的原因。

## 重入语义

- `Bridge` 为**每条消息**维护一个 `ReentrantLock`。同一次 `submit` / `retry`
  尚未结束时，同一线程从同一路径再次进入同一消息，立即返回
  `REJECTED + REENTRANCY_DETECTED`：
  - 不会出现半更新的中间状态（重入调用在任何账务写入之前被拦截）；
  - 不会重复记账或重复扣减重试预算；
  - 外层处理随后仍正常完成，之后的重复投递继续走标准 `REPLAY` 口径。
- 锁是**按消息分段**的，不使用全局大锁：不同消息可以并行推进，正常并发投递不会被
  长时间阻塞；锁顺序恒为"消息锁 → 重组屏障"，重组不反向申请消息锁，因此无死锁。
- 测试通过 `Bridge.setBeforeExecuteHook`（执行切面向账务写入之前回调）稳定复现重入时序。

## 重新执行前必须重新验证来源

每一次真正执行之前——无论是**初次提交**、`RETRYABLE` 的**重试**，还是
`REVERTED`（重组撤销）后的**重新提交**——都会重新跑一遍完整来源验证
（凭证 → 区块头撤销检查 → 多签门槛 → 包含证明 → 证明值一致）：

- `retry(id)` 使用登记时保存的来源材料重新验证；
- `retry(id, freshProof, freshHeader)` 携带最新来源材料重新验证；
- 来源证明已失效（如来源头已被 `markHeaderRevoked` 撤销）时返回可区分原因
  （`SOURCE_HEADER_REVOKED` / `CREDENTIAL_MALFORMED` / `PROOF_NOT_COVERING` 等），
  **不改变目标链状态，也不消耗重试预算**；
- 重组撤销后只能用新规范链上的有效区块头与证明重新提交。

## 重组判定口径与最终性

- `Bridge.onReorg` 只撤销**同时满足**以下条件的消息：状态为 `EXECUTED`、
  执行高度落在被回滚范围内（`executedAtHeight > 当前链头`）、且执行高度
  **尚未最终确定**（`> finalizedHeight`）。其余消息（包括回滚范围之外、未最终确定的
  消息）一律保持原状态。
- `SimulatedChain.reorg` 拒绝触及已最终确定高度（返回 -1，状态不变）；
  已最终确定的消息及其账务在任何重组下都不会被回滚。
- 撤销与最终确定分别由 `MessageStatus.REVERTED` 与 `MessageStatus.FINALIZED` 明确区分。
- `Bridge.handleReorg(depth)` 在一个串行区内完成"等待在途投递结束 → 链回滚 →
  通知撤销"，消除投递与重组交错时的撕裂窗口（建议所有重组都走该入口）。

## 并发与一致性

- 消息级分段锁 + 重组屏障保证：并发投递与重组交错时最终状态唯一、可重放一致；
  同一消息在每个规范链纪元内有效执行**最多一次**，账务与手续费要么一致存在、
  要么一致回滚，不会出现半更新值。
- 多通道乱序投递（`Relayer.deliverChannelAsync`）下，账务操作可交换，
  处理顺序不一致时最终状态一致。

## 验证规则与失败原因汇总

| 规则 | 失败原因 / 结果 |
|---|---|
| 必填字段缺失 | `MISSING_FIELD` |
| 目标链标识不匹配 | `CHAIN_ID_MISMATCH` |
| `validUntil` 早于当前高度 / `validFrom` 晚于当前高度 | `EXPIRED` / `NOT_YET_VALID` |
| 凭证篡改、无法解析或不属任何身份 | `CREDENTIAL_MALFORMED` |
| 凭证由其他已注册身份作出 | `CREDENTIAL_SENDER_MISMATCH` |
| 区块头伪造授权 / 同一授权重复计入 | `CREDENTIAL_MALFORMED` / `DUPLICATE_AUTHORIZATION` |
| 去重后有效授权不足阈值 | `INSUFFICIENT_VALIDATORS` |
| 来源区块头已被撤销/替换 | `SOURCE_HEADER_REVOKED` |
| 证明未覆盖或值不一致 / 状态根不一致 | `PROOF_NOT_COVERING` / `STATE_ROOT_MISMATCH` |
| 同一路径处理未结束时重入 | `REENTRANCY_DETECTED` |
| 同一消息重复投递 / 同 id 不同内容 | `REPLAY` / `ID_COLLISION` |
| 执行消耗超过 `gasLimit` | 进入 `RETRYABLE`（非终态） |
| 重试次数耗尽 | `FAILED_FINAL` + `RETRIES_EXHAUSTED` |
| 重组触及已最终确定区块 | 链拒绝重组（`reorg`/`handleReorg` 返回 -1） |

所有被拒绝的消息**不改变目标链任何状态**；每次判定都输出含消息标识与判定依据的日志。

## 本地验证方法

```bash
# 编译
mvn compile

# 运行全部测试（69 个用例）
mvn test

# 单独运行某类场景
mvn test -Dtest=CredentialSecurityTest       # 凭证不可伪造：篡改/身份不一致/复用
mvn test -Dtest=ReentrancyTest               # 重入识别、预算不重复扣、无死锁/长阻塞
mvn test -Dtest=ReexecutionVerificationTest  # 重试与重组后重新执行的重新来源验证
mvn test -Dtest=ReorgConcurrencyTest         # 重组与并发投递交错（含 20 次重复）
mvn test -Dtest=ReorgFinalityTest            # 重组区间判定与最终性
mvn test -Dtest=LightClientVerificationTest  # 区块头授权与轻客户端证明
mvn test -Dtest=ConcurrencyTest              # 多通道并发投递与顺序无关性
mvn test -Dtest=ReplayProtectionTest         # 重复投递与重放防护
mvn test -Dtest=GasAndRetryTest              # 手续费与资源限额
mvn test -Dtest=SubmissionValidationTest     # 提交校验
```

测试日志（`java.util.logging`）中包含消息标识与判定依据，例如：

```
msg=e1 拒绝: 原因=REENTRANCY_DETECTED 依据=提交路径在同一次处理结束前被重入
msg=r5c 重组撤销: 执行高度=3 当前链头=2 最终确定高度=0 判定=REVERTED
msg=x6 重试前来源验证未通过: 原因=SOURCE_HEADER_REVOKED 判定保持=RETRYABLE
msg=s4 拒绝: 原因=CREDENTIAL_SENDER_MISMATCH 依据=发送方凭证校验失败: sender=alice
```
