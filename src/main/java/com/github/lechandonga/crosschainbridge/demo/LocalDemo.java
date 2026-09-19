package com.github.lechandonga.crosschainbridge.demo;

import com.github.lechandonga.crosschainbridge.bridge.*;
import com.github.lechandonga.crosschainbridge.chain.FinalizedRollbackException;
import com.github.lechandonga.crosschainbridge.crypto.HashUtils;
import com.github.lechandonga.crosschainbridge.model.*;
import com.github.lechandonga.crosschainbridge.support.DemoMessages;

import java.util.List;

/**
 * 本地双模拟链端到端演示。运行：{@code mvn -q exec:java} 不可用时可直接用：
 * <pre>
 *   mvn -q compile exec:java -Dexec.mainClass=com.github.lechandonga.crosschainbridge.demo.LocalDemo
 * </pre>
 * 若未安装 exec 插件，可在 IDE 中直接运行本类的 main。
 */
public final class LocalDemo {

    private LocalDemo() {
    }

    public static void main(String[] args) {
        long now = 1_000_000L;
        TwoChainSystem system = new TwoChainSystem(3);
        system.stateA().credit("alice", 1_000L);
        system.stateB().credit("alice", 1_000L);

        section("1) 正常跨链消息 A -> B");
        BridgeMessage m1 = DemoMessages.transfer("demo-1", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "bob", 200L, now, 100_000L);
        SignedMessage s1 = system.endpointA().originate(m1, now);
        List<MessageProof> p1 = system.endpointA().dispatchBatch(List.of(s1), system.endpointB());
        SubmitResult r1 = system.endpointB().submit(p1.get(0), now + 10L);
        print("提交结果", r1);
        print("B 链余额 alice/bob", system.stateB().balanceOf("alice") + " / "
                + system.stateB().balanceOf("bob"));

        section("2) 重复投递被幂等拒绝");
        SubmitResult r1dup = system.endpointB().submit(p1.get(0), now + 20L);
        print("重复提交结果", r1dup);

        section("3) 超出资源限额进入可重试，补足费用后成功");
        BridgeMessage m2 = DemoMessages.transfer("demo-2", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "carol", 50L, now, 100_000L);
        BridgeMessage m2gas = DemoMessages.withGas("demo-2", m2, 200L, 100L);
        SignedMessage s2 = system.endpointA().originate(m2gas, now);
        List<MessageProof> p2 = system.endpointA().dispatchBatch(List.of(s2), system.endpointB());
        SubmitResult r2 = system.endpointB().submit(p2.get(0), now + 10L);
        print("首次提交", r2);
        system.endpointB().addFeeTopUp("demo-2", 150L);
        SubmitResult r2retry = system.endpointB().retry("demo-2", now + 30L);
        print("补足费用后重试", r2retry);

        section("4) 未最终消息在来源链重组后被撤销，已执行账务被回滚");
        // demo-3 在新区块出块、在 B 执行；随后链在它父块处分叉，该块被替换（尚未最终）
        BridgeMessage m3 = DemoMessages.transfer("demo-3", ChainId.CHAIN_A, ChainId.CHAIN_B,
                "alice", "dave", 70L, now, 100_000L);
        SignedMessage s3 = system.endpointA().originate(m3, now);
        List<MessageProof> p3 = system.endpointA().dispatchBatch(List.of(s3), system.endpointB());
        print("执行", system.endpointB().submit(p3.get(0), now + 40L));
        long forkHeight = system.chainA().headHeight() - 1;
        system.chainA().reorg(forkHeight, List.of(List.of(HashUtils.sha256("fork-tx"))));
        system.lightClientOnB().ingestHeader(system.chainA().headerAt(system.chainA().headHeight()));
        ReorgResult reorg = system.endpointB().handleReorg(forkHeight, now + 50L);
        print("撤销消息", reorg.revoked());
        print("保留的最终消息", reorg.finalizedKept());
        print("dave 余额（应为 0，已回滚）", system.stateB().balanceOf("dave"));
        print("bob 余额（demo-1 早于分叉点，仍为 200）", system.stateB().balanceOf("bob"));

        section("5) 最终确定的区块不得回滚");
        system.chainA().finalizeUpTo(system.chainA().headHeight());
        boolean rejected = false;
        try {
            system.chainA().reorg(0L, List.of(List.of(HashUtils.sha256("evil"))));
        } catch (FinalizedRollbackException e) {
            rejected = true;
            print("来源链拒绝回滚", e.getMessage());
        }
        print("最终区块回滚是否被拒", rejected);

        section("完成");
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    private static void print(String label, Object value) {
        System.out.println("  " + label + ": " + value);
    }
}
