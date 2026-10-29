package io.mczju.maggoteers.game;

import com.github.mczjuops.mczjugamecore.MCZJUGameCore;
import io.mczju.maggoteers.state.PlayerStateManager;
import io.mczju.maggoteers.wave.WaveScheduler;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 收集当前进行中的 {@link MaggoteersGame}（MGC {@code getAllGames()} 在 RUNNING 时可能为空）。 */
public final class ActiveMaggoteersGames {

    private ActiveMaggoteersGames() {}

    public static Set<MaggoteersGame> snapshot() {
        Set<MaggoteersGame> out = new HashSet<>();
        for (var g : MCZJUGameCore.getGameManager().getAllGames()) {
            if (g instanceof MaggoteersGame mg) out.add(mg);
        }
        for (var g : WaveScheduler.activeGames()) {
            if (g instanceof MaggoteersGame mg) out.add(mg);
        }
        for (var g : PlayerStateManager.gamesWithState()) {
            if (g instanceof MaggoteersGame mg) out.add(mg);
        }
        return out;
    }

    public static void forEach(Consumer<MaggoteersGame> action) {
        for (MaggoteersGame mg : snapshot()) action.accept(mg);
    }
}
