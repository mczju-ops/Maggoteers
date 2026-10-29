package io.mczju.maggoteers.effect;

/** 类型化参数键（仿 BuffKey），保证 {@link EffectContext} 取值类型安全。 */
public final class EffectKey<T> {
    private final String name;
    public EffectKey(String name) { this.name = name; }
    public String name() { return name; }
    @Override public String toString() { return name; }
}
