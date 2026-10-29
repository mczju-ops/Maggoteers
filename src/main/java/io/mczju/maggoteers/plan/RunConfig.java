package io.mczju.maggoteers.plan;

import io.mczju.maggoteers.wave.Vec3;
import java.util.List;
import java.util.Map;

/**
 * RunPlanner 的纯配置快照：层列表（{@code acts}）+ 层原点（{@code act_origins}）
 * + 每层 weak/strong 波数（{@code waves_per_act}）。
 *
 * <p>⚠️ 取值一律 fail-fast：缺键抛 {@link IllegalStateException}。
 * 历史实现缺键会静默回落 {@code (0,64,0)}（= Act1 原点，第四层结构会粘在第一层上）、波数回落
 * {@code 3/2} —— 这类"不报错但错"的默认值是加层时最危险的地雷。
 */
public record RunConfig(List<String> acts, Map<String, Vec3> origins, Map<String, int[]> wavesPerAct) {

    public RunConfig {
        acts = acts == null ? List.of() : List.copyOf(acts);
    }

    public int actCount() {
        return acts.size();
    }

    public Vec3 origin(String act) {
        Vec3 v = origins.get(act);
        if (v == null) {
            throw new IllegalStateException("config.yml 缺少 act_origins." + act
                    + "（已启用的层必须配置原点；否则会与其它层原点重叠）");
        }
        return v;
    }

    public int weak(String act) {
        return waves(act)[0];
    }

    public int strong(String act) {
        return waves(act)[1];
    }

    private int[] waves(String act) {
        int[] v = wavesPerAct.get(act);
        if (v == null || v.length < 2) {
            throw new IllegalStateException("config.yml 缺少 waves_per_act." + act + "（需 weak/strong）");
        }
        return v;
    }
}
