# 跨链消息桥接（本地双链模拟）

在本地运行的两条模拟链（`chain-a` 来源链、`chain-b` 目标链）之间验证跨链消息的
**提交 → 验证 → 执行** 全流程，保证在凭证伪造、接口重入、链重组、乱序与并发投递下
仍得到确定且可重放一致的最终结果。

## 模块结构

```
com.github.lechandonga.bridge
├── model        消息模型：CrossChainMessage / MessageStatus / RejectionReason / RetryPolicy
├── crypto       密码学凭证：Signatures（Ed25519 签发/校验）、IdentityRegistry（身份-公钥绑定）
├── chain        模拟链：SimulatedChain（出块、重组、最终性）、Block / BlockHeader
├── lightclient  轻客户端：LightClient（正统区块头跟踪、授权计数、Merkle 包含证明）、MerkleProof
├── bridge       桥接端点：Bridge（凭证校验、重入防护、提交/重试/重提交、来源再验证、重组）
└── relayer      中继器：Relayer（多通道并发投递）
```

## 消息生命周期

```
INITIATED → SUBMITTED → VERIFIED → EXECUTED → FINALIZED
                              ├→ RETRYABLE → (重试耗尽) FAILED_FINAL
                              └→ (重组回滚) REVERTED → 重新提交（重新完整验证）→ 执行
任意阶段校验失败 → REJECTED（携带可区分的 RejectionReason）
```

1. **发起与凭证**：发起方在身份登记本（`IdentityRegistry`）登记公钥，用对应 **Ed25519
   私钥**对消息内容哈希签名，凭证格式 `cred:v1:<sender>:<base64url(signature)>`。
   凭证与发送方身份强绑定：**仅凭消息内容无法构造有效凭证**，必须持有发送方私钥。
2. **提交**：消息在来源链登记 `outbox:<id> = contentHash` 并随验证者授权凭证出块；
   轻客户端按来源链正统链跟踪区块头。
3. **验证**：目标链 `Bridge.submit` 依次校验重入、字段、链标识、有效期、发送方凭证、
   重放防护，再由轻客户端验证区块头（授权门槛 + 正统性）与 Merkle 包含证明
   （证明覆盖 `outbox:<id>` 且重算根等于区块头状态根）。
4. **执行**：通过全部校验后原子执行载荷（`transfer:<account>:<amount>`）并收取手续费，
   状态写入目标链并固化到新区块。
5. **最终性**：执行所在高度被 `finalizeUpTo` 覆盖后进入 `FINALIZED`，**在任何重组下都
   不可回滚**。

## 凭证与授权验证规则

### 消息发送方凭证（Ed25519，身份绑定、不可伪造）

| 情形 | 失败原因 |
|---|---|
| 发送方未在身份登记本登记 | `UNREGISTERED_SENDER` |
| 凭证无法解析 / Base64 损坏 / 签名体被篡改 / 验签失败 | `INVALID_SIGNATURE` |
| 凭证本身真实有效，但由其他已登记主体签发（与声明发送方不一致） | `CREDENTIAL_SENDER_MISMATCH` |
| 凭证通过且与发送方绑定 | 放行 |

只知道内容哈希无法产出 `cred:v1:alice:<sig>`——没有 alice 的私钥，任何伪造串都过不了
alice 公钥的 Ed25519 验签；把他人凭证前缀里的主体名改成 alice 同样验签失败。

### 来源链区块头授权（与消息凭证同一口径）

区块头授权凭证为 `cred:v1:<validator>:<sig(headerHash)>`，用验证者登记公钥校验。
计数时**先按验证者去重**，伪造、越权、重复的授权一律不计入门槛；有效授权数达到阈值
（示例为 2/3）才通过：

| 情形 | 失败原因 |
|---|---|
| 凭证无法解析 / 被篡改 / 公钥验签失败 | `INVALID_SIGNATURE` |
| 凭证有效但签发主体不在验证者集合 | `AUTHORIZER_NOT_AUTHORIZED` |
| 同一验证者的授权被重复列入（去重后不足阈值） | `DUPLICATE_AUTHORIZER` |
| 去重后的有效授权数仍低于阈值 | `INSUFFICIENT_VALIDATORS` |
| 区块头不在来源链当前正统链上（已被重组取代） | `SOURCE_HEADER_SUPERSEDED` |

轻客户端通过 `observeCanonical` 顺序接收正统区块头；来源链重组后调用
`resetCanonicalHead` 把正统头切换到新链末端，旧分叉上的区块头随即被判为已取代。

## 重入语义

- 同一次投递/重试尚未结束时，**从同一处理路径（同线程）再次进入** `submit`/`retry`，
  立即返回可区分失败 `REENTRANT_CALL`，不会当作普通请求继续处理。
- 重入防护是线程内标记（`ThreadLocal`），**不是跨线程互斥锁**：因此不会死锁，也不会
  阻塞其他线程对不同消息的正常并发投递；外层调用结束后标记自动清除，后续重复投递按
  正常的 `REPLAY` 处理。
- 重入发生时不产生半更新中间状态、不发生重复记账（余额/手续费只记一次）。
- 测试通过可注入的执行窗口探针 `Bridge.setExecutionHook`（账务已应用、区块固化前回调）
  确定性复现重入时序；生产环境保持默认空探针即可。

