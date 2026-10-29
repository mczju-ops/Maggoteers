package io.mczju.maggoteers.codex;

/**
 * 图鉴四个菜单的槽位布局（唯一真相源）。
 *
 * <p>写成常量而不是散在菜单里，是为了让 {@code CodexLayoutTest} 能钉住版式：
 * <ul>
 *   <li>一级（3×9）：第 2 行第 2/4/6/8 格 = 四本图鉴，第 3 行第 9 格 = 退出。</li>
 *   <li>武器/藏品（6×9 = 原版上限 54 格）：第 1–5 行 45 格放条目，第 6 行 = 上一页 / 本页池子 / 下一页 / 返回。</li>
 *   <li>首领（5×9）：第 1–4 行第 1 格标层数、第 2–9 格放刷怪蛋；第 5 行第 9 格 = 返回。</li>
 *   <li>词条（5×9）：第 2–4 行第 2–8 格放词条；第 5 行第 9 格 = 返回。</li>
 * </ul>
 * 改版式必须先改测试。
 */
public final class CodexLayout {

    public static final int COLUMNS = 9;

    // ================================================================ 一级菜单（3×9）
    public static final int MAIN_ROWS = 3;
    public static final int MAIN_WEAPON = 10;        // 第 2 行第 2 格
    public static final int MAIN_COLLECTIBLE = 12;   // 第 2 行第 4 格
    public static final int MAIN_BOSS = 14;          // 第 2 行第 6 格
    public static final int MAIN_AFFIX = 16;         // 第 2 行第 8 格
    public static final int MAIN_EXIT = 26;          // 第 3 行第 9 格

    // ================================================================ 武器 / 藏品（5×9，页内续屏）
    /**
     * 二级菜单 5 行：第 1 行第 5 格 = 本页池子，第 2–4 行第 2–8 格 = 21 件条目，
     * 第 5 行第 2/8 格 = 上一页/下一页、第 9 格 = 返回。
     *
     * <p>2026-10-30 第二版曾放大到 6×9（原版上限 54 格）想做到"一页一池不翻页"，
     * 但藏品一个池子最多 37 件，放大后仍要按池续屏；用户裁定**回到 5×9 + 页内续屏**，
     * 视觉上与首领/词条两本保持一致。
     */
    public static final int SUB_ROWS = 5;
    /** 第 1 行第 5 格：本页对应的池子。 */
    public static final int PAGE_HEADER = 4;
    /** 第 5 行第 2 / 第 8 格：上一页 / 下一页。 */
    public static final int PAGE_PREV = 37;
    public static final int PAGE_NEXT = 43;
    /** 第 5 行第 9 格：返回。首领/词条菜单共用这个位置。 */
    public static final int SUB_BACK = 44;

    /** 武器/藏品条目格：第 2–4 行 × 第 2–8 格 = 21 格（顺序：先行后列）。 */
    public static final int[] PAGE_ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    // ================================================================ 首领 / 词条（5×9）
    /** 首领：每行第 1 格是层数标签，第 2–9 格是 Boss。 */
    public static final int BOSS_LABEL_OFFSET = 0;
    public static final int BOSS_SLOTS_PER_ROW = 8;
    public static final int BOSS_MAX_ROWS = 4;

    /** 词条：第 2–4 行 × 第 2–8 格 = 21 格。 */
    public static final int[] AFFIX_SLOTS = PAGE_ENTRY_SLOTS;

    private CodexLayout() {}

    /** 第 {@code row}（0 基）行第 {@code col}（0 基）列的槽位。 */
    public static int slot(int row, int col) {
        return row * COLUMNS + col;
    }
}
