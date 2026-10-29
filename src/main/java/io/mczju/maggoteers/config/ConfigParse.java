package io.mczju.maggoteers.config;

/** 配置解析共用小工具（消除各解析器的重复实现）。 */
public final class ConfigParse {
    private ConfigParse() {}

    /** Number 原样返回，非 Number 回落 fallback。 */
    public static Number num(Object v, Number fallback) {
        return v instanceof Number n ? n : fallback;
    }

    /** trim 后为空 → null（name 等可空字段共用）。 */
    public static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }
}
