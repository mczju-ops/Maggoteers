package io.mczju.maggoteers.item.interact;

import com.github.mczjuops.mczjugamecore.game.AbstractGame;
import io.mczju.maggoteers.effect.AbilityStep;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.EffectKeys;
import io.mczju.maggoteers.effect.EffectService;
import io.mczju.maggoteers.effect.ItemAbility;
import io.mczju.maggoteers.effect.ItemAbilityRegistry;
import io.mczju.maggoteers.effect.PlayerCombatStats;
import io.mczju.maggoteers.item.fx.MagicUseFx;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.item.CooldownService;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.item.fx.MagicFxConfig;
import io.mczju.maggoteers.item.fx.MagicFxService;
import io.mczju.maggoteers.config.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Config-driven magic weapon right-click ({@code use_ability} on items YAML). */
public final class ConfigMagicHandler implements ItemUseHandler {

    @Override
    public void onUse(Player player, AbstractGame game, ItemStack stack) {
        String itemId = ItemService.itemIdOf(stack);
        if (itemId == null) {
            return;
        }
        ItemAbility ability = ItemAbilityRegistry.get(itemId).orElse(null);
        if (ability == null) {
            return;
        }
        // 2026-10-28：冷却中右键**不再提示**（快速 CD 武器下反复弹字影响手感），静默忽略。
        if (CooldownService.onCooldown(player.getUniqueId(), itemId)) {
            return;
        }
        if (!(game instanceof MaggoteersGame mg)) {
            return;
        }
        double effectiveSec = PlayerCombatStats.effectiveCooldownSec(mg, player.getUniqueId(),
                ability.cooldownSec());                      // ① 执行前快照
        // 2026-10-28（吟游诗人）：每第 N 次**成功**使用把伤害 ×multiplier。
        io.mczju.maggoteers.state.PlayerState st =
                io.mczju.maggoteers.state.PlayerStateManager.get(mg, player.getUniqueId());
        int useIndex = (st == null ? 0 : st.weaponUses(itemId)) + 1;
        ItemAbilityRegistry.Burst burst = ItemAbilityRegistry.burstOf(itemId);
        boolean empowered = burst != null && burst.every() > 0 && useIndex % burst.every() == 0;
        ItemAbility effective = empowered
                ? ability.withDamageMultiplier(burst.multiplier()) : ability;
        boolean success = EffectService.executeAbility(player, mg, effective);
        if (!success) {
            return;                                          // ② 失败/空放不进 CD
        }
        if (st != null) {
            st.incrementWeaponUse(itemId);
        }
        CooldownService.tryUse(player, stack, itemId, effectiveSec);   // ③ 用快照
        boolean areaRing = false;
        for (AbilityStep step : effective.steps()) {
            if (!step.isDeferred()
                    && MagicFxConfig.shouldPlayCastAreaRing(step.effect(), step.params())) {
                areaRing = true;
                break;
            }
        }
        MagicFxService.play(player, resolveCastFx(effective), areaRing);
        if (effective.consume()) {
            ItemService.spendOneFromMainHand(player);
        }
    }

    private static MagicUseFx resolveCastFx(ItemAbility ability) {
        MagicUseFx fx = ability.fx();
        if (fx == null) {
            return null;
        }
        for (AbilityStep step : ability.steps()) {
            if (step.isDeferred() || step.effect() != Effect.HEAL_AREA || step.params() == null) {
                continue;
            }
            double radius = step.params().getOrDefault(EffectKeys.RADIUS, fx.radius());
            if (radius > 0 && radius != fx.radius()) {
                return new MagicUseFx(
                        fx.sound(), fx.soundVolume(), fx.soundPitch(), fx.preset(), fx.particle(),
                        radius, fx.rayLength(), fx.density(), fx.rippleRings(), fx.expandSteps(),
                        fx.spiralTicks(), fx.durationTicks());
            }
        }
        return fx;
    }
}
