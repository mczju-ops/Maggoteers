package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.state.PlayerStateManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 局内友军选取（以 PlayerState 为准，不依赖 MGC {@code getPlayers()}）。 */
public final class AllyTargeting {

    /** beam 治疗命中余量：玩家 hitbox 半径约 0.6，取 0.4 保守（配合 beam_radius 1.0 覆盖"站线上"的队友）。 */
    static final double HITBOX_EXTRA = 0.4;

    private AllyTargeting() {}

    public static MaggoteersGame resolveForPlayer(Player player, AbstractGame hint) {
        if (player == null) return null;
        MaggoteersGame bound = PlayerStateManager.gameOf(player);
        if (bound != null) return bound;
        if (hint instanceof MaggoteersGame mg) return mg;
        for (MaggoteersGame mg : ActiveMaggoteersGames.snapshot()) {
            if (PlayerStateManager.get(mg, player.getUniqueId()) != null) return mg;
        }
        return null;
    }

    /** 半径内存活的局内玩家；{@code includeSelf=true} 时包含施法者（单人有效）。 */
    public static List<Player> alliesInRadius(MaggoteersGame mg, Player origin, double radius, boolean includeSelf) {
        List<Player> out = new ArrayList<>();
        if (origin == null) return out;
        if (mg == null) mg = PlayerStateManager.gameForPlayer(origin.getUniqueId());
        if (mg == null) return out;

        Set<UUID> seen = new HashSet<>();
        for (UUID uuid : PlayerStateManager.uuidsInGame(mg)) {
            tryAdd(out, seen, mg, origin, Bukkit.getPlayer(uuid), radius, includeSelf);
        }
        if (includeSelf) {
            tryAdd(out, seen, mg, origin, origin, radius, true);
        }
        return out;
    }

    /** Allies within {@code radius} of {@code center} (for death-site area effects). */
    public static List<Player> alliesNear(MaggoteersGame mg, org.bukkit.Location center,
                                          double radius, Player caster, boolean includeSelf) {
        List<Player> out = new ArrayList<>();
        if (center == null || center.getWorld() == null) return out;
        if (mg == null && caster != null) {
            mg = PlayerStateManager.gameForPlayer(caster.getUniqueId());
        }
        if (mg == null) return out;

        Set<UUID> seen = new HashSet<>();
        for (UUID uuid : PlayerStateManager.uuidsInGame(mg)) {
            Player candidate = org.bukkit.Bukkit.getPlayer(uuid);
            if (candidate == null || seen.contains(uuid)) continue;
            if (!includeSelf && caster != null && candidate.getUniqueId().equals(caster.getUniqueId())) {
                continue;
            }
            if (candidate.getWorld() != center.getWorld()) continue;
            if (candidate.getLocation().distance(center) > radius) continue;
            if (!PlayerStateManager.isRunParticipant(mg, candidate)) continue;
            seen.add(uuid);
            out.add(candidate);
        }
        if (includeSelf && caster != null && !seen.contains(caster.getUniqueId())
                && PlayerStateManager.isRunParticipant(mg, caster)
                && caster.getWorld() == center.getWorld()
                && caster.getLocation().distance(center) <= radius) {
            out.add(caster);
        }
        return out;
    }

    /**
     * Beam 治疗选取：以「玩家 hitbox（近似圆柱，中心=脚底+0.9）到 beam 线段的最短垂线距离 ≤
     * {@code radius + HITBOX_EXTRA}」判定。
     * <p>不能复用 {@link #alliesNear}——它以「玩家脚底中心 → 采样点」的 3D 球距离判定，而采样点位于
     * 眼睛高度（站立时与脚底差 ~1.62 格），beam_radius 默认 1.0 时站立玩家永远不命中（2026-08-22 治疗法杖修复）。
     */
    public static List<Player> alliesOnBeam(MaggoteersGame mg, org.bukkit.Location eye, Vector dir,
                                            double length, double radius, Player caster, boolean includeSelf) {
        List<Player> out = new ArrayList<>();
        if (eye == null || eye.getWorld() == null || dir == null) return out;
        if (mg == null && caster != null) {
            mg = PlayerStateManager.gameForPlayer(caster.getUniqueId());
        }
        if (mg == null) return out;

        Vector d = dir.clone().normalize();
        Vector e = eye.toVector();
        Set<UUID> seen = new HashSet<>();
        for (UUID uuid : PlayerStateManager.uuidsInGame(mg)) {
            Player candidate = org.bukkit.Bukkit.getPlayer(uuid);
            if (candidate == null || seen.contains(uuid)) continue;
            if (!includeSelf && caster != null && candidate.getUniqueId().equals(caster.getUniqueId())) {
                continue;
            }
            if (candidate.getWorld() != eye.getWorld()) continue;
            if (!PlayerStateManager.isRunParticipant(mg, candidate)) continue;
            if (distanceToSegment(candidate, e, d, length) <= radius + HITBOX_EXTRA) {
                seen.add(uuid);
                out.add(candidate);
            }
        }
        if (includeSelf && caster != null && !seen.contains(caster.getUniqueId())
                && PlayerStateManager.isRunParticipant(mg, caster)
                && caster.getWorld() == eye.getWorld()
                && distanceToSegment(caster, e, d, length) <= radius + HITBOX_EXTRA) {
            out.add(caster);
        }
        return out;
    }

    /** 玩家 hitbox 中心（脚底+0.9）到 beam 线段 [e, e+d*length] 的最短 3D 距离。 */
    private static double distanceToSegment(Player player, Vector e, Vector d, double length) {
        Vector center = player.getLocation().clone().add(0, 0.9, 0).toVector();
        double t = center.clone().subtract(e).dot(d);
        if (t < 0) t = 0;
        if (t > length) t = length;
        return center.distance(e.clone().add(d.clone().multiply(t)));
    }

    private static void tryAdd(List<Player> out, Set<UUID> seen, MaggoteersGame mg, Player origin,
                               Player candidate, double radius, boolean includeSelf) {
        if (candidate == null || seen.contains(candidate.getUniqueId())) return;
        if (!includeSelf && candidate.getUniqueId().equals(origin.getUniqueId())) return;
        if (candidate.getWorld() != origin.getWorld()) return;
        if (candidate.getLocation().distance(origin.getLocation()) > radius) return;
            if (!PlayerStateManager.isRunParticipant(mg, candidate)) return;
        seen.add(candidate.getUniqueId());
        out.add(candidate);
    }
}
