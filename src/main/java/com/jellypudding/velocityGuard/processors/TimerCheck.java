package com.jellypudding.velocityGuard.processors;

public final class TimerCheck {

    private TimerCheck() {}

    private static final long TICK_NANOS = 50_000_000L; // 50ms

    public static void onTransaction(PlayerMovementState state) {
        if (state.hasGottenMovementAfterTransaction) {
            state.knownPlayerClockTime = state.lastMovementPlayerClock;
            state.lastMovementPlayerClock = state.playerClockAtLeast;
            state.hasGottenMovementAfterTransaction = false;
        }
    }

    public static boolean onMovement(PlayerMovementState state, long driftNanos) {
        state.hasGottenMovementAfterTransaction = true;
        state.timerBalanceRealTime += TICK_NANOS;

        boolean tooFast = false;
        if (state.timerBalanceRealTime > System.nanoTime()) {
            tooFast = true;
            state.timerBalanceRealTime -= TICK_NANOS;
        }

        state.timerBalanceRealTime = Math.max(
                state.timerBalanceRealTime,
                state.lastMovementPlayerClock - driftNanos);

        return tooFast;
    }
}
