package net.slipcor.pvparena.goals.cyanroundteamlives;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-series bookkeeping: the branchy part that decides when a match is actually over. */
class RoundTeamLivesTest {

    private static Map<String, Integer> wins(final int red, final int blue) {
        final Map<String, Integer> map = new HashMap<>();
        map.put("red", red);
        map.put("blue", blue);
        return map;
    }

    @Test
    void matchRunsOnUntilATeamHitsTheTarget() {
        assertFalse(GoalRoundTeamLives.matchOver(wins(0, 0), 3));
        assertFalse(GoalRoundTeamLives.matchOver(wins(2, 2), 3), "2-2 in a best-of-3 is not over");
        assertTrue(GoalRoundTeamLives.matchOver(wins(3, 1), 3));
        assertTrue(GoalRoundTeamLives.matchOver(wins(1, 3), 3));
    }

    @Test
    void singleRoundMatchEndsOnFirstWin() {
        assertFalse(GoalRoundTeamLives.matchOver(wins(0, 0), 1));
        assertTrue(GoalRoundTeamLives.matchOver(wins(1, 0), 1));
    }

    @Test
    void leaderPicksTheTeamWithMostRoundWins() {
        assertEquals("blue", GoalRoundTeamLives.leader(wins(1, 3)));
        assertEquals("red", GoalRoundTeamLives.leader(wins(3, 0)));
    }

    @Test
    void leaderHandlesEmptyMap() {
        assertNull(GoalRoundTeamLives.leader(new HashMap<String, Integer>()));
    }
}
