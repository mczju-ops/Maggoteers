package io.mczju.maggoteers.mob;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 骑乘小队表：按对局作用域隔离（R1）；清理统一（R2）。全部主线程访问。 */
public final class MountedSquadRegistry {

    public record Squad(AbstractGame game, UUID rootUuid, UUID controllerUuid) {}

    private static final Map<AbstractGame, Map<UUID, Squad>> BY_GAME = new IdentityHashMap<>();

    private MountedSquadRegistry() {}

    public static void register(AbstractGame game, LivingEntity root, LivingEntity controller) {
        if (game == null || root == null || controller == null) return;
        BY_GAME.computeIfAbsent(game, k -> new java.util.HashMap<>())
                .put(root.getUniqueId(), new Squad(game, root.getUniqueId(), controller.getUniqueId()));
    }

    public static Squad removeByRoot(AbstractGame game, UUID rootUuid) {
        if (game == null || rootUuid == null) return null;
        Map<UUID, Squad> inner = BY_GAME.get(game);
        return inner == null ? null : inner.remove(rootUuid);
    }

    /** 本局全部 squad 快照（供 AiService 遍历）。 */
    public static List<Squad> squadsFor(AbstractGame game) {
        Map<UUID, Squad> inner = BY_GAME.get(game);
        return inner == null ? List.of() : new ArrayList<>(inner.values());
    }

    /** 整局清空（stop / clearTracking 调用，R2）。 */
    public static void removeGame(AbstractGame game) {
        if (game != null) BY_GAME.remove(game);
    }
}
