package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class MovingBlockDebugger {

    public static final int RECORD_GT = 100;

    // Shifts the timestamp of recorded moving block events by +2 GT
    private static final int EVENT_DELAY = 2; //first instant of a moivng block if start command was to update an observer

    public record MovingBlockEvent(BlockPos pos, String blockName, boolean extending, long gt) {
        public MovingBlockEvent(BlockPos pos, String blockName, boolean extending, long gt) {
            this.pos       = pos.immutable();
            this.blockName = blockName;
            this.extending = extending;
            this.gt        = gt;
        }
    }

    public enum RecordState { IDLE, AWAITING, RECORDING, DONE }

    public static final List<MovingBlockEvent> expected = new ArrayList<>();
    public static final List<MovingBlockEvent> actual   = new ArrayList<>();

    public static RecordState recordState   = RecordState.IDLE;
    public static long        realStartTick = -1;
    public static ServerPlayer trackedPlayer = null;

    public static void resetExpected() {
        expected.clear();
    }

    public static void logExpected(BlockPos pos, BlockState state, boolean extending, long gt) {
        if (gt <= RECORD_GT) {
            expected.add(new MovingBlockEvent(pos, state.getBlock().getName().getString(), extending, gt));
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
    }

    public static void logReal(BlockPos pos, BlockState state, boolean extending, long currentServerTick) {
        if (recordState == RecordState.AWAITING) {
            // We do NOT apply the delay to realStartTick here.
            // We want the shared baseline to remain accurate for the Observer and Piston debuggers.
            realStartTick = currentServerTick;
            recordState   = RecordState.RECORDING;

            // Sync other systems if they are awaiting
            if (PistonDebugger.recordState == PistonDebugger.RecordState.AWAITING) {
                // If PistonDebugger expects a 0-offset baseline, it gets it here
                PistonDebugger.realStartTick = realStartTick;
                PistonDebugger.recordState = PistonDebugger.RecordState.RECORDING;
            }
            if (ObserverDebugger.recordState == ObserverDebugger.RecordState.AWAITING) {
                // ObserverDebugger normally subtracts 2 from currentServerTick,
                // so we replicate its exact expected baseline offset here:
                ObserverDebugger.realStartTick = realStartTick - 2;
                ObserverDebugger.recordState = ObserverDebugger.RecordState.RECORDING;
            }

            if (trackedPlayer != null) {
                trackedPlayer.sendSystemMessage(Component.literal(
                        "§d[Slimestone] Recording started — first moving block detected. Auto-compare in §f"
                                + RECORD_GT + "§d GT."));
            }
        }

        if (recordState != RecordState.RECORDING) return;

        // Apply the +2 GT delay specifically to the moving block's recorded timestamp
        long gt = (currentServerTick - realStartTick) + EVENT_DELAY;
        if (gt < 0) gt = 0;

        if (gt > RECORD_GT) {
            finishRecording();
            return;
        }

        MovingBlockEvent newEvent = new MovingBlockEvent(pos, state.getBlock().getName().getString(), extending, gt);
        if (!actual.contains(newEvent)) {
            actual.add(newEvent);
        }
    }

    public static void onServerTick(long currentServerTick) {
        if (recordState == RecordState.RECORDING && realStartTick >= 0 && (currentServerTick - realStartTick) > RECORD_GT) {
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

        trackedPlayer.sendSystemMessage(Component.literal("§d§l═════════ Moving Block Results ═════════"));
        trackedPlayer.sendSystemMessage(Component.literal("§7Expected: §f" + expSize + " §7events  |  Actual: §f" + actSize + " §7events"));

        if (total == 0) return;

        int firstErrorIdx = -1;

        for (int i = 0; i < total; i++) {
            MovingBlockEvent exp = i < expSize ? expected.get(i) : null;
            MovingBlockEvent act = i < actSize ? actual.get(i) : null;

            boolean posMatch   = exp != null && act != null && exp.pos().equals(act.pos());
            boolean stateMatch = exp != null && act != null && exp.blockName().equals(act.blockName());
            boolean gtMatch    = exp != null && act != null && exp.gt() == act.gt();
            boolean fullMatch  = posMatch && stateMatch && gtMatch;

            if (!fullMatch && firstErrorIdx == -1) firstErrorIdx = i;

            String icon     = fullMatch ? "§a✓" : "§c✗";
            if (fullMatch) {
                trackedPlayer.sendSystemMessage(Component.literal(icon + " §7#" + (i + 1) + "  " + fmt(exp)));
            } else {
                String expStr = (exp == null) ? "§8(missing)" : fmt(exp);
                String actStr = (act == null) ? "§8(missing)" : fmt(act);
                trackedPlayer.sendSystemMessage(Component.literal(icon + " §7#" + (i + 1) + (i == firstErrorIdx ? " §c§l◄ FIRST ERROR" : "")));
                trackedPlayer.sendSystemMessage(Component.literal("   §7Exp: " + expStr));
                trackedPlayer.sendSystemMessage(Component.literal("   §7Act: " + actStr));
            }
        }
        trackedPlayer.sendSystemMessage(Component.literal("§d§l════════════════════════════════════════"));
    }

    private static String fmt(MovingBlockEvent e) {
        String stateStr = e.extending() ? "§bEXTEND" : "§6RETRACT";
        return "§f" + e.pos().toShortString() + " §7[" + e.blockName() + "] " + stateStr + " §7@GT" + e.gt();
    }
}