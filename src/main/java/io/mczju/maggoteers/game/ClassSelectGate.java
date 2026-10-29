package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.reward.RewardOption;
import org.bukkit.entity.Player;

import java.util.*;

/** 开局职业选择门闩（全员选完才开波）。 */
public final class ClassSelectGate {
    private static final Map<AbstractGame, Set<UUID>> CHOSEN = new IdentityHashMap<>();

    private ClassSelectGate() {}

    public static void reset(AbstractGame game) {
        CHOSEN.put(game, new HashSet<>());
    }

    public static void markChosen(AbstractGame game, UUID uuid) {
        CHOSEN.computeIfAbsent(game, g -> new HashSet<>()).add(uuid);
    }

    public static boolean hasChosen(AbstractGame game, UUID uuid) {
        Set<UUID> s = CHOSEN.get(game);
        return s != null && s.contains(uuid);
    }

    public static boolean allChosen(AbstractGame game) {
        return game.getPlayers().stream()
                .allMatch(pe -> hasChosen(game, pe.player().getUniqueId()));
    }

    public static void clear(AbstractGame game) { CHOSEN.remove(game); }

    /** 超时兜底：为未选玩家各自从 class 池随机抽 1 个职业并应用。 */
    public static void autoPickRemaining(AbstractGame game, Random rng) {
        for (var pe : game.getPlayers()) {
            UUID id = pe.player().getUniqueId();
            if (hasChosen(game, id)) continue;
            Player p = pe.player();
            List<RewardOption> drawn = io.mczju.maggoteers.reward.RewardService.draw("class", 1, rng, p);
            if (drawn.isEmpty()) continue;
            if (!io.mczju.maggoteers.item.ItemService.spendOneKind(p, io.mczju.maggoteers.item.ItemKind.CLASS_TICKET)) {
                io.mczju.maggoteers.item.ItemService.giveKind(p, io.mczju.maggoteers.item.ItemKind.CLASS_TICKET, 1);
                io.mczju.maggoteers.item.ItemService.spendOneKind(p, io.mczju.maggoteers.item.ItemKind.CLASS_TICKET);
            }
            io.mczju.maggoteers.reward.RewardService.apply(p, drawn.get(0), game, "class");
            markChosen(game, id);
        }
    }
}
