package io.mczju.maggoteers.effect;

import io.mczju.maggoteers.game.MaggoteersGame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 计数条件修饰符（AND/OR/NOT 组合 + 叶子条件）。evaluate 以玩家为中心（玩家被动侧）。
 * <p>怪物侧的条件以怪物为中心判断，见 {@code MobCondition}（Task 4）。
 */
public sealed interface CounterCondition {

    boolean evaluate(MaggoteersGame game, Player player);

    /** 全部子条件为真。 */
    record And(List<CounterCondition> children) implements CounterCondition {
        public And {
            children = children == null ? List.of() : List.copyOf(children);
        }
        @Override public boolean evaluate(MaggoteersGame game, Player player) {
            for (CounterCondition c : children) {
                if (!c.evaluate(game, player)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 任一子条件为真。 */
    record Or(List<CounterCondition> children) implements CounterCondition {
        public Or {
            children = children == null ? List.of() : List.copyOf(children);
        }
        @Override public boolean evaluate(MaggoteersGame game, Player player) {
            for (CounterCondition c : children) {
                if (c.evaluate(game, player)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 子条件取反。 */
    record Not(CounterCondition child) implements CounterCondition {
        @Override public boolean evaluate(MaggoteersGame game, Player player) {
            return child != null && !child.evaluate(game, player);
        }
    }

    /** 手持物品 PDC {@code maggoteers:id} 匹配。 */
    record HoldItemPdc(String pdcValue) implements CounterCondition {
        @Override public boolean evaluate(MaggoteersGame game, Player player) {
            if (player == null) {
                return false;
            }
            ItemStack held = player.getInventory().getItemInMainHand();
            String id = io.mczju.maggoteers.item.ItemService.itemIdOf(held);
            return pdcValue != null && pdcValue.equals(id);
        }
    }

    /** 半径内存在<b>其它</b>玩家实体（以被评估玩家位置为中心）。 */
    record PlayerInRadius(double radius) implements CounterCondition {
        @Override public boolean evaluate(MaggoteersGame game, Player player) {
            if (player == null || game == null) {
                return false;
            }
            double r = Math.max(0.0, radius);
            for (var pe : game.getPlayers()) {
                Player other = pe.player();
                if (other == null || other.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (!other.getWorld().equals(player.getWorld())) {
                    continue;
                }
                if (other.getLocation().distanceSquared(player.getLocation()) <= r * r) {
                    return true;
                }
            }
            return false;
        }
    }
}
