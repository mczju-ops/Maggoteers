package io.mczju.maggoteers.item.fx;

/** 魔法物品释放时的粒子预设（config / items YAML 引用 id）。 */
public enum MagicFxPreset {
    /** 仅音效，不播放 cast 粒子（范围环由 EffectService 绘制，或光束/自身强化类）。 */
    NONE,
    /** 默认：加粗水平圆环（替代旧 playAreaRing）。 */
    THICK_RING,
    /** 自玩家前方起绕玩家快速旋转的半径粒子，前方缺口、后方递减。 */
    SPIRAL_RADIUS,
    /** 波浪式半径递增的同心环，保留前环残留。 */
    RIPPLE_RINGS,
    /** 沿准星方向的加粗射线。 */
    AIM_RAY,
    /** 平滑单环半径递增（同一时刻仅一环）。 */
    SMOOTH_EXPAND_RING,
    /** 实体正上方一格的圆点。 */
    DOT_ABOVE,
    /** 三轴正交护盾环（xy / xz / yz 平面各一圈，以中心为原点）。 */
    SHIELD_AXES,
    /** 经纬球壳：lats 条水平纬线环 + 两条竖环（法线 x/z），以实体中心为球心，两端复用；
     *  周期模式 3s（Spec3 §4，duration_ticks>0 时经 ParticleLoopService 周期重绘）。 */
    SHIELD_SHELL,
    /**
     * 2026-10-29：**扇形（CONE）由内向外扩散**。真正的绘制在
     * {@link MagicFxPresets#playConeAlong}——只有 effect 参数知道张角与准星方向，
     * 所以通用 {@code play(...)} 分支里它是空操作（只贡献 {@code sound}）。
     */
    CONE_SPREAD
}
