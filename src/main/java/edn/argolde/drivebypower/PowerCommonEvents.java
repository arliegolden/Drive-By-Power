package edn.argolde.drivebypower;

import edn.argolde.drivebypower.wire.PowerNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class PowerCommonEvents {
    private PowerCommonEvents() {
    }

    public static void onLevelTick(final LevelTickEvent.Post event) {
        final Level level = event.getLevel();
        if (!(level instanceof final ServerLevel serverLevel)) {
            return;
        }

        final PowerNetworkManager manager = PowerNetworkManager.get(serverLevel);
        manager.flushPendingGraphRebuild(serverLevel);
        manager.forEachConnection((source, channel, sink, sinkDirection) -> {
            final int transferred = transferEnergy(serverLevel, source, sink, sinkDirection);
            manager.setFlow(source, channel, sink, sinkDirection, transferred);
        });
    }

    private static int transferEnergy(
        final ServerLevel level,
        final BlockPos sourcePos,
        final BlockPos sinkPos,
        final Direction sinkDirection
    ) {
        final IEnergyStorage sink = level.getCapability(Capabilities.EnergyStorage.BLOCK, sinkPos, sinkDirection);
        if (sink == null || !sink.canReceive()) {
            return 0;
        }

        final int maxTransfer = PowerConfig.MAX_FE_PER_TICK.getAsInt();
        final int receivable = sink.receiveEnergy(maxTransfer, true);
        if (receivable <= 0) {
            return 0;
        }

        final Extraction extraction = extractFromSource(level, sourcePos, receivable, true);
        if (extraction.energy() <= 0) {
            return 0;
        }

        final int extracted = getEnergy(level, sourcePos, extraction.side()).extractEnergy(extraction.energy(), false);
        if (extracted <= 0) {
            return 0;
        }

        final int accepted = sink.receiveEnergy(extracted, false);
        if (accepted < extracted) {
            final IEnergyStorage source = getEnergy(level, sourcePos, extraction.side());
            source.receiveEnergy(extracted - accepted, false);
        }
        return accepted;
    }

    private static Extraction extractFromSource(final ServerLevel level, final BlockPos sourcePos, final int amount, final boolean simulate) {
        IEnergyStorage source = getEnergy(level, sourcePos, null);
        if (source != null && source.canExtract()) {
            final int extracted = source.extractEnergy(amount, simulate);
            if (extracted > 0) {
                return new Extraction(null, extracted);
            }
        }

        for (final Direction side : Direction.values()) {
            source = getEnergy(level, sourcePos, side);
            if (source == null || !source.canExtract()) {
                continue;
            }

            final int extracted = source.extractEnergy(amount, simulate);
            if (extracted > 0) {
                return new Extraction(side, extracted);
            }
        }

        return Extraction.EMPTY;
    }

    private static IEnergyStorage getEnergy(final ServerLevel level, final BlockPos pos, final Direction side) {
        return level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
    }

    private record Extraction(Direction side, int energy) {
        private static final Extraction EMPTY = new Extraction(null, 0);
    }
}
