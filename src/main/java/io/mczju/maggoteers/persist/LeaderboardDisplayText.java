package io.mczju.maggoteers.persist;

/** Leaderboard text builder (pure MiniMessage; MGC AbstractLeaderboard String contract). */
public final class LeaderboardDisplayText {
    private LeaderboardDisplayText() {}

    public static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }

    public static String line(int index, String plainName, String valueText, boolean op) {
        String nameColor = op ? "dark_red" : "green";
        return "<gray>" + index + ". <" + nameColor + ">" + esc(plainName)
                + "</" + nameColor + "> <yellow>" + esc(valueText) + "</yellow></gray>";
    }

    public static String stripMgcNameColor(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return "";
        }
        int gt = displayName.indexOf((char) 62);
        if (displayName.startsWith("<") && gt > 0) {
            return displayName.substring(gt + 1);
        }
        return displayName;
    }

    public static boolean mgcNameIsOp(String displayName) {
        return displayName != null && displayName.startsWith("<dark_red>");
    }
}
