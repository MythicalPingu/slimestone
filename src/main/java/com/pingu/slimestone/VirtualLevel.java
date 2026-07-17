package com.pingu.slimestone;
import net.minecraft.world.level.block.Block;
// ObserverDebugger is in the same package — no extra import needed.
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.*;


public class VirtualLevel {

    public static final int UPDATE_NEIGHBORS = 1;
    public static final int UPDATE_CLIENTS = 2;
    public static final int UPDATE_INVISIBLE = 4;
    public static final int UPDATE_KNOWN_SHAPE = 16;
    public static final int UPDATE_SUPPRESS_DROPS = 32;
    public static final int UPDATE_MOVED_BY_PISTON = 64;
    public static final int UPDATE_ALL = UPDATE_NEIGHBORS | UPDATE_CLIENTS;

    // Package-private so PistonMechanics can reuse the same update ordering
    static final Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private final ServerPlayer player;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    // Maps a block's current (possibly-displaced) position back to the real-world
// position it originally started at, before any simulated pushes/pulls.
    private final Map<BlockPos, BlockPos> originTracker = new HashMap<>();

    /** Returns the real-world starting position for a block now at {@code current}. */
    public BlockPos getOriginPos(BlockPos current) {
        return originTracker.getOrDefault(current.immutable(), current.immutable());
    }

    // Maps a displaced block's real-world origin to wherever it currently
    // sits, for EVERY block PistonMechanics has ever recorded a displacement
    // for. Unlike originTracker above (which forgets a block the instant it
    // lands back home, since that map exists purely to redirect
    // piston/observer display anchors), this one keeps the entry for the
    // rest of the run even after the block returns to its starting spot -
    // that's what lets a block that moved out and came all the way back
    // show up net-0. Piston heads are never in here: moveBlocks only calls
    // recordDisplacement for the toPush list, never for the head placeholder.
    // Blocks PistonMechanics never touched at all (e.g. something the user
    // forgot to actually attach to the moving structure) are also net-0 but
    // don't get an entry here - spawnDisplacementDisplays backfills those
    // separately from a pre-run snapshot before reading this map.
    private final Map<BlockPos, BlockPos> displacedOriginToFinalPos = new LinkedHashMap<>();

    void recordDisplacement(BlockPos oldPos, BlockPos newPos) {
        BlockPos origin = getOriginPos(oldPos);
        originTracker.remove(oldPos.immutable());
        displacedOriginToFinalPos.put(origin, newPos.immutable());
        if (origin.equals(newPos)) {
            originTracker.remove(newPos.immutable()); // moved back home, stop tracking
        } else {
            originTracker.put(newPos.immutable(), origin);
        }
    }
    // All piston extend/retract simulation lives here
    public final PistonMechanics pistonMechanics;

    // Observers only ever log their first TWO actions total (in whatever order
    // they actually occur - normally an ON pulse-start followed by an OFF
    // pulse-end, one per cycle). This cap is only for the *normal* end-of-run
    // display - see pendingObserverPoiDisplays below for the uncapped POI feed.
    private final Map<BlockPos, Integer> observerActionCount = new HashMap<>();
    private final List<ObserverPendingEvent> pendingObserverDisplays = new ArrayList<>();
    // Accumulates each observer's actions across the whole run; only spawned as
    // a real text_display once the simulation finishes (see spawnObserverDisplays).
    private final Map<BlockPos, ObserverDisplayEntry> observerDisplayEntries = new LinkedHashMap<>();

    // Mirrors pendingObserverDisplays/observerDisplayEntries above, but with no
    // two-action cap - every observer action gets recorded here so that
    // /slimestone poi can show as many actions as actually happened in its
    // window, not just an observer's first two. Kept fully separate from the
    // capped pipeline so it can never change the normal display's suffixing.
    private final List<ObserverPendingEvent> pendingObserverPoiDisplays = new ArrayList<>();
    private final Map<BlockPos, ObserverDisplayEntry> observerPoiDisplayEntries = new LinkedHashMap<>();

    // Pistons only ever log their first TWO actions total (in whatever order
    // they actually occur - usually one Extend and one Retract, but a piston
    // that starts already extended could see a Retract before an Extend).
    // This cap is only for the *normal* end-of-run display - see
    // pendingPistonPoiDisplays below for the uncapped POI feed.
    private final Map<BlockPos, Integer> pistonActionCount = new HashMap<>();
    private final List<PistonPendingEvent> pendingPistonDisplays = new ArrayList<>();
    // Accumulates each piston's actions across the whole run; only spawned as a
    // real text_display once the simulation finishes (see spawnPistonDisplays).
    private final Map<BlockPos, PistonDisplayEntry> pistonDisplayEntries = new LinkedHashMap<>();

    // Mirrors pendingPistonDisplays/pistonDisplayEntries above, but with no
    // two-action cap - every piston action gets recorded here so that
    // /slimestone poi can show as many actions as actually happened in its
    // window, not just a piston's first two. Kept fully separate from the
    // capped pipeline so it can never change the normal display's suffixing.
    private final List<PistonPendingEvent> pendingPistonPoiDisplays = new ArrayList<>();
    private final Map<BlockPos, PistonDisplayEntry> pistonPoiDisplayEntries = new LinkedHashMap<>();

    private static class ObserverPendingEvent {
        final BlockPos pos;
        final boolean poweredOn; // true = turned ON (light blue), false = turned OFF (pink)

        ObserverPendingEvent(BlockPos pos, boolean poweredOn) {
            this.pos = pos;
            this.poweredOn = poweredOn;
        }
    }

    // A single logged ON/OFF action, with the raw (uncolored) tick+suffix
    // piece - color is decided at spawn time from poweredOn alone (no
    // "only one action" special case, unlike pistons).
    private static class ObserverActionRecord {
        final int tick;
        final String suffix;
        final boolean poweredOn;

        ObserverActionRecord(int tick, String suffix, boolean poweredOn) {
            this.tick = tick;
            this.suffix = suffix;
            this.poweredOn = poweredOn;
        }
    }

    private static class ObserverDisplayEntry {
        final List<ObserverActionRecord> actions = new ArrayList<>(); // at most 2, chronological order
    }

    private static class PistonPendingEvent {
        final BlockPos pos;
        final boolean extending;
        final int blockCount;

        PistonPendingEvent(BlockPos pos, boolean extending, int blockCount) {
            this.pos = pos;
            this.extending = extending;
            this.blockCount = blockCount;
        }
    }

    // A single logged Extend or Retract, with the raw (uncolored) tick+suffix
    // pieces - colors are only decided once we know the total action count.
    private static class PistonActionRecord {
        final int tick;
        final String suffix;
        final boolean extending;
        final int blockCount;