## 重新执行前的来源再验证

- **资源超限重试**（`retry`）：每次重试前都使用留存的来源材料重新执行一次完整来源验证
  （区块头授权门槛 + 正统性 + Merkle 包含 + 证明值一致）。
- **重组撤销后重新提交**（`submit`）：与首次提交完全相同的完整验证流程。
- 来源证明在此期间失效（如来源链重组使区块头变为 `SOURCE_HEADER_SUPERSEDED`，或授权
  不足）时，重新执行被拒绝并给出可区分原因；**不改变目标链状态，也不消耗重试预算**
  （反复验证失败不会把消息推进到 `FAILED_FINAL`）。

## 重组判定口径

- `Bridge.handleReorg(depth)` 在桥接端点同一把锁内原子完成「链回滚 + 消息撤销判定」，
  与并发提交/重试串行化；底层 `SimulatedChain.reorg` 返回实际回滚块数，触及最终确定
  高度时返回 -1 且状态不变。
- 一次重组只回滚部分区块时，**只有执行高度真正落在被回滚区间、且尚未最终确定的消息**
  被标记为 `REVERTED`；区间外消息保持 `EXECUTED`，`FINALIZED` 消息及其账务永不回滚。
- 撤销与最终确定通过 `MessageStatus.REVERTED` 与 `MessageStatus.FINALIZED` 明确区分。

## 并发与一致性

- `Bridge` 的状态变更在 `synchronized` 方法内原子完成；重组与投递通过同一把锁串行化。
- 多通道并发投递、投递与重组任意交错下，同一消息的**有效执行最多一次**；
  枚举全部事件顺序与多线程压力测试均验证：最终状态唯一、可重放一致、无重复记账。

## 验证规则与失败原因汇总

| 规则 | 失败原因 |
|---|---|
| 必填字段缺失（id/链标识/发送方/载荷/重试策略） | `MISSING_FIELD` |
| 声明目标链与实际目标链不一致 | `CHAIN_ID_MISMATCH` |
| `validUntil` 早于当前高度 | `EXPIRED` |
| `validFrom` 晚于当前高度 | `NOT_YET_VALID` |
| 发送方未登记 | `UNREGISTERED_SENDER` |
| 消息凭证损坏/被篡改/验签失败 | `INVALID_SIGNATURE` |
| 凭证有效但与声明发送方不一致 | `CREDENTIAL_SENDER_MISMATCH` |
| 区块头授权损坏/伪造 | `INVALID_SIGNATURE` |
| 授权主体不在验证者集合 | `AUTHORIZER_NOT_AUTHORIZED` |
| 同一验证者重复计入 | `DUPLICATE_AUTHORIZER` |
| 有效验证者授权数低于阈值 | `INSUFFICIENT_VALIDATORS` |
| 来源区块头已被重组取代 | `SOURCE_HEADER_SUPERSEDED` |
| 同一处理路径重入 | `REENTRANT_CALL` |
| 证明未覆盖 `outbox:<id>` 或证明值与内容哈希不符 | `PROOF_NOT_COVERING` |
| 证明重算根与区块头状态根不一致 | `STATE_ROOT_MISMATCH` |
| 同一消息重复投递 | `REPLAY` |
| 相同 id 不同内容 | `ID_COLLISION` |
| 执行消耗超过 `gasLimit` | 进入 `RETRYABLE`（非终态） |
| 重试次数耗尽 | `FAILED_FINAL` + `RETRIES_EXHAUSTED` |
| 重组触及已最终确定区块 | 链拒绝重组（`reorg`/`handleReorg` 返回 -1） |

## 本地验证方法

```bash
# 编译
mvn compile

# 运行全部测试（46 个用例，覆盖凭证伪造、重入、重试/重组再验证、重组与并发交错）
mvn test

# 单独运行某类场景
mvn test -Dtest=CredentialSecurityTest       # 不可伪造凭证：篡改/身份错配/未登记
mvn test -Dtest=LightClientVerificationTest  # 区块头授权伪造/越权/重复/不足 + 正统头取代
mvn test -Dtest=ReentrancyTest               # 同路径重入检测与跨线程不阻塞
mvn test -Dtest=RetryRevalidationTest        # 重试与重组后重新提交的来源再验证
mvn test -Dtest=ReorgFinalityTest            # 重组精度与最终性
mvn test -Dtest=ReorgConcurrencyTest         # 重组与并发投递交错、全顺序枚举、双花防护
mvn test -Dtest=ConcurrencyTest              # 多通道并发投递与顺序无关性
mvn test -Dtest=ReplayProtectionTest         # 重复投递与重放防护
mvn test -Dtest=GasAndRetryTest              # 手续费与资源限额
mvn test -Dtest=SubmissionValidationTest     # 提交校验
```

测试日志（`java.util.logging`）中包含消息标识与判定依据，例如：

```
msg=r1 重组撤销: 执行高度=3 当前链头=2 判定=REVERTED
msg=x2 重试前来源验证失败: 原因=SOURCE_HEADER_SUPERSEDED 目标链状态不变
msg=p1 拒绝: 原因=REPLAY 依据=消息已投递过: id=p1
msg=e1 拒绝: 原因=REENTRANT_CALL 依据=检测到同一路径重入: 上一次投递尚未结束
```
