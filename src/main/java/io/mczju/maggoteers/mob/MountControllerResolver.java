package io.mczju.maggoteers.mob;

import io.mczju.maggoteers.wave.PassengerSpawn;
import org.bukkit.entity.Camel;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/** 骑乘小队 AI 控制实体（§1.7 spec + controllerCandidates 纯候选链）。 */
public final class MountControllerResolver {

    private MountControllerResolver() {}

    /** 运行时：按候选链取第一个可用 Mob；全不可用 → root。签名不变。 */
    public static LivingEntity resolve(LivingEntity root,
                                       List<LivingEntity> directPassengersInOrder,
                                       List<PassengerSpawn> passengerSpec) {
        if (passengerSpec == null || passengerSpec.isEmpty()) return root;
        boolean rootIsCamel = root instanceof Camel;
        for (List<Integer> path : controllerCandidates(passengerSpec, rootIsCamel)) {
            LivingEntity e = walkPath(root, directPassengersInOrder, passengerSpec, path);
            if (e instanceof Mob m && m.isValid() && !m.isDead()) return m;
        }
        return root;
    }

    /**
     * 纯候选链（无 Bukkit 依赖，可单测）。
     * 候选 = 显式段（DFS 序全部 controller()==true 路径）+ 默认回落段（2/2.5/3，去重）。
     */
    static List<List<Integer>> controllerCandidates(List<PassengerSpawn> spec, boolean rootIsCamel) {
        List<List<Integer>> out = new ArrayList<>();
        collectExplicit(spec, List.of(), out);
        for (List<Integer> path : defaultChain(spec, rootIsCamel)) {
            if (!out.contains(path)) out.add(path);
        }
        return out;
    }

    private static void collectExplicit(List<PassengerSpawn> nodes, List<Integer> prefix,
                                        List<List<Integer>> out) {
        for (int i = 0; i < nodes.size(); i++) {
            PassengerSpawn n = nodes.get(i);
            List<Integer> path = new ArrayList<>(prefix);
            path.add(i);
            if (n.controller()) out.add(path);
            if (!n.passengers().isEmpty()) collectExplicit(n.passengers(), path, out);
        }
    }

    /** 默认回落链：camel 双叶→[0]；camel 嵌套→[前座子树最深, 整树最深]；非 camel→整树最深。 */
    private static List<List<Integer>> defaultChain(List<PassengerSpawn> spec, boolean rootIsCamel) {
        List<List<Integer>> out = new ArrayList<>();
        if (rootIsCamel) {
            if (spec.size() == 2
                    && spec.get(0).passengers().isEmpty()
                    && spec.get(1).passengers().isEmpty()) {
                out.add(List.of(0));          // 规则 2 双叶短路
                return out;
            }
            if (!spec.isEmpty()) {            // 规则 2.5：先前座子树，再整树
                List<Integer> firstSub = deepestLeafPath(spec.get(0), List.of(0));
                out.add(firstSub);
                List<Integer> whole = deepestLeafInList(spec, List.of());
                if (!whole.equals(firstSub)) out.add(whole);
                return out;
            }
            return out;
        }
        if (!spec.isEmpty()) {                // 规则 3
            out.add(deepestLeafInList(spec, List.of()));
        }
        return out;
    }

    /** 单个节点子树的最深叶路径；tie 取 DFS 序较后者（对齐现代码 deepestMob 的 >=）。 */
    private static List<Integer> deepestLeafPath(PassengerSpawn node, List<Integer> prefix) {
        List<Integer> best = prefix;
        int bestDepth = prefix.size();
        List<PassengerSpawn> nested = node.passengers();
        for (int i = 0; i < nested.size(); i++) {
            List<Integer> childPath = new ArrayList<>(prefix);
            childPath.add(i);
            List<Integer> cand = deepestLeafPath(nested.get(i), childPath);
            if (cand.size() >= bestDepth) {
                best = cand;
                bestDepth = cand.size();
            }
        }
        return best;
    }

    /** 多个子树整树最深叶路径；tie 取 DFS 序较前者（对齐现代码 collectDepth+max 的 first）。 */
    private static List<Integer> deepestLeafInList(List<PassengerSpawn> spec, List<Integer> prefix) {
        List<Integer> best = null;
        int bestDepth = -1;
        for (int i = 0; i < spec.size(); i++) {
            List<Integer> childPath = new ArrayList<>(prefix);
            childPath.add(i);
            List<Integer> cand = deepestLeafPath(spec.get(i), childPath);
            if (best == null || cand.size() > bestDepth) {
                best = cand;
                bestDepth = cand.size();
            }
        }
        return best;
    }

    /** 沿候选路径取实体（spec 树索引对齐实体树；越界/缺失 → null，落到下一候选/root）。 */
    private static LivingEntity walkPath(LivingEntity root, List<LivingEntity> direct,
                                         List<PassengerSpawn> spec, List<Integer> path) {
        List<LivingEntity> children = direct;
        for (int i = 0; i < path.size(); i++) {
            int idx = path.get(i);
            if (idx >= children.size()) return null;
            LivingEntity cur = children.get(idx);
            if (i == path.size() - 1) return cur;
            children = cur.getPassengers().stream()
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .toList();
        }
        return null;
    }
}