        PistonActionRecord(int tick, String suffix, boolean extending, int blockCount) {
            this.tick = tick;
            this.suffix = suffix;
            this.extending = extending;
            this.blockCount = blockCount;
        }
    }

    // Passed as blockCount when a logged action has no meaningful push/pull
    // number to show (e.g. a normal piston's retract, which never pulls
    // anything) - the second-line renderer skips these entirely.
    public static final int NO_COUNT = -1;

    private static class PistonDisplayEntry {
        final List<PistonActionRecord> actions = new ArrayList<>(); // at most 2, chronological order
    }

    // Package-private so the Resolver can verify moving pistons
    final Map<BlockPos, SimPistonMovingEntity> blockEntities = new LinkedHashMap<>();

    // Package-private so PistonMechanics can queue/check extend & retract events
    final Queue<BlockEvent> blockEvents = new ArrayDeque<>();
    private final Queue<NeighborUpdate> neighborUpdates = new ArrayDeque<>();

    private int currentTick = 0;
    private String currentPhase = "INIT";

    /**
     * Pending scheduled block ticks ("tile ticks"), e.g. an Observer's
     * falling-edge pulse. Ordered like vanilla's {@code ScheduledTick.DRAIN_ORDER}
     * (earlier {@code triggerTick} first, ties broken by insertion order).
     */
    private final PriorityQueue<SimScheduledTick> scheduledBlockTicks = new PriorityQueue<>();
    private long subTickOrderCounter = 0;

