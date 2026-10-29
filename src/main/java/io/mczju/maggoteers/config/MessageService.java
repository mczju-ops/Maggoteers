package io.mczju.maggoteers.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Player-facing copy from config.yml messages: (MiniMessage + escaped placeholders). */
public final class MessageService {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Spec section 4.2 — load warns if any missing. meta_description requires at least .0 */
    public static final Set<String> REQUIRED_KEYS;
    static {
        Set<String> keys = new LinkedHashSet<>();
        Collections.addAll(keys,
            "game.meta_name",
            "game.meta_author",
            "game.meta_description.0",
            "game.world_create_failed",
            "game.plan_generating",
            "game.plan_timeout",
            "game.plan_failed",
            "game.enter_class_select",
            "game.ready",
            "game.prep_clause",
            "game.settlement_grant",
            "game.defeat",
            "wave.skip_vote",
            "wave.skip_passed",
            "wave.enter_act",
            "wave.prep",
            "wave.begin",
            "wave.strategy_clause",
            "wave.cleared",
            "wave.cleared_rewarded",
            "wave.final_rest",
            "wave.rest_hint",
            "wave.victory",
            // 隐藏结局层（Act4，2026-10-26 阶段 5）
            "wave.hidden_key_hint",
            "wave.hidden_enter",
            "wave.hidden_key_denied",
            "wave.phase_switch",
            "wave.phase_prep",
            "wave.phase_spawning",
            "wave.phase_active",
            "wave.phase_rest",
            "wave.phase_done",
            "wave.phase_defeated",
            "death.missing_state",
            "death.auto_revive",
            "death.to_spectator",
            "death.to_down_quit",
            "menu.rest.title",
            "menu.rest.normal_name",
            "menu.rest.normal_lore",
            "menu.rest.boss_name",
            "menu.rest.boss_lore",
            "menu.rest.skip_name",
            "menu.rest.skip_lore",
            "menu.rest.held",
            "menu.rest.click",
            "menu.rest.pool_missing",
            "menu.rest.no_offers",
            "menu.rest.not_enough_currency",
            "menu.rest.rest_only",
            "menu.pick.title",
            "menu.pick.charge_error",
            "menu.pick.apply_failed",
            "menu.class.title",
            "menu.class.need_ticket",
            "menu.class.chosen",
            "menu.revive.title",
            "menu.revive.no_coin",
            "menu.revive.not_needed",
            "menu.revive.revived",
            "menu.revive.add_life",
            "menu.revive.head_down",
            "menu.revive.head_alive",
            "menu.shop.title",
            "menu.shop.already_owned",
            "menu.shop.not_enough",
            "menu.shop.unlocked",
            "menu.shop.lore_owned",
            "menu.shop.lore_cost",
            "menu.shop.prev_name",
            "menu.shop.next_name",
            "menu.shop.page_lore",
            "menu.shop.page_edge",
            "menu.shop.confirm_title",
            "menu.shop.confirm_item",
            "menu.shop.confirm_item_lore",
            "menu.shop.confirm_ok",
            "menu.shop.confirm_ok_lore",
            "menu.shop.confirm_cancel",
            "menu.shop.confirm_cancel_lore",
            "menu.achievement.legendary_title",
            "menu.achievement.legendary_subtitle",
            "menu.achievement.legendary_broadcast",
            "menu.achievement.legendary_sound",
            "menu.achievement.legendary_stinger",
            // 图鉴（/maggoteers codex，2026-10-30）
            "menu.codex.title",
            "menu.codex.weapon_title",
            "menu.codex.collectible_title",
            "menu.codex.boss_title",
            "menu.codex.affix_title",
            "menu.codex.weapon_name",
            "menu.codex.weapon_lore",
            "menu.codex.collectible_name",
            "menu.codex.collectible_lore",
            "menu.codex.boss_name",
            "menu.codex.boss_lore",
            "menu.codex.affix_name",
            "menu.codex.affix_lore",
            "menu.codex.exit_name",
            "menu.codex.exit_lore",
            "menu.codex.back_name",
            "menu.codex.back_lore",
            "menu.codex.prev_name",
            "menu.codex.prev_lore",
            "menu.codex.next_name",
            "menu.codex.next_lore",
            "menu.codex.page_edge",
            "menu.codex.locked_name",
            "menu.codex.locked_lore_weapon",
            "menu.codex.locked_lore_collectible",
            "menu.codex.unseen_weapon",
            "menu.codex.unseen_collectible",
            "menu.codex.boss_locked_name",
            "menu.codex.boss_locked_lore",
            "menu.codex.hp",
            "menu.codex.page_progress",
            "menu.codex.page_index",
            "menu.codex.act_row",
            "menu.codex.act_row_lore",
            "item.rest_only",
            "item.shop_emerald_rest_only",
            "item.class_unavailable",
            "item.class_already",
            "item.ability_cooldown",
            "item.heal_full",
            "item.heal_amount",
            "reward.gained",
            "reward.apply_no_state",
            "reward.apply_bad_bundle",
            "reward.apply_failed",
            "reward.apply_error",
            "reward.click_choose",
            "reward.revive_trade",
            "scoreboard.title",
            "scoreboard.line_act",
            "scoreboard.line_wave",
            "scoreboard.line_wave_resting",
            "scoreboard.line_strategy",
            "scoreboard.line_mobs",
            "scoreboard.separator",
            "scoreboard.counter_line",
            "scoreboard.counter_ready",
            "scoreboard.status_fight",
            "scoreboard.status_spec",
            "scoreboard.player_line",
            "scoreboard.class_vanguard",
            "scoreboard.class_mage",
            "scoreboard.class_sniper",
            "scoreboard.class_heavy",
            "scoreboard.class_medical",
            "scoreboard.class_guard",
            "scoreboard.class_header",
            "scoreboard.class_hint",
            "scoreboard.class_chosen",
            "scoreboard.class_pending",
            "scoreboard.time_record_best",
            "scoreboard.time_record_top",
            "leaderboard.title",
            "leaderboard.subtitle",
            "leaderboard.empty",
            "leaderboard.time_title",
            "leaderboard.time_subtitle",
            "leaderboard.time_line",
            "leaderboard.no_record",
            "command.time_self",
            "command.time_none",
            "command.time_empty",
            "command.time_list_title",
            "command.time_list_line",
            "command.players_only",
            "command.usage",
            "command.reload_ok",
            "command.reload_fail",
            "command.reload_no_perm"
        );
        REQUIRED_KEYS = Collections.unmodifiableSet(keys);
    }

