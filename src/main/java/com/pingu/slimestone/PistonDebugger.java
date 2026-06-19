package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class PistonDebugger {

    public static final int RECORD_GT = 100;
    private static final int TRIGGER_OFFSET = 0; // Piston events schedule instantly (GT 0)
    private static final int EVENT_DELAY = 0;

    public record PistonEvent(BlockPos pos, boolean extending, long gt) {
        public PistonEvent(BlockPos pos, boolean extending, long gt) {
            this.pos       = pos.immutable();
            this.extending = extending;
            this.gt        = gt;
        }
    }

    public enum RecordState { IDLE, AWAITING, RECORDING, DONE }

    public static final List<PistonEvent> expected = new ArrayList<>();
    public static final List<PistonEvent> actual   = new ArrayList<>();

    public static RecordState recordState   = RecordState.IDLE;
    public static long        realStartTick = -1;
    public static ServerPlayer trackedPlayer = null;

    public static void resetExpected() {
        expected.clear();
    }

    public static void logExpected(BlockPos pos, boolean extending, long gt) {
        if (gt <= RECORD_GT) {
            expected.add(new PistonEvent(pos, extending, gt));
        }
    }

    public static void prepareForRealRecording(ServerPlayer player) {
        actual.clear();
        recordState   = RecordState.AWAITING;
        realStartTick = -1;
        trackedPlayer = player;
    }

    public static void fullReset(ServerPlayer player) {
        expected.clear();
        actual.clear();
        recordState   = RecordState.IDLE;
        realStartTick = -1;
        if (player != null) {
            // Optional: comment out if you don't want double-messages with ObserverDebugger
            // player.sendSystemMessage(Component.literal("§7[Slimestone] Piston Debugger reset."));
        }
    }

    public static void logReal(BlockPos pos, boolean extending, long currentServerTick) {
        if (recordState == RecordState.AWAITING) {
            realStartTick = currentServerTick - TRIGGER_OFFSET;
            recordState   = RecordState.RECORDING;

            // SYNC OBSERVER DEBUGGER TO THE SAME BASELINE
            if (ObserverDebugger.recordState == ObserverDebugger.RecordState.AWAITING) {
                ObserverDebugger.realStartTick = realStartTick;
                ObserverDebugger.recordState = ObserverDebugger.RecordState.RECORDING;
            }

            if (trackedPlayer != null) {
                trackedPlayer.sendSystemMessage(Component.literal(
                        "§b[Slimestone] Recording started — first piston event detected. Auto-compare in §f"
                                + RECORD_GT + "§b GT."));
            }
        }

        if (recordState != RecordState.RECORDING) return;

        // APPLY THE EVENT DELAY HERE
        long gt = (currentServerTick - realStartTick) - EVENT_DELAY;

        // Prevent negative GTs if an event somehow fires earlier than the delay expects
        if (gt < 0) gt = 0;

        if (gt > RECORD_GT) {
            finishRecording();
            return;
        }

        PistonEvent newEvent = new PistonEvent(pos, extending, gt);

        // Only add it if an identical event hasn't already been logged this tick
        if (!actual.contains(newEvent)) {
            actual.add(newEvent);
        }
    }

    public static void onServerTick(long currentServerTick) {
        if (recordState == RecordState.RECORDING
                && realStartTick >= 0
                && (currentServerTick - realStartTick) > RECORD_GT) {
            finishRecording();
        }
    }

    public static void finishRecording() {
        if (recordState != RecordState.RECORDING && recordState != RecordState.AWAITING) return;
        recordState = RecordState.DONE;
        printComparison();
    }

    private static void printComparison() {
        if (trackedPlayer == null) return;

        int expSize = expected.size();
        int actSize = actual.size();
        int total   = Math.max(expSize, actSize);

        trackedPlayer.sendSystemMessage(Component.literal(
                "§b§l══════════ Piston Debug Results ════════════"));
        trackedPlayer.sendSystemMessage(Component.literal(
                "§7Expected: §f" + expSize + " §7events  |  Actual: §f" + actSize
                        + " §7events  (first " + RECORD_GT + " GT)"));

        if (total == 0) {
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§c[Slimestone] No piston events recorded on either side."));
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§b§l════════════════════════════════════════════"));
            return;
        }

        int firstErrorIdx = -1;

        for (int i = 0; i < total; i++) {
            PistonEvent exp = i < expSize ? expected.get(i) : null;
            PistonEvent act = i < actSize ? actual.get(i) : null;

            boolean posMatch   = exp != null && act != null && exp.pos().equals(act.pos());
            boolean stateMatch = exp != null && act != null && exp.extending() == act.extending();
            boolean gtMatch    = exp != null && act != null && exp.gt() == act.gt();
            boolean fullMatch  = posMatch && stateMatch && gtMatch;

            if (!fullMatch && firstErrorIdx == -1) {
                firstErrorIdx = i;
            }

            String icon     = fullMatch ? "§a✓" : "§c✗";
            String errorTag = (i == firstErrorIdx) ? " §c§l◄ FIRST ERROR" : "";

            if (fullMatch) {
                trackedPlayer.sendSystemMessage(Component.literal(
                        icon + " §7#" + (i + 1) + "  " + fmt(exp)));
            } else {
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
                "§b§l════════════════════════════════════════════"));

        if (firstErrorIdx == -1) {
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§a§l✓ PERFECT MATCH — all " + total + " piston events correct."));
        } else {
            PistonEvent exp = firstErrorIdx < expSize ? expected.get(firstErrorIdx) : null;
            PistonEvent act = firstErrorIdx < actSize ? actual.get(firstErrorIdx) : null;
            String expSuffix  = (exp == null) ? "nothing" : fmt(exp);
            String actSuffix  = (act == null) ? "nothing" : fmt(act);
            trackedPlayer.sendSystemMessage(Component.literal(
                    "§c§l✗ First error at piston event #" + (firstErrorIdx + 1)
                            + " — expected " + expSuffix + ", got " + actSuffix));
        }
    }

    private static String fmt(PistonEvent e) {
        String stateStr = e.extending() ? "§bEXTEND " : "§6RETRACT";
        return "§f" + e.pos().toShortString() + " " + stateStr + " §7@GT" + e.gt();
    }
}