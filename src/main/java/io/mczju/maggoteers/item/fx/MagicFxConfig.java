package io.mczju.maggoteers.item.fx;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.effect.AbilityStep;
import io.mczju.maggoteers.effect.ItemAbility;
import io.mczju.maggoteers.util.GameRegistries;
import io.mczju.maggoteers.util.ItemYaml;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 从 {@code config.yml → magic_fx} 与 {@code items/*.yml → use_fx} 加载魔法释放配置。
 * <p>合并顺序：defaults → effect template → {@code magic_fx.weapons} → {@code use_ability.fx}。
 */
public final class MagicFxConfig {
    private static MagicUseFx defaults = MagicUseFx.defaults();
    private static final Map<String, MagicUseFx> BY_TEMPLATE = new HashMap<>();
    private static final Map<String, MagicUseFx> BY_WEAPON = new HashMap<>();

    private MagicFxConfig() {}

    public static void load(MaggoteersPlugin plugin) {
        defaults = parseSection(plugin.getConfig().getConfigurationSection("magic_fx.defaults"), MagicUseFx.defaults());
        BY_TEMPLATE.clear();
        ConfigurationSection templates = plugin.getConfig().getConfigurationSection("magic_fx.templates");
        if (templates != null) {
            for (String key : templates.getKeys(false)) {
                BY_TEMPLATE.put(key, merge(defaults, parseSection(templates.getConfigurationSection(key), defaults)));
            }
        }
        BY_WEAPON.clear();
        ConfigurationSection weapons = plugin.getConfig().getConfigurationSection("magic_fx.weapons");
        if (weapons != null) {
            for (String id : weapons.getKeys(false)) {
                BY_WEAPON.put(id, merge(defaults, parseSection(weapons.getConfigurationSection(id), defaults)));
            }
        }
        loadItemYamlFx(new File(plugin.getDataFolder(), "items"));
        if (plugin.getResource("items/maggoteers.yml") != null) {
            try (var in = plugin.getResource("items/maggoteers.yml")) {
                if (in != null) {
                    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                            new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    mergeYamlItems(yaml);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("读取内置 items/maggoteers.yml use_fx 失败：" + e.getMessage());
            }
        }
        plugin.getLogger().info("MagicFxConfig：默认 preset=" + defaults.preset()
                + "，已绑定 " + BY_WEAPON.size() + " 个武器 FX。");
    }

    private static void loadItemYamlFx(File dir) {
        ItemYaml.forEachYaml(dir, MagicFxConfig::mergeYamlItems);
    }

    private static void mergeYamlItems(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection itemSec = yaml.getConfigurationSection(key);
            if (itemSec == null) continue;
            ConfigurationSection fx = itemSec.getConfigurationSection("use_fx");
            if (fx == null) continue;
            String itemId = ItemYaml.normalizeItemId(key);
            MagicUseFx base = BY_WEAPON.getOrDefault(itemId, defaults);
            BY_WEAPON.put(itemId, merge(base, parseSection(fx, base)));
        }
    }

    /** FX merge: defaults → effect template → config.weapons → use_ability.fx. */
    public static MagicUseFx mergedFxForAbility(String itemId, io.mczju.maggoteers.effect.Effect effect,
                                              io.mczju.maggoteers.effect.EffectContext params,
                                              ConfigurationSection abilityFx) {
        MagicUseFx base = defaults;
        String templateKey = templateKeyForEffect(effect, params);
        if (templateKey != null) {
            MagicUseFx tpl = BY_TEMPLATE.get(templateKey);
            if (tpl != null) {
                base = merge(base, tpl);
            }
        }
        MagicUseFx weaponFx = BY_WEAPON.get(itemId);
        if (weaponFx != null) {
            base = merge(base, weaponFx);
        }
        if (abilityFx == null) {
            return base;
        }
        return merge(base, parseSection(abilityFx, base));
    }

    /**
     * Multi packs: weapons + use_ability.fx only — no effect-template.
     * Legacy single may pass {@code legacyStepOrNull} to keep template merge.
     * If nothing configured, returns null (no magic_fx.defaults spam).
     */
    public static MagicUseFx mergedFxForAbilityMulti(
            String itemId, ConfigurationSection abilityFx, ConfigurationSection legacyUseFx,
            AbilityStep legacyStepOrNull) {
        if (legacyStepOrNull != null) {
            ConfigurationSection sec = abilityFx != null ? abilityFx : legacyUseFx;
            return mergedFxForAbility(itemId, legacyStepOrNull.effect(), legacyStepOrNull.params(), sec);
        }
        MagicUseFx base = null;
        MagicUseFx weapon = BY_WEAPON.get(itemId);
        if (weapon != null) {
            base = weapon;
        }
        ConfigurationSection overlay = abilityFx != null ? abilityFx : legacyUseFx;
        if (overlay != null) {
            MagicUseFx parsed = parseSection(overlay, base);
            base = base == null ? parsed : merge(base, parsed);
        }
        return base;
    }

    /** Parse empower_fx without merging magic_fx.defaults. Null section → null. */
    public static MagicUseFx parseOptionalFx(ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }
        return parseSection(sec, null);
    }

    /**
     * Built-in next-hit strike flash (victim {@code DOT_ABOVE} + crit sound).
     * Used when a deferred-only pack has no {@code empower_fx} and no visible cast fx.
     */
    public static MagicUseFx deferredStrikeFx() {
        return new MagicUseFx(
                Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.95f, 1.2f,
                MagicFxPreset.DOT_ABOVE, Particle.CRIT,
                1.0, 10.0, 36,
                4, 28, 18, 0);
    }

    static boolean isVisibleCastFx(MagicUseFx fx) {
        return fx != null && fx.preset() != null && fx.preset() != MagicFxPreset.NONE;
    }

    /**
     * Empower resolve: empower_fx → visible cast fx → deferred-only strike flash → null.
     * Never falls through to {@code magic_fx.defaults}.
     */
    public static MagicUseFx resolveEmpowerFx(ItemAbility ab) {
        if (ab == null) {
            return null;
        }
        var choice = io.mczju.maggoteers.effect.EmpowerFxCoalesce.choose(
                ab.empowerFx() != null, ab.isDeferredOnly(), isVisibleCastFx(ab.fx()));
        return switch (choice) {
            case EMPOWER -> ab.empowerFx();
            case CAST -> ab.fx();
            case STRIKE -> deferredStrikeFx();
            case NONE -> null;
        };
    }

    static String templateKeyForEffect(io.mczju.maggoteers.effect.Effect effect,
                                       io.mczju.maggoteers.effect.EffectContext params) {
        if (effect == null) {
            return null;
        }
        return switch (effect) {
            case HEAL_AREA -> "heal";
            case DAMAGE_BEAM -> "beam";
            case ADD_ATTRIBUTE, ADD_POTION -> "buff";
            case BUFF_AREA -> {
                if (params == null) {
                    yield null;
                }
                String targets = params.get(io.mczju.maggoteers.effect.EffectKeys.TARGETS);
                if (targets == null) {
                    yield null;
                }
                yield switch (targets.toLowerCase(java.util.Locale.ROOT)) {
                    case "self", "allies" -> "buff";
                    default -> null;
                };
            }
            default -> null;
        };
    }

    /** 右键 cast 时是否允许范围标识环（区域型效果或敌对/友军范围 BUFF/DISABLE_AI）。 */
    public static boolean shouldPlayCastAreaRing(io.mczju.maggoteers.effect.Effect effect,
                                                 io.mczju.maggoteers.effect.EffectContext params) {
        if (effect == null) {
            return false;
        }
        return switch (effect) {
            case HEAL_AREA, DAMAGE_AREA -> true;
            case BUFF_AREA, DISABLE_AI -> {
                if (params == null) {
                    yield false;
                }
                String t = params.getOrDefault(io.mczju.maggoteers.effect.EffectKeys.TARGETS, "")
                        .toLowerCase(Locale.ROOT);
                yield "enemies".equals(t) || "allies".equals(t) || "both".equals(t);
            }
            default -> false;
        };
    }

    /** 配置加载的默认 FX（非 {@link MagicUseFx#defaults()} 的写死值——两者半径等字段不同）。 */
    public static MagicUseFx currentDefaults() {
        return defaults;
    }

    private static MagicUseFx merge(MagicUseFx base, MagicUseFx over) {
        if (base == null) {
            return over;
        }
        if (over == null) {
            return base;
        }
        return new MagicUseFx(
                over.sound() != null ? over.sound() : base.sound(),
                over.soundVolume() > 0 ? over.soundVolume() : base.soundVolume(),
                over.soundPitch() > 0 ? over.soundPitch() : base.soundPitch(),
                over.preset() != null ? over.preset() : base.preset(),
                over.particle() != null ? over.particle() : base.particle(),
                over.radius() > 0 ? over.radius() : base.radius(),
                over.rayLength() > 0 ? over.rayLength() : base.rayLength(),
                over.density() > 0 ? over.density() : base.density(),
                over.rippleRings() > 0 ? over.rippleRings() : base.rippleRings(),
                over.expandSteps() > 0 ? over.expandSteps() : base.expandSteps(),
                over.spiralTicks() > 0 ? over.spiralTicks() : base.spiralTicks(),
                over.durationTicks() > 0 ? over.durationTicks() : base.durationTicks()
        );
    }

    private static MagicUseFx parseSection(ConfigurationSection sec, MagicUseFx fallback) {
        if (sec == null) {
            return fallback;
        }
        Sound sound = fallback != null ? fallback.sound() : null;
        String soundRaw = sec.getString("sound");
        if (soundRaw != null && !soundRaw.isBlank()) {
            Sound parsed = GameRegistries.sound(soundRaw);
            if (parsed != null) {
                sound = parsed;
            }
        }
        MagicFxPreset preset = fallback != null ? fallback.preset() : null;
        String presetRaw = sec.getString("preset");
        if (presetRaw != null && !presetRaw.isBlank()) {
            try {
                preset = MagicFxPreset.valueOf(presetRaw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                MaggoteersPlugin plug = MaggoteersPlugin.getInstance();
                if (plug != null) {
                    plug.getLogger().warning("未知 magic_fx preset: " + presetRaw);
                }
            }
        }
        Particle particle = fallback != null ? fallback.particle() : null;
        String particleRaw = sec.getString("particle");
        if (particleRaw != null && !particleRaw.isBlank()) {
            Particle p = GameRegistries.particle(particleRaw);
            if (p != null) {
                particle = p;
            } else {
                MaggoteersPlugin plug = MaggoteersPlugin.getInstance();
                if (plug != null) {
                    plug.getLogger().warning("无法解析 particle: " + particleRaw);
                }
            }
        }
        float vol = (float) sec.getDouble("sound_volume", fallback != null ? fallback.soundVolume() : 0.0);
        float pitch = (float) sec.getDouble("sound_pitch", fallback != null ? fallback.soundPitch() : 0.0);
        double radius = sec.getDouble("radius", fallback != null ? fallback.radius() : 0.0);
        double ray = sec.getDouble("ray_length", fallback != null ? fallback.rayLength() : 0.0);
        int density = sec.getInt("density", fallback != null ? fallback.density() : 0);
        int ripple = sec.getInt("ripple_rings", fallback != null ? fallback.rippleRings() : 0);
        int expand = sec.getInt("expand_steps", fallback != null ? fallback.expandSteps() : 0);
        int spiral = sec.getInt("spiral_ticks", fallback != null ? fallback.spiralTicks() : 0);
        int duration = sec.getInt("duration_ticks", fallback != null ? fallback.durationTicks() : 0);
        return new MagicUseFx(sound, vol, pitch, preset, particle, radius, ray, density, ripple, expand, spiral, duration);
    }
}