    private static volatile Map<String, String> MESSAGES = Map.of();
    private static final Set<String> WARNED_MISSING = ConcurrentHashMap.newKeySet();
    private static Logger logger;

    private MessageService() {}

    public static void load(JavaPlugin plugin) {
        logger = plugin.getLogger();
        loadFromSection(plugin.getConfig().getConfigurationSection("messages"));
    }

    public static void loadFromSection(ConfigurationSection root) {
        Map<String, String> flat = new HashMap<>();
        if (root != null) {
            flatten("", root, flat);
        }
        MESSAGES = Map.copyOf(flat);
        WARNED_MISSING.clear();
        List<String> missing = new ArrayList<>();
        for (String k : REQUIRED_KEYS) {
            if (!MESSAGES.containsKey(k)) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            warn("MessageService: missing " + missing.size()
                    + " messages keys (see messages.<key> fallback): " + missing);
        }
    }

    public static Set<String> requiredKeys() {
        return REQUIRED_KEYS;
    }

    public static boolean hasKey(String key) {
        return MESSAGES.containsKey(key);
    }

    public static String raw(String key, Map<String, String> ph) {
        return applyPlaceholders(template(key), ph);
    }

    public static Component component(String key, Map<String, String> ph) {
        return MM.deserialize(raw(key, ph));
    }

    public static String legacy(String key, Map<String, String> ph) {
        return LEGACY.serialize(component(key, ph));
    }

    public static List<String> rawList(String key) {
        List<String> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            String k = key + "." + i;
            if (!MESSAGES.containsKey(k)) {
                break;
            }
            out.add(MESSAGES.get(k));
        }
        if (out.isEmpty()) {
            if (WARNED_MISSING.add(key)) {
                warn("MessageService: missing list messages." + key);
            }
        }
        return List.copyOf(out);
    }

    private static String template(String key) {
        if (!MESSAGES.containsKey(key)) {
            String lit = "messages." + key;
            if (WARNED_MISSING.add(key)) {
                warn("MessageService: missing key " + lit);
            }
            return lit;
        }
        return MESSAGES.get(key);
    }

    private static String applyPlaceholders(String template, Map<String, String> ph) {
        if (ph == null || ph.isEmpty()) {
            return template;
        }
        String s = template;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            String val = e.getValue() == null ? "" : MM.escapeTags(e.getValue());
            s = s.replace("{" + e.getKey() + "}", val);
        }
        return s;
    }

    private static void flatten(String prefix, ConfigurationSection sec, Map<String, String> out) {
        for (String key : sec.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (sec.isConfigurationSection(key)) {
                ConfigurationSection child = sec.getConfigurationSection(key);
                if (child != null) {
                    flatten(path, child, out);
                }
            } else if (sec.isList(key)) {
                List<?> list = sec.getList(key);
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    Object v = list.get(i);
                    out.put(path + "." + i, v == null ? "" : String.valueOf(v));
                }
            } else {
                Object v = sec.get(key);
                out.put(path, v == null ? "" : String.valueOf(v));
            }
        }
    }

    private static void warn(String msg) {
        if (logger != null) {
            logger.warning(msg);
        } else {
            System.err.println(msg);
        }
    }
}
