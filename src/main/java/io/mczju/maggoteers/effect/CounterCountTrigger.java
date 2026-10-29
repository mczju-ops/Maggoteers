package io.mczju.maggoteers.effect;

/**
 * 计数器的计数输入（修正：计数器=中继器；计时器=count:tick 计数器）。
 * <p>TICK/ATTACK/DAMAGE_TAKEN/KILL/WAVE_CLEAR 为既有 trigger 驱动的输入；
 * ON_COUNTER 为<b>串联输入</b>——上游计数器计满后，其输出信号（ON_COUNTER）
 * 会驱动 countTrigger 为 ON_COUNTER 的其它计数器（跳过自身防自环）。
 */
public enum CounterCountTrigger {
    TICK, ATTACK, DAMAGE_TAKEN, KILL, WAVE_CLEAR, ON_COUNTER
}
