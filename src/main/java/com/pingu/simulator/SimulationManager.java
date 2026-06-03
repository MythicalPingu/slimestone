package com.pingu.simulator;

import com.pingu.Slimestone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages active simulation sessions, one per player.
 * Simulations run on a dedicated thread pool — never blocking the game thread.
 */
public class SimulationManager {

    private static final SimulationManager INSTANCE = new SimulationManager();

    // Single-thread executor: simulations are sequential per player
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PistonSim-Worker");
        t.setDaemon(true);
        return t;
    });

    // Per-player: the most recent simulation result
    private final Map<UUID, SimulationResult> results = new ConcurrentHashMap<>();

    // Per-player: futures so we can cancel an in-progress sim before starting a new one
    private final Map<UUID, Future<?>> activeFutures = new ConcurrentHashMap<>();

    private SimulationManager() {}

    public static SimulationManager get() {
        return INSTANCE;
    }

    /**
     * Start a simulation for the given player.
     * Captures the world snapshot synchronously (on the calling/game thread),
     * then runs the simulation asynchronously.
     */
    public void startSimulation(ServerPlayer player, BlockPos pistonPos, Direction facing) {
        UUID id = player.getUUID();

        // Cancel any running simulation
        Future<?> prev = activeFutures.get(id);
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }

        // Snapshot must happen on game thread — we're called from command which is on game thread
        Level level = player.level();
        VirtualWorld snapshot = new VirtualWorld(level, pistonPos);

        Slimestone.LOGGER.info("[PistonSim] Starting simulation for {} at {} facing {}", player.getName().getString(), pistonPos, facing);

        Future<?> future = executor.submit(() -> {
            try {
                long start = System.currentTimeMillis();
                PistonSimulator sim = new PistonSimulator(snapshot);
                List<PistonEvent> events = sim.simulate(pistonPos, facing);
                long elapsed = System.currentTimeMillis() - start;

                Slimestone.LOGGER.info("[PistonSim] Simulation for {} complete in {}ms, {} events", player.getName().getString(), elapsed, events.size());
                results.put(id, new SimulationResult(pistonPos, facing, events, elapsed));

            } catch (Exception e) {
                Slimestone.LOGGER.error("[PistonSim] Simulation failed for {}", player.getName().getString(), e);
            }
        });

        activeFutures.put(id, future);
    }

    /**
     * Retrieve the last completed simulation result for this player, or null if none.
     */
    public SimulationResult getResult(UUID playerId) {
        return results.get(playerId);
    }

    /**
     * Clear the simulation result for this player.
     */
    public void clearResult(UUID playerId) {
        results.remove(playerId);
        Future<?> f = activeFutures.remove(playerId);
        if (f != null) f.cancel(true);
    }

    public boolean isRunning(UUID playerId) {
        Future<?> f = activeFutures.get(playerId);
        return f != null && !f.isDone();
    }

    /**
     * Container for a completed simulation result.
     */
    public record SimulationResult(
        BlockPos origin,
        Direction facing,
        List<PistonEvent> events,
        long elapsedMs
    ) {
        public List<PistonEvent> getFailures() {
            return events.stream().filter(e -> !e.isSuccess()).toList();
        }

        public List<PistonEvent> getSuccesses() {
            return events.stream().filter(PistonEvent::isSuccess).toList();
        }

        /** Group events by game tick */
        public Map<Integer, List<PistonEvent>> byTick() {
            Map<Integer, List<PistonEvent>> map = new TreeMap<>();
            for (PistonEvent e : events) {
                map.computeIfAbsent(e.getGameTick(), k -> new ArrayList<>()).add(e);
            }
            return map;
        }
    }
}