    public VirtualLevel(ServerPlayer player) {
        this.player = player;
        this.pistonMechanics = new PistonMechanics(this);
    }
    // Converts a 0-based action index into an Excel-style column label: a, b,
    // ..., z, aa, ab, ..., az, ba, ... instead of the raw (char) ('a' + i)
    // cast, which would run off the end of the alphabet into '{', '|', etc.
    // once a block racks up more than 26 actions in a single tick.
    private static String excelSuffix(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1; // work in 1-based terms so the math lines up
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('a' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    public void queueObserverDisplay(BlockPos pos, boolean poweredOn) {
        BlockPos displayPos = getOriginPos(pos).immutable();

        // POI: every action is recorded here, uncapped.
        pendingObserverPoiDisplays.add(new ObserverPendingEvent(displayPos, poweredOn));

        // Normal display: only the first two actions for this observer are ever logged.
        int count = observerActionCount.getOrDefault(displayPos, 0);
        if (count >= 2) return;
        observerActionCount.put(displayPos, count + 1);
        pendingObserverDisplays.add(new ObserverPendingEvent(displayPos, poweredOn));
    }

    public void queuePistonDisplay(BlockPos pos, int blockCount, boolean extending) {
        BlockPos displayPos = getOriginPos(pos).immutable();

        // POI: every action is recorded here, uncapped.
        pendingPistonPoiDisplays.add(new PistonPendingEvent(displayPos, extending, blockCount));

        // Normal display: only the first two actions for this piston are ever logged.
        int count = pistonActionCount.getOrDefault(displayPos, 0);
        if (count >= 2) return;
        pistonActionCount.put(displayPos, count + 1);
        pendingPistonDisplays.add(new PistonPendingEvent(displayPos, extending, blockCount));
    }

    // Records this tick's ON/OFF events into each observer's running
    // display entry, in raw (uncolored) form - mirrors flushPistonDisplays.
    private void flushObserverDisplays(int tick) {
        if (pendingObserverDisplays.isEmpty()) return;
        boolean useSuffix = pendingObserverDisplays.size() > 1; // Only use a,b,c if more than 1 this tick

        for (int i = 0; i < pendingObserverDisplays.size(); i++) {
            ObserverPendingEvent e = pendingObserverDisplays.get(i);
            String suffix = useSuffix ? excelSuffix(i) : "";

            ObserverDisplayEntry entry = observerDisplayEntries.computeIfAbsent(e.pos, p -> new ObserverDisplayEntry());
            entry.actions.add(new ObserverActionRecord(tick, suffix, e.poweredOn));
        }
        pendingObserverDisplays.clear();
    }

    // POI counterpart of flushObserverDisplays - same per-tick suffixing
    // logic, but reading/writing the uncapped POI-only lists so it can never
    // affect the normal display.
    private void flushObserverPoiDisplays(int tick) {
        if (pendingObserverPoiDisplays.isEmpty()) return;
        boolean useSuffix = pendingObserverPoiDisplays.size() > 1;

        for (int i = 0; i < pendingObserverPoiDisplays.size(); i++) {
            ObserverPendingEvent e = pendingObserverPoiDisplays.get(i);
            String suffix = useSuffix ? excelSuffix(i) : "";

            ObserverDisplayEntry entry = observerPoiDisplayEntries.computeIfAbsent(e.pos, p -> new ObserverDisplayEntry());
            entry.actions.add(new ObserverActionRecord(tick, suffix, e.poweredOn));
        }
        pendingObserverPoiDisplays.clear();
    }

    // One matchable action, kept around after the simulation finishes so a
    // later /poi <gt> [window] command can re-summon just the actions that
    // happened near a specific tick. Stored as raw data rather than a
    // pre-colored string because the color/suffix pieces are only combined
    // once we know exactly which actions land in the queried window - see
    // spawnPointOfInterest for where that's rendered (using the actual
    // simulation GT, not one relative to centerGt).
    private record PoiEntry(BlockPos pos, int tick, String suffix, boolean isPiston, boolean stateOn, int blockCount) {}

    // Every action from the most recent simulation run, in chronological order.
    private final List<PoiEntry> poiEntries = new ArrayList<>();

    // Snapshot of the text every text_display showed right after the
    // simulation finished - the "full picture" of every piston/observer that
    // had an action, capped at its usual first-two-actions-per-block limit.
    // Never cleared once written, so a bare "/poi" (no gt) can restore this
    // view later after a windowed "/poi <gt> [window]" has replaced it with
    // a filtered one - see spawnFullDisplay.
    private final Map<BlockPos, String> fullDisplayText = new LinkedHashMap<>();

    // Same idea as fullDisplayText, but for the "." displacement markers
    // spawned by spawnDisplacementDisplays - kept in its own map so a marker
    // can never collide with/overwrite a piston or observer row that
    // happens to land on the same block position. Also never cleared, for
    // the same resummon reasons as fullDisplayText.
    private final Map<BlockPos, String> displacementDisplayText = new LinkedHashMap<>();

    // Spawns the actual text_display entities once the whole simulation is
    // done. Up to two actions land on one row, colored by type (§b light
    // blue for turning ON / §d pink for turning OFF). Unlike pistons, there
    // is no special-case recoloring when only one action is found.
    private void spawnObserverDisplays(boolean poiMode) {
        for (Map.Entry<BlockPos, ObserverDisplayEntry> mapEntry : observerDisplayEntries.entrySet()) {
            List<ObserverActionRecord> actions = mapEntry.getValue().actions;
            if (actions.isEmpty()) continue;

            StringBuilder line = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                ObserverActionRecord a = actions.get(i);
                String color = a.poweredOn ? "§b" : "§d";
                String piece = color + a.tick + a.suffix;
                if (i > 0) line.append(" ");
                line.append(piece);
            }
            String text = line.toString();
            fullDisplayText.put(mapEntry.getKey(), text);
            // While in poi mode we still record the full-run text (so a later
            // bare /poi can restore it), but we don't actually summon it here -
            // the poi-filtered view is what should be on screen instead.
            if (!poiMode) {
                spawnGtDisplay(mapEntry.getKey(), text);
            }
        }
        observerDisplayEntries.clear();
    }

    // Spawns a small "." marker over every non-piston, non-observer block
    // that either PistonMechanics recorded a displacement for, or that just
    // sat completely untouched for the whole run, once the whole simulation
    // is done:
    //   - §c red "."    if it ended the run back at the exact position it
    //                    started the simulation from (net displacement 0) -
    //                    this includes blocks that never moved a single
    //                    time, e.g. something the user forgot to attach to
    //                    a moving structure.
    //   - nothing at all if it ended up a net one block away.
    //   - §6 orange "." if it ended up a net two blocks away.
    //   - nothing at all for anything past two blocks - no marker defined.
    // Displacement is measured as the Manhattan distance between a block's
    // original position and where it actually ended up, which collapses to
    // the plain block-count of straight-line offset for the common
    // single-axis push/pull case.
    private void spawnDisplacementDisplays(Map<BlockPos, BlockState> initialBlocks) {
        // Backfill: recordDisplacement only ever fires for blocks a piston
        // actually grabbed, so anything it never touched has no entry in
        // displacedOriginToFinalPos at all. Walk the pre-run snapshot and
        // add a same-position (net-0) entry for every block that's still
        // sitting there untouched, so it gets picked up by the loop below
        // exactly like a block that moved out and came all the way back.
        for (Map.Entry<BlockPos, BlockState> initialEntry : initialBlocks.entrySet()) {
            BlockPos origin = initialEntry.getKey();
            if (displacedOriginToFinalPos.containsKey(origin)) continue; // already handled below

            BlockState initialState = initialEntry.getValue();
            if (initialState.isAir()) continue;
            // Pistons and observers already get their own dedicated Extend/Retract
            // or ON/OFF display at this exact position, and a stationary one would
            // otherwise *always* qualify as "never moved" - skip them so we don't
            // stack a redundant, always-true marker on top of that display.
            if (initialState.getBlock() instanceof PistonBaseBlock) continue;
            if (initialState.getBlock() instanceof PistonHeadBlock) continue;
            if (initialState.is(Blocks.OBSERVER)) continue;

            // Only count it as "never moved" if the same block is still actually
            // sitting there - a block that got destroyed outright (toDestroy)
            // shouldn't get a "stayed home" marker.
            if (!getBlockState(origin).is(initialState.getBlock())) continue;

            displacedOriginToFinalPos.put(origin, origin);
        }

        for (Map.Entry<BlockPos, BlockPos> mapEntry : displacedOriginToFinalPos.entrySet()) {
            BlockPos origin = mapEntry.getKey();
            BlockPos finalPos = mapEntry.getValue();

            int displacement = Math.abs(finalPos.getX() - origin.getX())
                    + Math.abs(finalPos.getY() - origin.getY())
                    + Math.abs(finalPos.getZ() - origin.getZ());

            String text;
            if (displacement == 0) {
                text = "§c█"; // red - ended back at home (or never moved at all)
            } else if (displacement == 2) {
                text = "§6█"; // orange - ended two blocks out
            } else {
                continue; // net displacement of 1 (or 3+) gets no marker
            }

            displacementDisplayText.put(origin, text);
            spawnGtDisplay(origin, text);
        }
    }

    // Records this tick's Extend/Retract events into each piston's running
    // display entry, in raw (uncolored) form. Colors are only decided once
    // we know how many actions that piston ends up with in total.
    private void flushPistonDisplays(int tick) {
        if (pendingPistonDisplays.isEmpty()) return;
        boolean useSuffix = pendingPistonDisplays.size() > 1; // Only use a,b,c if more than 1 this tick

        for (int i = 0; i < pendingPistonDisplays.size(); i++) {
            PistonPendingEvent e = pendingPistonDisplays.get(i);
            String suffix = useSuffix ? excelSuffix(i) : "";

            PistonDisplayEntry entry = pistonDisplayEntries.computeIfAbsent(e.pos, p -> new PistonDisplayEntry());
            entry.actions.add(new PistonActionRecord(tick, suffix, e.extending, e.blockCount));
        }
        pendingPistonDisplays.clear();
    }

    // POI counterpart of flushPistonDisplays - same per-tick suffixing logic,
    // but reading/writing the uncapped POI-only lists so it can never affect
    // the normal display.
    private void flushPistonPoiDisplays(int tick) {
        if (pendingPistonPoiDisplays.isEmpty()) return;
        boolean useSuffix = pendingPistonPoiDisplays.size() > 1;

        for (int i = 0; i < pendingPistonPoiDisplays.size(); i++) {
            PistonPendingEvent e = pendingPistonPoiDisplays.get(i);
            String suffix = useSuffix ? excelSuffix(i) : "";

            PistonDisplayEntry entry = pistonPoiDisplayEntries.computeIfAbsent(e.pos, p -> new PistonDisplayEntry());
            entry.actions.add(new PistonActionRecord(tick, suffix, e.extending, e.blockCount));
        }
        pendingPistonPoiDisplays.clear();
    }

    // Spawns the actual text_display entities once the whole simulation is
    // done. Two actions land on one row colored by type (§e extend / §a
    // retract); a piston with only a single action ever gets that one
    // number recolored §6 (orange) instead.
    private void spawnPistonDisplays(boolean poiMode) {
        for (Map.Entry<BlockPos, PistonDisplayEntry> mapEntry : pistonDisplayEntries.entrySet()) {
            List<PistonActionRecord> actions = mapEntry.getValue().actions;
            if (actions.isEmpty()) continue;

            String[] topPieces = new String[actions.size()];
            String[] bottomPieces = new String[actions.size()];

            StringBuilder firstLine = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                PistonActionRecord a = actions.get(i);
                String color = actions.size() == 1 ? "§6" : (a.extending ? "§e" : "§a");
                topPieces[i] = color + a.tick + a.suffix;
                if (i > 0) firstLine.append(" ");
                firstLine.append(topPieces[i]);
            }

            StringBuilder secondLine = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                PistonActionRecord a = actions.get(i);
                if (a.blockCount == NO_COUNT) continue; // nothing meaningful to show for this action
                String countColor = a.blockCount > 12 ? "§c" : (a.extending ? "§e" : "§a");
                bottomPieces[i] = countColor + a.blockCount;
                if (secondLine.length() > 0) secondLine.append(" ");
                secondLine.append(bottomPieces[i]);
            }

            String text = firstLine + "\n" + secondLine;
            fullDisplayText.put(mapEntry.getKey(), text);
            // While in poi mode we still record the full-run text (so a later
            // bare /poi can restore it), but we don't actually summon it here -
            // the poi-filtered view is what should be on screen instead.
            if (!poiMode) {
                spawnGtDisplay(mapEntry.getKey(), text);
            }
        }
        pistonDisplayEntries.clear();
    }

    // Builds poiEntries for the whole run from the uncapped POI-only feed
    // (observerPoiDisplayEntries / pistonPoiDisplayEntries), completely
    // independent of the normal display's first-two-actions cap and of the
    // piston single-action orange recolor - POI always shows every action
    // recorded, each with its true ON/OFF or extend/retract color. The
    // actual GT number shown is computed later, relative to the queried
    // centerGt - see spawnPointOfInterest.
    private void buildPoiEntries() {
        for (Map.Entry<BlockPos, ObserverDisplayEntry> mapEntry : observerPoiDisplayEntries.entrySet()) {
            for (ObserverActionRecord a : mapEntry.getValue().actions) {
                poiEntries.add(new PoiEntry(mapEntry.getKey(), a.tick, a.suffix, false, a.poweredOn, NO_COUNT));
            }
        }
        observerPoiDisplayEntries.clear();

        for (Map.Entry<BlockPos, PistonDisplayEntry> mapEntry : pistonPoiDisplayEntries.entrySet()) {
            for (PistonActionRecord a : mapEntry.getValue().actions) {
                poiEntries.add(new PoiEntry(mapEntry.getKey(), a.tick, a.suffix, true, a.extending, a.blockCount));
            }
        }
        pistonPoiDisplayEntries.clear();
    }

    /**
     * Re-summons only the actions from the most recent simulation run that
     * happened within {@code window} game-ticks of {@code centerGt}
     * (inclusive on both ends), replacing whatever text displays currently
     * exist. Multiple matching actions at the same block still land on one
     * display, same as a normal run. Unlike the normal end-of-run display,
     * this is not limited to an observer/piston's first two actions - every
     * action recorded for a block that falls inside the window is shown.
     * <p>
     * The GT shown on each display is the actual simulation tick the action
     * happened on - {@code centerGt} is only used to pick which actions fall
     * inside the window, it is no longer subtracted out.
     *
     * @return the number of displays spawned
     */
    public int spawnPointOfInterest(int centerGt, int window) {
        Map<BlockPos, List<PoiEntry>> byPos = new LinkedHashMap<>();
        for (PoiEntry e : poiEntries) {
            if (Math.abs(e.tick() - centerGt) > window) continue;
            byPos.computeIfAbsent(e.pos(), p -> new ArrayList<>()).add(e);
        }

        killAllTextDisplays();

        for (Map.Entry<BlockPos, List<PoiEntry>> group : byPos.entrySet()) {
            StringBuilder top = new StringBuilder();
            StringBuilder bottom = new StringBuilder();
            for (PoiEntry e : group.getValue()) {
                int gt = e.tick(); // actual simulation GT, not relative to centerGt

                String topPiece;
                String bottomPiece = null;
                if (e.isPiston()) {
                    String color = e.stateOn() ? "§e" : "§a"; // extend / retract
                    topPiece = color + gt + e.suffix();
                    if (e.blockCount() != NO_COUNT) {
                        String countColor = e.blockCount() > 12 ? "§c" : (e.stateOn() ? "§e" : "§a");
                        bottomPiece = countColor + e.blockCount();
                    }
                } else {
                    String color = e.stateOn() ? "§b" : "§d"; // ON / OFF
                    topPiece = color + gt + e.suffix();
                }

                if (top.length() > 0) top.append(" ");
                top.append(topPiece);
                if (bottomPiece != null) {
                    if (bottom.length() > 0) bottom.append(" ");
                    bottom.append(bottomPiece);
                }
            }
            String text = bottom.length() > 0 ? top + "\n" + bottom : top.toString();
            spawnGtDisplay(group.getKey(), text);
        }

        // Displacement ("." / "█") markers are part of the full-run
        // displacement display system, which is intentionally skipped while
        // in poi mode - only the windowed piston/observer actions show here.

        return byPos.size();
    }

    /**
     * Restores the full end-of-run display - every piston/observer that had
     * an action during the simulation, exactly as it looked right after
     * {@code /slimestone} finished running - replacing whatever text
     * displays currently exist. This is what a bare {@code /poi} (no gt)
     * resets back to after a windowed {@code /poi <gt> [window]} has
     * replaced it with a filtered view.
     *
     * @return the number of displays spawned
     */
    public int spawnFullDisplay() {
        killAllTextDisplays();
        for (Map.Entry<BlockPos, String> entry : fullDisplayText.entrySet()) {
            spawnGtDisplay(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<BlockPos, String> entry : displacementDisplayText.entrySet()) {
            spawnGtDisplay(entry.getKey(), entry.getValue());
        }
        return fullDisplayText.size() + displacementDisplayText.size();
    }

    private void killAllTextDisplays() {
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "kill @e[type=minecraft:text_display]"
        );
    }

    private void spawnGtDisplay(BlockPos targetPos, String jsonText) {
        double x = targetPos.getX() + 0.5;
        double y = targetPos.getY() + 0.5;
        double z = targetPos.getZ() + 0.5;

        String summon = String.format(
                java.util.Locale.US,
                "summon minecraft:text_display %.3f %.3f %.3f {text:'%s',billboard:\"center\",see_through:1b,shadow:0b,default_background:0b,background:0,alignment:\"center\",line_width:200}",
                x, y, z, jsonText
        );

        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                summon
        );
    }


    public void log(String msg) {
        String phaseColor = switch (currentPhase) {
            case "INIT" -> "§b";               // Aqua
            case "TILE TICK" -> "§3";          // Dark Aqua
            case "BLOCK ENTITIES" -> "§e";     // Yellow
            case "BLOCK EVENTS" -> "§6";       // Gold
            case "NEIGHBOR UPDATES" -> "§d";   // Light Purple
            default -> "§7";                   // Gray
        };
        player.sendSystemMessage(Component.literal(String.format("%s[GT %d | %s] §f%s", phaseColor, currentTick, currentPhase, msg)));
    }

    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    public void setBlockRaw(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    public void setBlock(BlockPos pos, BlockState state) {
        setBlock(pos, state, UPDATE_ALL); // Default to flag 3
    }

    public void setBlock(BlockPos pos, BlockState state, int flags) {
        BlockState oldState = getBlockState(pos);
        setBlockRaw(pos, state);
        //log("Set block at " + pos.toShortString() + " to " + state.getBlock().getName().getString());

        // Emulate vanilla calling onPlace immediately after setting the chunk data
        handleOnPlace(pos, state, oldState);

        // Fetch the state AGAIN to see if onPlace modified it recursively
        BlockState currentState = getBlockState(pos);

        // THE VANILLA CHECK: If onPlace changed the state, suppress all automated updates
        if (currentState == state) {
            if ((flags & UPDATE_NEIGHBORS) != 0) {
                updateNeighborsAt(pos);
            }

            if ((flags & UPDATE_KNOWN_SHAPE) == 0) {
                fireShapeUpdates(pos); // Tell neighbors our shape changed
                evaluateOwnShape(pos, currentState); // Tell ourselves to evaluate surroundings
            }
        } else {
            //log("§8[Vanilla Mechanics] Updates suppressed at " + pos.toShortString() + " because onPlace modified the state.");
        }
    }

    private void handleOnPlace(BlockPos pos, BlockState state, BlockState oldState) {
        if (state.is(oldState.getBlock())) return;

        // --- Observer Placement / Landing Logic ---
        if (state.is(Blocks.OBSERVER)) {
            boolean isPowered = state.getValue(BlockStateProperties.POWERED);

            // Vanilla ObserverBlock.onPlace: Reset spurious powered state if no tick is pending.
            if (isPowered && !hasScheduledTick(pos, Blocks.OBSERVER)) {
                BlockState unpowered = state.setValue(BlockStateProperties.POWERED, false);

                // Vanilla uses flag 18: UPDATE_KNOWN_SHAPE (16) | UPDATE_CLIENTS (2)
                // This intentionally bypasses UPDATE_NEIGHBORS (1) for this nested call
                setBlock(pos, unpowered, UPDATE_KNOWN_SHAPE | UPDATE_CLIENTS);

                //log("§3[Observer] onPlace reset spurious POWERED state at " + pos.toShortString());
                updateNeighborsFromObserver(pos, unpowered);
            }
        }
        if (state.getBlock() instanceof PistonBaseBlock) {
            pistonMechanics.checkIfExtend(pos, state);
        }
    }

    private void evaluateOwnShape(BlockPos pos, BlockState state) {
        // Emulates a placed block running its own shape checks against neighbors upon landing
        if (state.is(Blocks.OBSERVER)) {
            if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(pos, Blocks.OBSERVER)) {
                //log("§3[Schedule] Observer at " + pos.toShortString() + " → fires next tick");
                scheduleTick(pos, Blocks.OBSERVER, 2);
            }
        }
    }
    /**
     * Returns true if there is already a pending tick for {@code (pos, type)}.
     * Mirrors {@code LevelTickAccess.hasScheduledTick(pos, type)}.
     */
    public boolean hasScheduledTick(BlockPos pos, Block type) {
        for (SimScheduledTick t : scheduledBlockTicks) {
            if (t.type() == type && t.pos().equals(pos)) return true;
        }
        return false;
    }

    public void scheduleTick(BlockPos pos, Block type, int delay) {
        if (hasScheduledTick(pos, type)) return;
        long fireTick = currentTick + delay;
        scheduledBlockTicks.add(new SimScheduledTick(pos.immutable(), type, fireTick, subTickOrderCounter++));

    }

    public void runTickLoop(int maxTicks, boolean poiMode) {

        // Snapshot the block layout before anything moves - spawnDisplacementDisplays
        // uses this afterward to also flag blocks that never moved at all (not just
        // ones that moved and came back home), since recordDisplacement alone can
        // only ever tell us about blocks PistonMechanics actually grabbed.
        Map<BlockPos, BlockState> initialBlocks = new HashMap<>(blocks);

        for (currentTick = 0; currentTick < maxTicks; currentTick++) {
            boolean active = false;


            List<SimScheduledTick> currentlyTicking = new ArrayList<>();
            while (!scheduledBlockTicks.isEmpty() && scheduledBlockTicks.peek().triggerTick() <= currentTick) {
                currentlyTicking.add(scheduledBlockTicks.poll());
            }

            for (SimScheduledTick tick : currentlyTicking) {
                active = true;
                processScheduledTick(tick);
                flushNeighborUpdates();
            }

            // FLUSH OBSERVER LABELS HERE
            flushObserverDisplays(currentTick);
            flushObserverPoiDisplays(currentTick);

            // ── PHASE 2: BLOCK EVENTS ─────────────────────────────────────────
            currentPhase = "BLOCK EVENTS";
            while (!blockEvents.isEmpty()) {
                active = true;
                BlockEvent event = blockEvents.peek();
                pistonMechanics.triggerPistonEvent(event.pos(), event.state(), event.type(), event.dir());
                flushNeighborUpdates();
                blockEvents.poll();
            }

            // FLUSH PISTON LABELS HERE
            flushPistonDisplays(currentTick);
            flushPistonPoiDisplays(currentTick);

            // ── PHASE 3: BLOCK ENTITIES ────────────────────────────────────────
            currentPhase = "BLOCK ENTITIES";
            List<SimPistonMovingEntity> tickingBEs = new ArrayList<>(blockEntities.values());
            for (SimPistonMovingEntity be : tickingBEs) {
                active = true;
                be.tick(this);
                flushNeighborUpdates();
            }



            if (!active && blockEvents.isEmpty() && neighborUpdates.isEmpty() && scheduledBlockTicks.isEmpty()) {
                //log("§8Simulation settled. Halting early.");
                break;
            }
        }

        // Simulation window is over - spawn each piston's merged Extend/Retract row now.
        spawnPistonDisplays(poiMode);
        // ...and each observer's merged ON/OFF row.
        spawnObserverDisplays(poiMode);
        // ...and build the uncapped POI action list from the parallel feed.
        buildPoiEntries();
        // ...and a "." marker for every other block that ended the run net-0
        // or net-2 away from where it started - but never while in poi mode,
        // which doesn't use the displacement display system at all.
        if (!poiMode) {
            spawnDisplacementDisplays(initialBlocks);
        }
    }
    public int getCurrentTick() {
        return currentTick;
    }
    private void processScheduledTick(SimScheduledTick tick) {
        BlockState state = getBlockState(tick.pos());

        if (!state.is(tick.type())) {
            //log("§8[Tick] Cancelled stale " + tick.type().getName().getString()
            //        + " tick at " + tick.pos().toShortString() + " (block changed)");
            return;
        }

        if (tick.type() == Blocks.OBSERVER) {
            processObserverTick(tick.pos(), state);
        } else if (tick.type() == Blocks.REDSTONE_LAMP) {
            processRedstoneLampTick(tick.pos(), state);
        } else if (tick.type() == Blocks.NOTE_BLOCK) {
            processForcedNoteBlockTick(tick.pos(), state);
        } else if (tick.type() instanceof TrapDoorBlock) {
            processForcedTrapDoorTick(tick.pos(), state);
        } else {
            // Nothing else in this simulation ever schedules a tick for any
            // other block type - this is exclusively the /slimestone starting
            // event for "any block that isn't a piston/observer/note
            // block/trapdoor" (see Slimestone#runSimulation), so treat it as
            // that block having just been broken/removed.
            processForcedRemovalTick(tick.pos(), state);
        }
    }

    private void processObserverTick(BlockPos pos, BlockState state) {
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);

        if (wasPowered) {
            BlockState unpowered = state.setValue(BlockStateProperties.POWERED, false);
            setBlockRaw(pos, unpowered);
            //log("§3[Observer] " + pos.toShortString() + " → POWERED=false (pulse end)");
            ObserverDebugger.logExpected(pos, false, currentTick);

            // --- Display the GT for this OFF action, if within the first two ---
            queueObserverDisplay(pos, false);
            // --------------------------------------------------

            // 1. Emit Shape Updates to immediate neighbors
            fireShapeUpdates(pos);
            // 2. Emit Redstone Updates to the back block and its neighbors
            updateNeighborsFromObserver(pos, unpowered);
        } else {
            BlockState powered = state.setValue(BlockStateProperties.POWERED, true);
            setBlockRaw(pos, powered);
            //log("§3[Observer] " + pos.toShortString() + " → POWERED=true (pulse start)");
            ObserverDebugger.logExpected(pos, true, currentTick);

            // --- Display the GT for this ON action, if within the first two ---
            queueObserverDisplay(pos, true);
            // --------------------------------------------------

            fireShapeUpdates(pos);
            scheduleTick(pos, Blocks.OBSERVER, 2);

            updateNeighborsFromObserver(pos, powered);
        }
    }

    void updateNeighborsFromObserver(BlockPos obsPos, BlockState obsState) {
        Direction facing = obsState.getValue(BlockStateProperties.FACING);
        BlockPos outputPos = obsPos.relative(facing.getOpposite());

        // 1. Emulate vanilla neighborChanged for the output block directly.
        neighborUpdates.add(new NeighborUpdate(outputPos, obsPos));

        // 2. Emulate updateNeighborsAtExceptFromFacing.
        for (Direction dir : UPDATE_ORDER) {
            // Skip the direction pointing back at the observer
            if (dir == facing) continue;
            neighborUpdates.add(new NeighborUpdate(outputPos.relative(dir), outputPos));
        }
    }

    private void flushNeighborUpdates() {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";
        while (!neighborUpdates.isEmpty()) {
            NeighborUpdate update = neighborUpdates.poll();
            BlockState state = getBlockState(update.pos());
            // Pass the fromPos to the handler
            handleNeighborUpdate(update.pos(), state, update.fromPos());
        }
        currentPhase = previousPhase;
    }

    public void updateNeighborsAt(BlockPos pos) {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";

        for (Direction dir : UPDATE_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState state = getBlockState(neighborPos);
            // Pass 'pos' as the fromPos
            handleNeighborUpdate(neighborPos, state, pos);
        }

        currentPhase = previousPhase;
    }


    private void handleNeighborUpdate(BlockPos pos, BlockState state, BlockPos fromPos) {

        // --- NOTE BLOCK ---
        if (state.is(Blocks.NOTE_BLOCK)) {
            handleNoteBlockNeighborChanged(pos, state);
        }

        // --- REDSTONE LAMP ---
        else if (state.is(Blocks.REDSTONE_LAMP)) {
            handleRedstoneLampNeighborChanged(pos, state);
        }

        // --- TRAPDOOR ---
        else if (state.getBlock() instanceof TrapDoorBlock) {
            handleTrapDoorNeighborChanged(pos, state);
        }

        // --- PISTON LOGIC (delegated to PistonMechanics) ---
        else if (state.getBlock() instanceof PistonBaseBlock) {
            pistonMechanics.checkIfExtend(pos, state);
        } else if (state.getBlock() instanceof PistonHeadBlock) {
            pistonMechanics.handlePistonHeadNeighborUpdate(pos, state);
        }
    }
    public void fireShapeUpdates(BlockPos pos) {
        String previousPhase = currentPhase;
        currentPhase = "SHAPE UPDATES";

        for (Direction dir : UPDATE_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState state = getBlockState(neighborPos);

            if (state.is(Blocks.OBSERVER)) {
                // Emulate the Mixin's updateShape logging exactly
                //log("§6[ShapeUpdate] Detected update from " + dir.getOpposite().getName() + " side at " + neighborPos.toShortString());

                Direction facing = state.getValue(BlockStateProperties.FACING);
                BlockPos lookingAt = neighborPos.relative(facing);

                if (lookingAt.equals(pos)) {
                    // This block matches vanilla's startSignal behavior
                    if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(neighborPos, Blocks.OBSERVER)) {
                        //log("§3[Schedule] Observer at " + neighborPos.toShortString() + " → fires next tick");
                        scheduleTick(neighborPos, Blocks.OBSERVER, 2);
                    }
                }
            } else if (state.is(Blocks.NOTE_BLOCK)) {
                if (dir.getAxis() == Direction.Axis.Y) {
                    BlockState updated = simSetNoteBlockInstrument(neighborPos, state);
                    if (updated != state) {

                        // Replaced setBlockRaw with setBlock + UPDATE_CLIENTS
                        // This ensures bit 16 (UPDATE_KNOWN_SHAPE) is NOT set,
                        // forcing fireShapeUpdates() to run and notify observers.
                        setBlock(neighborPos, updated, UPDATE_CLIENTS);

                        //log("§a[NoteBlock] Instrument updated at " + neighborPos.toShortString()
                        //        + " → " + updated.getValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT).name());
                    }
                }
            }
        }

        currentPhase = previousPhase;
    }

    public boolean hasRedstoneBlockPower(BlockPos pos, Direction pistonFacing) {
        // ── Loop 1: Direct neighbours (all sides except the piston's push face)
        for (Direction dir : Direction.values()) {
            if (dir == pistonFacing) continue;
            if (simHasSignal(pos.relative(dir), dir)) return true;
        }

        // ── Loop 2: QC — neighbours of the block above (all sides except below)
        BlockPos above = pos.above();
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (simHasSignal(above.relative(dir), dir)) return true;
        }

        return false;
    }

    private boolean simHasSignal(BlockPos blockPos, Direction queryDir) {
        BlockState state = getBlockState(blockPos);

        if (state.is(Blocks.REDSTONE_BLOCK)) return true;

        // Vanilla logic: An observer emits signal 15 if powered AND its FACING matches the queried direction.
        if (state.is(Blocks.OBSERVER)
                && state.getValue(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.FACING) == queryDir) {
            return true;
        }

        // Solid conductors re-emit direct (strong) signals from their neighbours.
        if (isSimRedstoneConductor(state)) {
            return simGetDirectSignalTo(blockPos) > 0;
        }

        return false;
    }
    private boolean isSimRedstoneConductor(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.REDSTONE_BLOCK)) return false;
        if (state.is(Blocks.OBSERVER)) return false;
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) return false;
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) return false;
        if (state.is(Blocks.HONEY_BLOCK)) return false; // Slime blocks are solid blocks unlike honey
        if (state.getBlock() instanceof TrapDoorBlock) return false; // Trapdoors are not solid

        // Rails (regular, powered, activator, detector) share BaseRailBlock
        if (state.getBlock() instanceof BaseRailBlock) return false;

        // Anvil (all 3 damage states)
        if (state.getBlock() instanceof AnvilBlock) return false;

        // Carpets (wool carpets)
        if (state.getBlock() instanceof CarpetBlock) return false;

        // Cauldron (empty, water, lava, powder snow)
        if (state.getBlock() instanceof AbstractCauldronBlock) return false;

        if (state.is(Blocks.CHAIN)) return false;
        if (state.is(Blocks.COMPOSTER)) return false;

        // Copper Bulb (all oxidation/wax states)
        if (state.is(Blocks.COPPER_BULB)
                || state.is(Blocks.EXPOSED_COPPER_BULB)
                || state.is(Blocks.WEATHERED_COPPER_BULB)
                || state.is(Blocks.OXIDIZED_COPPER_BULB)
                || state.is(Blocks.WAXED_COPPER_BULB)
                || state.is(Blocks.WAXED_EXPOSED_COPPER_BULB)
                || state.is(Blocks.WAXED_WEATHERED_COPPER_BULB)
                || state.is(Blocks.WAXED_OXIDIZED_COPPER_BULB)) return false;

        // Copper Grate (all oxidation/wax states)
        if (state.is(Blocks.COPPER_GRATE)
                || state.is(Blocks.EXPOSED_COPPER_GRATE)
                || state.is(Blocks.WEATHERED_COPPER_GRATE)
                || state.is(Blocks.OXIDIZED_COPPER_GRATE)
                || state.is(Blocks.WAXED_COPPER_GRATE)
                || state.is(Blocks.WAXED_EXPOSED_COPPER_GRATE)
                || state.is(Blocks.WAXED_WEATHERED_COPPER_GRATE)
                || state.is(Blocks.WAXED_OXIDIZED_COPPER_GRATE)) return false;

        // Coral fans + floor corals (not the solid Coral Block)
        if (state.getBlock() instanceof BaseCoralPlantTypeBlock) return false;

        // Dirt Path, Farmland
        if (state.getBlock() instanceof DirtPathBlock) return false;
        if (state.getBlock() instanceof FarmBlock) return false;

        if (state.is(Blocks.DRAGON_EGG)) return false;
        if (state.is(Blocks.END_ROD)) return false;

        // Fences, Fence Gates
        if (state.getBlock() instanceof FenceBlock) return false;
        if (state.getBlock() instanceof FenceGateBlock) return false;

        // Ice + Frosted Ice (FrostedIceBlock extends IceBlock)
        if (state.getBlock() instanceof IceBlock) return false;

        // Glass, stained glass, glass pane, stained glass panes
        if (state.is(Blocks.GLASS)) return false;
        if (state.is(Blocks.GLASS_PANE)) return false;
        if (state.getBlock() instanceof StainedGlassBlock) return false;
        if (state.getBlock() instanceof StainedGlassPaneBlock) return false;

        if (state.is(Blocks.GLOWSTONE)) return false;
        if (state.is(Blocks.HEAVY_CORE)) return false;

        // Iron Bars
        if (state.getBlock() instanceof IronBarsBlock) return false;

        if (state.is(Blocks.LIGHTNING_ROD)) return false;
        if (state.is(Blocks.POINTED_DRIPSTONE)) return false;
        if (state.is(Blocks.POWDER_SNOW)) return false;
        if (state.is(Blocks.SCAFFOLDING)) return false;
        if (state.is(Blocks.SEA_LANTERN)) return false;

        // Single slabs only (double slabs remain conductive)
        if (state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) return false;

        // All stairs (official Mojang name is StairBlock, not StairsBlock)
        if (state.getBlock() instanceof StairBlock) return false;

        if (state.is(Blocks.SNIFFER_EGG)) return false;
        if (state.is(Blocks.STONECUTTER)) return false;
        if (state.is(Blocks.TINTED_GLASS)) return false;
        if (state.is(Blocks.TNT)) return false;

        // All walls
        if (state.getBlock() instanceof WallBlock) return false;

        return true;
    }

    private int simGetDirectSignalTo(BlockPos blockPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = blockPos.relative(dir);
            BlockState neighborState = getBlockState(neighborPos);



            // Ensure strong power is checked from adjacent observers
            if (neighborState.is(Blocks.OBSERVER)
                    && neighborState.getValue(BlockStateProperties.POWERED)
                    && neighborState.getValue(BlockStateProperties.FACING) == dir) {
                return 15;
            }
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOTE BLOCK
    // ─────────────────────────────────────────────────────────────────────────

    public boolean simHasNeighborSignal(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (simHasSignal(pos.relative(dir), dir)) return true;
        }
        return false;
    }

    private void handleNoteBlockNeighborChanged(BlockPos pos, BlockState state) {
        boolean signal   = simHasNeighborSignal(pos);
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        if (signal == wasPowered) return;

        //log("§a[NoteBlock] " + pos.toShortString() + " → POWERED=" + signal);
        // flag 3 = UPDATE_ALL (UPDATE_NEIGHBORS | UPDATE_CLIENTS), matching vanilla
        setBlock(pos, state.setValue(BlockStateProperties.POWERED, signal), UPDATE_ALL);
    }

    /**
     * The /slimestone starting event for a note block: rather than computing
     * whether the real neighbor signal would flip POWERED (like
     * {@link #handleNoteBlockNeighborChanged}), this just forces it to the
     * opposite of whatever it currently is - we're pretending the player
     * triggered it directly, right at GT 0, and want to see what that does
     * downstream.
     */
    private void processForcedNoteBlockTick(BlockPos pos, BlockState state) {
        boolean newPowered = !state.getValue(BlockStateProperties.POWERED);
        //log("§a[NoteBlock] " + pos.toShortString() + " → POWERED=" + newPowered + " (forced start)");
        setBlock(pos, state.setValue(BlockStateProperties.POWERED, newPowered), UPDATE_ALL);
    }

    private BlockState simSetNoteBlockInstrument(BlockPos pos, BlockState state) {
        NoteBlockInstrument above = getBlockState(pos.above()).instrument();
        if (above.worksAboveNoteBlock()) {
            return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, above);
        }
        NoteBlockInstrument below  = getBlockState(pos.below()).instrument();
        NoteBlockInstrument result = below.worksAboveNoteBlock() ? NoteBlockInstrument.HARP : below;
        return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, result);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // REDSTONE LAMP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors {@code RedstoneLampBlock.neighborChanged}.
     * <p>
     * Turn-on is immediate (flag 2 → shape updates, no neighbor updates).
     * Turn-off is deferred: a 4-tick scheduled tick is queued, and the tick
     * re-checks power before actually switching off.
     */
    private void handleRedstoneLampNeighborChanged(BlockPos pos, BlockState state) {
        boolean isLit  = state.getValue(BlockStateProperties.LIT);
        boolean signal = simHasNeighborSignal(pos);
        if (isLit == signal) return;

        if (isLit) {
            // Signal lost — schedule a delayed turn-off (vanilla: 4 game-ticks).
            // hasScheduledTick guard inside scheduleTick prevents duplicates.
            //log("§c[Lamp] " + pos.toShortString() + " → turn-off scheduled in 4t");
            scheduleTick(pos, Blocks.REDSTONE_LAMP, 4);
        } else {
            // Signal arrived — turn on immediately.
            //log("§c[Lamp] " + pos.toShortString() + " → LIT=true");
            // Flag 2 = UPDATE_CLIENTS: fires shape updates but NOT neighbor updates,
            // matching vanilla's setBlock(pos, state.cycle(LIT), 2).
            setBlock(pos, state.setValue(BlockStateProperties.LIT, true), UPDATE_CLIENTS);
        }
    }

    /**
     * Mirrors {@code RedstoneLampBlock.tick}.
     * <p>
     * Called 4 ticks after power was lost. Only turns off if the lamp is still
     * lit AND still has no signal — power may have returned during the delay.
     */
    private void processRedstoneLampTick(BlockPos pos, BlockState state) {
        if (state.getValue(BlockStateProperties.LIT) && !simHasNeighborSignal(pos)) {
            //log("§c[Lamp] " + pos.toShortString() + " → LIT=false (4t delay elapsed)");
            setBlock(pos, state.setValue(BlockStateProperties.LIT, false), UPDATE_CLIENTS);
        } else {
            //log("§8[Lamp] " + pos.toShortString() + " → turn-off tick no-op (still powered or already unlit)");
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // TRAPDOOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors {@code TrapDoorBlock.neighborChanged}.
     * <p>
     * All trapdoors are treated as iron trapdoors (redstone-only), so OPEN
     * always tracks POWERED. Vanilla calls
     * {@code level.setBlock(pos, newState, 2)} — flag 2 is UPDATE_CLIENTS,
     * which (in our setBlock) fires shape updates but NOT neighbor updates.
     */
    private void handleTrapDoorNeighborChanged(BlockPos pos, BlockState state) {
        boolean signal    = simHasNeighborSignal(pos);
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        if (signal == wasPowered) return;

        BlockState newState = state
                .setValue(BlockStateProperties.POWERED, signal)
                .setValue(BlockStateProperties.OPEN,    signal);

        //("§9[TrapDoor] " + pos.toShortString()
        //       + " → POWERED=" + signal + ", OPEN=" + signal);

        // Flag 2 = UPDATE_CLIENTS only.
        // setBlock will fire shape updates (bit 16 not set → shape updates run)
        // but NOT neighbor updates (bit 1 not set), matching vanilla exactly.
        setBlock(pos, newState, UPDATE_CLIENTS);
    }

    /**
     * The /slimestone starting event for a trapdoor: rather than computing
     * whether the real neighbor signal would flip POWERED/OPEN (like
     * {@link #handleTrapDoorNeighborChanged}), this just forces both to the
     * opposite of whatever they currently are - we're pretending the player
     * triggered it directly, right at GT 0, and want to see what that does
     * downstream.
     */
    private void processForcedTrapDoorTick(BlockPos pos, BlockState state) {
        boolean newState = !state.getValue(BlockStateProperties.POWERED);
        BlockState updated = state
                .setValue(BlockStateProperties.POWERED, newState)
                .setValue(BlockStateProperties.OPEN,    newState);

        //log("§9[TrapDoor] " + pos.toShortString() + " → POWERED=" + newState + ", OPEN=" + newState + " (forced start)");
        setBlock(pos, updated, UPDATE_CLIENTS);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // ARBITRARY STARTING BLOCK (SIMULATION START)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The /slimestone starting event for anything that isn't a piston,
     * observer, note block, or trapdoor (e.g. glass, wool) - pretends the
     * block was just broken/removed, right at GT 0, so the simulation can
     * show what that triggers downstream. Mirrors a real block break:
     * flag 3 (UPDATE_ALL) so both shape and neighbor updates fire.
     */
    private void processForcedRemovalTick(BlockPos pos, BlockState state) {
        //log("§7[Remove] " + pos.toShortString() + " → AIR (forced start)");
        setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_ALL);
    }


    /**
     * Delegates to {@link PistonMechanics#checkIfExtend}. Kept on VirtualLevel
     * for backward compatibility with callers that previously invoked this
     * directly (e.g. {@code virtualLevel.checkIfExtend(pos, state)}).
     */
    public void checkIfExtend(BlockPos pos, BlockState state) {
        pistonMechanics.checkIfExtend(pos, state);
    }
}