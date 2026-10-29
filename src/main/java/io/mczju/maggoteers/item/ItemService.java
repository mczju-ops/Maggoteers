package io.mczju.maggoteers.item;

import io.mczju.maggoteers.MaggoteersPlugin;
import io.mczju.maggoteers.util.ItemYaml;
import io.mczju.mczjuitemcreator.api.ItemCreatorApi;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** ItemCreator 接入 + config/items 可配置券种 + items/*.yml 缓存（G2：发放走 giveItem(ItemStack)）。 */
public final class ItemService {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Logger LOG = Logger.getLogger("Maggoteers");
    private static final Map<String, ItemStack> CACHE = new HashMap<>();
    private static ItemCreatorApi api;

    /** config items.*.item_id */
    private static String idCurrencyNormal = "maggoteers:currency_normal";
    private static String idCurrencyBoss = "maggoteers:currency_boss";
    private static String idClassTicket = "maggoteers:class_ticket";

    private ItemService() {}

    static NamespacedKey kindKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "kind");
    }

    private static NamespacedKey idKey() {
        return new NamespacedKey(MaggoteersPlugin.getInstance(), "id");
    }

    public static void init(MaggoteersPlugin plugin) {
        CACHE.clear();
        api = Bukkit.getServicesManager().load(ItemCreatorApi.class);
        if (api == null) {
            LOG.warning("MCZJUItemCreator 未安装，物品功能不可用。");
        }
        loadConfigurableItems(plugin);
        File dir = new File(plugin.getDataFolder(), "items");
        if (!dir.exists()) dir.mkdirs();
        copyDefaultItems(plugin, dir);
        int[] total = {0};
        ItemYaml.forEachYaml(dir, yaml -> {
            if (api == null) {
                return;
            }
            Map<String, ItemStack> parsed = api.parseYamlToItems(yaml);
            for (var e : parsed.entrySet()) {
                if (!CACHE.containsKey(e.getKey())) CACHE.put(e.getKey(), e.getValue());
            }
            total[0] += parsed.size();
        });
        LOG.info("ItemService 已缓存 " + CACHE.size() + " 个物品（含 config 可配置券种）。");
    }

    /** 从 config.yml {@code items} 段构建货币/职业券（管理员可自定义 name/lore/material/glint）。 */
    private static void loadConfigurableItems(MaggoteersPlugin plugin) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("items");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            String itemId = sec.getString("item_id", "maggoteers:" + key);
            ItemKind kind = ItemKind.fromPdcLoose(sec.getString("kind", key));
            if (kind == null) {
                LOG.warning("items." + key + " 缺少有效 kind，已跳过。");
                continue;
            }
            CACHE.put(itemId, buildConfigured(sec, kind, itemId));
            switch (kind) {
                case CURRENCY_NORMAL -> idCurrencyNormal = itemId;
                case CURRENCY_BOSS -> idCurrencyBoss = itemId;
                case CLASS_TICKET -> idClassTicket = itemId;
                default -> { }
            }
        }
    }

    private static ItemStack buildConfigured(ConfigurationSection sec, ItemKind kind, String itemId) {
        Material mat = Material.matchMaterial(sec.getString("material", "PAPER"));
        if (mat == null || !mat.isItem()) mat = Material.PAPER;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        String name = sec.getString("name", "");
        if (!name.isBlank()) {
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        }
        List<String> loreLines = sec.getStringList("lore");
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(MM.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        if (sec.getBoolean("glint", false)) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.getPersistentDataContainer().set(kindKey(), PersistentDataType.STRING, kind.pdcValue());
        meta.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, itemId);
        stack.setItemMeta(meta);
        if (sec.getBoolean("glint", false)) {
            stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return stack;
    }

    private static void copyDefaultItems(MaggoteersPlugin plugin, File dir) {
        for (String name : new String[]{"maggoteers.yml", "collectibles.yml"}) {
            File out = new File(dir, name);
            if (out.exists()) continue;
            var stream = plugin.getResource("items/" + name);
            if (stream == null) continue;
            try (stream) {
                java.nio.file.Files.copy(stream, out.toPath());
            } catch (Exception e) {
                LOG.warning("释放默认物品 " + name + " 失败：" + e.getMessage());
            }
        }
    }

    private static String itemId(ItemKind kind) {
        return switch (kind) {
            case CURRENCY_NORMAL -> idCurrencyNormal;
            case CURRENCY_BOSS -> idCurrencyBoss;
            case CLASS_TICKET -> idClassTicket;
            case REVIVE_COIN -> "maggoteers:revive_coin";
            case SUPPLY_HEALING -> "maggoteers:supply_healing";
            case SHOP_EMERALD -> "maggoteers:shop_emerald";
            case HIDDEN_KEY -> "maggoteers:hidden_key";
            case COLLECTIBLE, RUN_GEAR, BOUND_EQUIP -> null;
        };
    }

    public static ItemKind readKind(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer().get(kindKey(), PersistentDataType.STRING);
        return ItemKind.fromPdcLoose(raw);
    }

    /** 读取 PDC {@code maggoteers:id}，返回物品 id 字符串（如 {@code "maggoteers:fire_sword"}），无则 null。 */
    public static String itemIdOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer().get(idKey(), PersistentDataType.STRING);
    }

    public static boolean hasItem(String id) {
        return CACHE.containsKey(id) || (api != null && api.hasItem(id));
    }

    public static Optional<ItemStack> createItem(String id, int amount) {
        if (id == null || id.isBlank()) return Optional.empty();
        ItemStack base = CACHE.get(id);
        if (base == null && api != null) base = api.createItem(id).orElse(null);
        if (base == null && !id.contains(":")) {
            String prefixed = "maggoteers:" + id;
            base = CACHE.get(prefixed);
            if (base == null && api != null) base = api.createItem(prefixed).orElse(null);
            if (base != null) id = prefixed;
        }
        if (base == null) return Optional.empty();
        ItemStack stack = base.clone();
        stack.setAmount(Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, id);
            stack.setItemMeta(meta);
        }
        normalizeUseCooldownGroup(stack, id);   // 发放即对：组件 group = PDC id（Spec2 §7）
        return Optional.of(stack);
    }

    /**
     * 把带 {@code use_cooldown} 组件的物品 {@code cooldownGroup} 规范化为 {@code Key.key(pdcId)}
     * （Spec2 §7 视觉 CD 对齐）。玩家拿到手即与 {@code setCooldown(Key)} 一致，避免"同 tick 改组件
     * 客户端未同步第一下遮罩"；无组件或 PDC 缺失则不动。运行期 {@code CooldownService} 仍有兜底。
     */
    private static void normalizeUseCooldownGroup(ItemStack stack, String pdcId) {
        if (stack == null || stack.getType().isAir() || pdcId == null) return;
        UseCooldown uc = stack.getData(DataComponentTypes.USE_COOLDOWN);
        if (uc == null) return;
        Key key;
        try {
            key = Key.key(pdcId);   // "maggoteers:fire_sword" → ns=maggoteers, value=fire_sword
        } catch (Exception ignored) {
            return;   // 非法 key 不动，运行期兜底回落
        }
        if (key.equals(uc.cooldownGroup())) return;   // 幂等：已一致不重写
        UseCooldown rebuilt = UseCooldown.useCooldown(uc.seconds()).cooldownGroup(key).build();
        stack.setData(DataComponentTypes.USE_COOLDOWN, rebuilt);
    }

    /**
     * 发放物品到背包；满则掉脚下。未知 id 返回 false（不抛异常）。
     * 无冒号前缀时会尝试 {@code maggoteers:<id>}。
     * <p>2026-10-27：「喀戎之引」的 {@code CURRENCY_LOCK} 生效时，普通货币一律不再发放
     * （clearReward / GRANT_ITEM 等所有入口都经过这里）。
     */
    public static boolean give(Player player, String id, int amount) {
        if (player != null && isNormalCurrency(id)
                && io.mczju.maggoteers.effect.EffectService.isCurrencyLocked(player)) {
            return false;
        }
        Optional<ItemStack> created = createItem(id, amount);
        if (created.isEmpty()) {
            LOG.warning("物品不存在，无法发放：" + id);
            return false;
        }
        deliver(player, created.get());
        return true;
    }

    private static boolean isNormalCurrency(String id) {
        if (id == null) return false;
        return idCurrencyNormal.equalsIgnoreCase(id)
                || id.equalsIgnoreCase("currency_normal")
                || id.equalsIgnoreCase("maggoteers:currency_normal");
    }

    /** 背包满则掉落脚下（与 CLAUDE G2 / Collectible 一致）。 */
    public static void deliver(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.getType().isAir()) return;
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack left : leftover.values()) {
            if (left == null || left.getType().isAir()) continue;
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        syncWeaponHeldSoon(player);
    }

    /** 发放后（延迟 1 tick）同步主手武器 held_effects。
     *  代码直接 addItem 塞进背包不产生 PlayerItemHeldEvent，WeaponHeldListener 收不到；
     *  否则开局/3选1/职业武器「持有时不生效、首次切武器才生效」（bug #52）。延迟与事件路径一致，防效果链内重入。 */
    private static void syncWeaponHeldSoon(Player player) {
        if (player == null || !player.isOnline()) return;
        Bukkit.getScheduler().runTask(MaggoteersPlugin.getInstance(), () -> {
            var game = io.mczju.maggoteers.state.PlayerStateManager.gameOf(player);
            if (game == null) return;
            io.mczju.maggoteers.effect.WeaponHeldService.sync(player, game);
        });
    }

    /**
     * 2026-10-28（安全）：只清掉带 {@code maggoteers:id} PDC 的本插件物品（武器/货币/护符/况等），
     * 不动玩家在大厅里的其他物品。用于"入局前清掉上一局残留"。
     */
    public static int clearRunItems(Player player) {
        if (player == null) return 0;
        int removed = 0;
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (itemIdOf(stack) == null) continue;
            inv.setItem(slot, null);
            removed++;
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir() && itemIdOf(off) != null) {
            inv.setItemInOffHand(null);
            removed++;
        }
        if (removed > 0) player.updateInventory();
        return removed;
    }

    public static boolean giveKind(Player player, ItemKind kind, int amount) {
        return give(player, itemId(kind), amount);
    }

    public static void giveInitialEquipment(Player player) {
        for (String id : MaggoteersPlugin.getInstance().getConfig().getStringList("initial_equipment")) {
            createItem(id, 1).ifPresent(stack -> {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(kindKey(), PersistentDataType.STRING,
                            ItemKind.RUN_GEAR.pdcValue());
                    stack.setItemMeta(meta);
                }
                deliver(player, stack);
            });
        }
    }

    public static int countKind(Player player, ItemKind kind) {
        return countCurrency(player, kind.pdcValue());
    }

    /** 统计背包中某类 PDC kind 数量（按件数，含副手，与 {@link #spendOne} 槽位范围一致，R13）。 */
    private static int countCurrency(Player player, String kindPdc) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            ItemKind k = readKind(stack);
            if (k != null && k.pdcValue().equals(kindPdc)) n += stack.getAmount();
        }
        return n;
    }

    /** 扣除 1 枚指定 kind；成功返回 true。花掉**普通货币**时额外发 {@code ON_SPEND}（猪灵存钱罐）。 */
    public static boolean spendOneKind(Player player, ItemKind kind) {
        boolean spent = spendOne(player, kind.pdcValue());
        if (spent && kind == ItemKind.CURRENCY_NORMAL) {
            io.mczju.maggoteers.effect.EffectService.fireTriggerPlayer(
                    io.mczju.maggoteers.state.PlayerStateManager.gameOf(player),
                    player,
                    io.mczju.maggoteers.effect.Trigger.ON_SPEND);
            // 成就「挥金如土」：只认 3 选 1 商店真的花掉的金币（被诅咒夺走的不走这里）
            io.mczju.maggoteers.achievement.AchievementService.onCoinsSpent(player);
        }
        return spent;
    }

    /** 消耗主手 1 个（amount<=1 清槽，否则减 1）；魔法武器消耗/补给共用。 */
    public static void spendOneFromMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
        syncWeaponHeldSoon(player);   // 主手清空/变更，卸载 held_effects（不产生事件）
    }

    private static boolean spendOne(Player player, String kindPdc) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            ItemKind k = readKind(stack);
            if (k == null || !k.pdcValue().equals(kindPdc)) continue;
            if (stack.getAmount() <= 1) player.getInventory().setItem(i, null);
            else stack.setAmount(stack.getAmount() - 1);
            return true;
        }
        return false;
    }

    /**
     * 2026-10-28（诅咒·贫穷）：从背包里夺走普通货币。{@code amount <= 0} = 全部。
     * <p>与 {@link #spendOneKind} 的区别：这是**被夺走**而非"花掉"，因此<b>不</b>发 {@code ON_SPEND}
     * （猪灵存钱罐不该被诅咒消耗掉）。
     *
     * @return 实际扣掉的数量
     */
    public static int loseNormalCurrency(Player player, int amount) {
        if (player == null) return 0;
        boolean all = amount <= 0;
        int left = amount;
        int removed = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize() && (all || left > 0); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType().isAir()) continue;
            if (readKind(stack) != ItemKind.CURRENCY_NORMAL) continue;
            int n = stack.getAmount();
            int take = all ? n : Math.min(n, left);
            if (take >= n) inv.setItem(i, null);
            else stack.setAmount(n - take);
            removed += take;
            left -= take;
        }
        if (removed > 0) player.updateInventory();
        return removed;
    }

    public static Map<String, ItemStack> cacheView() { return Collections.unmodifiableMap(CACHE); }
}
