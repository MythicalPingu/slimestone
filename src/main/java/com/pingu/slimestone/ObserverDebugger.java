package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Central store for the observer-order debugger.
 *
 * Flow:
 *  1. /slimestone → VirtualLevel sim runs → logExpected() populates expected list
 *  2. prepareForRealRecording() arms the recorder
 *  3. User triggers machine → ObserverBlockMixin calls logReal() on each observer tick
 *  4. After RECORD_GT real ticks (tracked by ServerTickEvents), finishRecording() compares
 */
public class ObserverDebugger {

    // How many game-ticks of observer events we track on both sides.
    public static final int RECORD_GT = 0;

    // Vanilla observers always fire 2 GT after detecting a block change.
    // When the player places a block to trigger the machine, that placement
    // is GT 0 in reality but GT 2 in the simulation (which schedules the
    // first observer tick with a 2-tick delay). We subtract this offset from
    // realStartTick so that the first real firing is labelled GT 2, matching
    // the simulation instead of GT 0.
    private static final int TRIGGER_OFFSET = 2;

    // ── Events ────────────────────────────────────────────────────────────────

    public record ObserverEvent(BlockPos pos, boolean poweredOn, long gt) {
        public ObserverEvent(BlockPos pos, boolean poweredOn, long gt) {
            this.pos       = pos.immutable();
            this.poweredOn = poweredOn;
            this.gt        = gt;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    public enum RecordState { IDLE, AWAITING, RECORDING, DONE }

    public static final List<ObserverEvent> expected = new ArrayList<>();
    public static final List<ObserverEvent> actual   = new ArrayList<>();

    public static RecordState recordState   = RecordState.IDLE;
    public static long        realStartTick = -1;
    public static ServerPlayer trackedPlayer = null;

    // ── API called by VirtualLevel ────────────────────────────────────────────

    /** Clear the expected list before a new simulation. */
    public static void resetExpected() {
        expected.clear();
    }

    /**
     * Record one expected observer transition.
     * Only events within the first RECORD_GT virtual ticks are kept.
     *
     * @param pos       observer position
     * @param poweredOn true = turning ON, false = turning OFF
     * @param gt        virtual game-tick (currentTick inside VirtualLevel)
     */
    public static void logExpected(BlockPos pos, boolean poweredOn, long gt) {
        if (gt <= RECORD_GT) {
            expected.add(new ObserverEvent(pos, poweredOn, gt));
        }
    }

    // ── API called by Slimestone command ──────────────────────────────────────

    /**
     * Arm the recorder after the simulation has finished.
     * The next real observer event will start the recording window.
     */
    public static void prepareForRealRecording(ServerPlayer player) {
        actual.clear();
        recordState   = RecordState.AWAITING;
        realStartTick = -1;
        trackedPlayer = player;
    }

    /** Hard reset: clears everything and goes back to IDLE. */
    public static void fullReset(ServerPlayer player) {
        expected.clear();
        actual.clear();
        recordState   = RecordState.IDLE;
        realStartTick = -1;
        if (player != null) {
            player.sendSystemMessage(Component.literal("§7[Slimestone] Debugger reset."));
        }
    }

    // ── API called by ObserverBlockMixin ──────────────────────────────────────

    /**
     * Record one real observer transition. Called from the mixin before the
     * observer's tick method executes, so poweredOn = !state.POWERED (the
     * upcoming new value).
     *
     * @param pos             observer world position
     * @param poweredOn       true = observer is turning ON, false = turning OFF
     * @param currentServerTick  level.getGameTime() at the moment of the tick
     */
    public static void logReal(BlockPos pos, boolean poweredOn, long currentServerTick) {
        if (recordState == RecordState.AWAITING) {
            realStartTick = currentServerTick - TRIGGER_OFFSET;
            recordState   = RecordState.RECORDING;

            // SYNC PISTON DEBUGGER TO THE SAME BASELINE
            if (PistonDebugger.recordState == PistonDebugger.RecordState.AWAITING) {
                PistonDebugger.realStartTick = realStartTick;
                PistonDebugger.recordState = PistonDebugger.RecordState.RECORDING;
            }

            if (trackedPlayer != null) {
                trackedPlayer.sendSystemMessage(Component.literal(
                        "§a[Slimestone] Recording started — first observer event detected. Auto-compare in §f"
                                + RECORD_GT + "§a GT."));
            }
        }

        if (recordState != RecordState.RECORDING) return;

        long gt = currentServerTick - realStartTick;

        // If the event itself is past the window, finish now (handles machines
        // that fire events right on or just after the boundary).
        if (gt > RECORD_GT) {
            finishRecording();
            return;
        }

        actual.add(new ObserverEvent(pos, poweredOn, gt));
    }

    // ── API called by ServerTickEvents listener ───────────────────────────────

    /**
     * Called every server tick. Auto-finalizes once RECORD_GT ticks have
     * elapsed since the first real observer event.
     */
    public static void onServerTick(long currentServerTick) {
        if (recordState == RecordState.RECORDING
                && realStartTick >= 0
                && (currentServerTick - realStartTick) > RECORD_GT) {
            finishRecording();
        }
    }

    /**
     * Finalise the recording window and print the comparison.
     * Safe to call multiple times — only acts on the first call per session.
     */
    public static void finishRecording() {
        if (recordState != RecordState.RECORDING && recordState != RecordState.AWAITING) return;
        recordState = RecordState.DONE;
        printComparison();
    }

    // ── Comparison output ─────────────────────────────────────────────────────

    private static void printComparison() {
        if (trackedPlayer == null) return;

        int expSize = expected.size();
        int actSize = actual.size();
        int total   = Math.max(expSize, actSize);

        trackedPlayer.sendSystemMessage(Component.literal(
                "§e§l══════════ Observer Debug Results ══════════"));
        trackedPlayer.sendSystemMessage(Component.literal(
                "§7Expected: §f" + expSize + " §7events  |  Actual: §f" + actSize
                        + " §7events  (first " + RECORD_GT + " GT)"));

        if (total == 0) {
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§c[Slimestone] No observer events recorded on either side."));
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§e§l════════════════════════════════════════════"));
            return;
        }

        int firstErrorIdx = -1;

        for (int i = 0; i < total; i++) {
            ObserverEvent exp = i < expSize ? expected.get(i) : null;
            ObserverEvent act = i < actSize ? actual.get(i) : null;

            boolean posMatch   = exp != null && act != null && exp.pos().equals(act.pos());
            boolean stateMatch = exp != null && act != null && exp.poweredOn() == act.poweredOn();
            boolean gtMatch    = exp != null && act != null && exp.gt() == act.gt();
            boolean fullMatch  = posMatch && stateMatch && gtMatch;

            if (!fullMatch && firstErrorIdx == -1) {
                firstErrorIdx = i;
            }

            String icon     = fullMatch ? "§a✓" : "§c✗";
            String errorTag = (i == firstErrorIdx) ? " §c§l◄ FIRST ERROR" : "";

            if (fullMatch) {
                // Compact one-liner for correct events
                trackedPlayer.sendSystemMessage(Component.literal(
                        icon + " §7#" + (i + 1) + "  " + fmt(exp)));
            } else {
                // Expanded two-liners so the discrepancy is obvious
                String expStr = (exp == null) ? "§8(missing)" : fmt(exp);
                String actStr = (act == null) ? "§8(missing)" : fmt(act);

                trackedPlayer.sendSystemMessage(Component.literal(
                        icon + " §7#" + (i + 1) + errorTag));
                trackedPlayer.sendSystemMessage(Component.literal(
                        "   §7Exp: " + expStr));
                trackedPlayer.sendSystemMessage(Component.literal(
                        "   §7Act: " + actStr));
            }
        }

        trackedPlayer.sendSystemMessage(Component.literal(
                "§e§l════════════════════════════════════════════"));

        if (firstErrorIdx == -1) {
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§a§l✓ PERFECT MATCH — all " + total + " events correct."));
        } else {
            ObserverEvent exp = firstErrorIdx < expSize ? expected.get(firstErrorIdx) : null;
            ObserverEvent act = firstErrorIdx < actSize ? actual.get(firstErrorIdx) : null;
            String expSuffix  = (exp == null) ? "nothing" : fmt(exp);
            String actSuffix  = (act == null) ? "nothing" : fmt(act);
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§c§l✗ First error at event #" + (firstErrorIdx + 1)
                            + " — expected " + expSuffix + ", got " + actSuffix));
        }
    }

    /** Formats an ObserverEvent as a short, coloured string. */
    private static String fmt(ObserverEvent e) {
        String stateStr = e.poweredOn() ? "§aON" : "§cOFF";
        return "§f" + e.pos().toShortString() + " " + stateStr + " §7@GT" + e.gt();
    }
}