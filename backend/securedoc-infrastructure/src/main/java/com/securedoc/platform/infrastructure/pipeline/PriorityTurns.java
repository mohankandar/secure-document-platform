package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.pipeline.PipelinePriority;

/**
 * Task 0073: no starvation. After four Urgent jobs in a row the next one is a Normal job if one waits; after four Normal
 * jobs without a Low one, the next Normal turn is a Low job's if one waits. So of every five jobs a stage takes at least one
 * is Normal while Urgent and Normal wait, and of every five below Urgent at least one is Low while Normal and Low wait. Per
 * server and per stage; a turn is given out at once, so workers asking at the same moment do not all get it.
 */
final class PriorityTurns {
    static final int IN_A_ROW = 4;
    private int urgentInARow;
    private int normalInARow;

    /** @param urgent the rank of each priority for this claim; the lowest is taken first */
    record Turn(int urgent, int normal, int low, boolean normalsTurn, boolean lowsTurn) { }

    synchronized Turn next() {
        boolean normals = urgentInARow >= IN_A_ROW;
        boolean lows = normalInARow >= IN_A_ROW;
        if (normals) urgentInARow = 0;
        if (lows) normalInARow = 0;
        if (normals) return lows ? new Turn(3, 2, 1, true, true) : new Turn(2, 1, 3, true, false);
        // Low's turn is one of Normal's: an Urgent job still goes first.
        return lows ? new Turn(1, 3, 2, false, true) : new Turn(1, 2, 3, false, false);
    }

    /** @param taken null when nothing was */
    synchronized void done(Turn turn, PipelinePriority taken) {
        if (taken == PipelinePriority.URGENT) {
            // On Normal's turn no Normal job waited: the turn stays open.
            urgentInARow = turn.normalsTurn() ? IN_A_ROW : urgentInARow + 1;
        } else if (taken != null) {
            urgentInARow = 0;
        } else if (turn.normalsTurn()) {
            urgentInARow = IN_A_ROW;
        }
        if (taken == PipelinePriority.NORMAL) normalInARow = turn.lowsTurn() ? IN_A_ROW : normalInARow + 1;
        else if (taken == PipelinePriority.LOW) normalInARow = 0;
        else if (turn.lowsTurn()) normalInARow = IN_A_ROW;
    }
}
