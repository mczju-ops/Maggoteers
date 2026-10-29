package io.mczju.maggoteers.reward;

import io.mczju.maggoteers.effect.BoundEquipParams;
import io.mczju.maggoteers.effect.Effect;
import io.mczju.maggoteers.effect.PlayerEffect;
import io.mczju.maggoteers.game.MaggoteersGame;
import io.mczju.maggoteers.config.MessageService;
import io.mczju.maggoteers.item.ItemService;
import io.mczju.maggoteers.state.PlayerState;
import io.mczju.maggoteers.state.PlayerStateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public final class RewardOptionIcons {

    private RewardOptionIcons() {}

    public static ItemStack preview(RewardOption opt, Player viewer, MaggoteersGame game) {
        ItemStack stack = resolveBaseStack(opt, viewer, game);
        return appendGuiLore(stack, opt);
    }

    private static ItemStack resolveBaseStack(RewardOption opt, Player viewer, MaggoteersGame game) {
        if (opt.isBundle()) {
            // collectibles.yml maps the parent bundle id, not grant child ids
            ItemStack parentCharm = CollectibleService.preview(opt.id(), viewer, game).orElse(null);
            if (parentCharm != null && parentCharm.getType() != Material.PAPER) {
                return parentCharm;
            }
            for (RewardOption grant : opt.grants()) {
                ItemStack g = grantStack(grant, viewer, game);
                if (g != null && g.getType() != Material.PAPER) {
                    return g;
                }
            }
            return fallbackPaper(opt);
        }
        return grantStack(opt, viewer, game);
    }

    private static ItemStack grantStack(RewardOption opt, Player viewer, MaggoteersGame game) {
        return switch (opt.category()) {
            case WEAPON, SUPPLY -> ItemService.createItem(opt.item(), 1)
                    .orElse(new ItemStack(Material.PAPER));
            case STAT -> {
                if (opt.effect() == Effect.BOUND_EQUIP) {
                    yield boundEquipPreview(opt, viewer, game).orElse(fallbackPaper(opt));
                }
                if (game != null) {
                    yield CollectibleService.preview(opt.id(), viewer, game)
                            .orElse(fallbackPaper(opt));
                }
                yield CollectibleService.preview(opt.id(), 1)
                        .orElse(fallbackPaper(opt));
            }
            case BUNDLE -> fallbackPaper(opt);
        };
    }

    private static Optional<ItemStack> boundEquipPreview(
            RewardOption opt, Player viewer, MaggoteersGame game) {
        int owned = 0;
        int upgradeMax = Math.max(1, opt.upgradeMax());
        if (viewer != null && game != null) {
            PlayerState ps = PlayerStateManager.get(game, viewer.getUniqueId());
            if (ps != null) {
                owned = ps.effects().stream().filter(e -> e.id().equals(opt.id()))
                        .mapToInt(PlayerEffect::level).max().orElse(0);
            }
        }
        Integer ownedOrNull = owned > 0 ? owned : null;
        int level = BoundEquipParams.previewLevel(ownedOrNull, upgradeMax);
        return BoundEquipParams.itemIdForLevel(opt.params(), level)
                .flatMap(id -> ItemService.createItem(id, 1));
    }

    public static ItemStack fallbackPaper(RewardOption opt) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(opt.displayPlain(), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack appendGuiLore(ItemStack stack, RewardOption opt) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        var lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<Component>();
        if (opt.description() != null && !opt.description().isBlank()) {
            lore.add(Component.text(opt.description(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (opt.isBundle()) {
            for (RewardOption grant : opt.grants()) {
                String line = grantSummary(grant);
                if (!line.isBlank()) {
                    lore.add(Component.text("\u00b7 " + line, NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        lore.add(MessageService.component("reward.click_choose", Map.of())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String grantSummary(RewardOption grant) {
        return switch (grant.category()) {
            case WEAPON, SUPPLY -> Optional.ofNullable(grant.item()).orElse("\u7269\u54c1");
            case STAT, BUNDLE -> grant.displayPlain();
        };
    }
}