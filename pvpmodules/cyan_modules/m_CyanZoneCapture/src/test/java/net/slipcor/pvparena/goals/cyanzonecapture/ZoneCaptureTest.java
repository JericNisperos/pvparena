package net.slipcor.pvparena.goals.cyanzonecapture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may advance a capture — the one rule the whole mode hangs on. */
class ZoneCaptureTest {

    @Test
    void loneAttackerInAnUndefendedZoneCaptures() {
        assertTrue(GoalZoneCapture.advancesCapture(false, 1));
    }

    @Test
    void aSingleDefenderStallsTheCapture() {
        assertFalse(GoalZoneCapture.advancesCapture(true, 1));
        assertFalse(GoalZoneCapture.advancesCapture(true, 3), "defender holds even when outnumbered");
    }

    @Test
    void emptyZoneDoesNotProgress() {
        assertFalse(GoalZoneCapture.advancesCapture(false, 0));
    }

    @Test
    void rivalAttackersStallEachOther() {
        assertFalse(GoalZoneCapture.advancesCapture(false, 2));
    }
}
