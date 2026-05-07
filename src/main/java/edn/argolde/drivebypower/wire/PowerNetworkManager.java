package edn.argolde.drivebypower.wire;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.schematic.SubLevelSchematicSerializationContext;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import edn.argolde.drivebypower.DriveByPowerMod;
import edn.argolde.drivebypower.wire.graph.PowerNetworkNode.PowerNetworkSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.createmod.catnip.data.WorldAttached;

public final class PowerNetworkManager {
    public static final String WORLD_CHANNEL = "world";
    private static final String CONNECTIONS_KEY = "Connections";
    private static final String SOURCE_KEY = "Source";
    private static final String SINK_KEY = "Sink";
    private static final String SOURCE_OWNER_KEY = "SourceOwnerSubLevel";
    private static final String SINK_OWNER_KEY = "SinkOwnerSubLevel";
    private static final String DIRECTION_KEY = "Direction";
    private static final String CHANNEL_KEY = "Channel";
    private static final String FACING_KEY = "Facing";
    private static final String FLOWS_KEY = "Flows";
    private static final String ENERGY_KEY = "Energy";
    private static final String UNSUPPORTED_CONNECTIONS_KEY = "UnsupportedConnections";
    private static final String SNAPSHOT_VERSION_KEY = "SnapshotVersion";
    private static final String OWNER_SUB_LEVEL_KEY = "OwnerSubLevel";
    private static final String PLACEMENT_RESOLVED_KEY = "PlacementResolved";
    private static final int RELATIVE_SNAPSHOT_VERSION = 2;
    private static final int OWNER_AWARE_SNAPSHOT_VERSION = 3;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_SINKS_PER_SOURCE = 64;
    private static final WorldAttached<PowerNetworkManager> CLIENT_MANAGERS = new WorldAttached<>(level -> new PowerNetworkManager(() -> {}));

    private final Map<Long, Map<String, Set<PowerNetworkSink>>> sinks = new HashMap<>();
    private final Map<Long, Set<SinkReference>> sinkReferences = new HashMap<>();
    private final Map<ConnectionKey, Integer> flows = new HashMap<>();
    private final Runnable dirtyMarker;
    private boolean graphDirty;

    PowerNetworkManager(final Runnable dirtyMarker) {
        this.dirtyMarker = dirtyMarker;
    }

    public static PowerNetworkManager get(final Level level) {
        if (level instanceof final ServerLevel serverLevel) {
            return PowerNetworkSavedData.get(serverLevel);
        }

        return CLIENT_MANAGERS.get(level);
    }

    public static ConnectionResult createConnection(
        final Level level,
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        return get(level).addConnection(level, source, sinkPos, sinkDirection, channel);
    }

    public static boolean hasConnection(
        final Level level,
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        return get(level).containsConnection(source, sinkPos, sinkDirection, channel);
    }

    public static boolean removeConnection(
        final Level level,
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        return get(level).removeConnectionInternal(level, source, sinkPos, sinkDirection, channel);
    }

    public static boolean removeAllFromSource(final Level level, final BlockPos source) {
        return get(level).removeAllFromSourceInternal(level, source);
    }

    public static void handleAssemblyMove(
        final ServerLevel originLevel,
        final ServerLevel resultingLevel,
        final BlockPos oldPos,
        final BlockPos newPos
    ) {
        final PowerNetworkManager originManager = get(originLevel);
        originManager.remapMovedBlockInternal(oldPos, newPos);

        if (resultingLevel != originLevel) {
            final PowerNetworkManager resultingManager = get(resultingLevel);
            if (resultingManager != originManager) {
                resultingManager.remapMovedBlockInternal(oldPos, newPos);
            }
        }
    }

    public ConnectionResult addConnection(
        final Level level,
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        if (source.equals(sinkPos)) {
            return ConnectionResult.FAIL_SAME_BLOCK;
        }

        final long sourceKey = source.asLong();
        if (!sinks.containsKey(sourceKey) && countSourcesInSameDomain(level, source) >= MAX_SOURCES) {
            return ConnectionResult.FAIL_TOO_MANY_SOURCES;
        }

        final Set<PowerNetworkSink> sinksOnChannel = getOrCreateSinksOnChannel(source, channel);
        if (sinksOnChannel.size() >= MAX_SINKS_PER_SOURCE) {
            return ConnectionResult.FAIL_TOO_MANY_SINKS;
        }

        final PowerNetworkSink sink = PowerNetworkSink.of(sinkPos, sinkDirection);
        if (!sinksOnChannel.add(sink)) {
            return ConnectionResult.FAIL_EXISTS;
        }

        addSinkReference(sourceKey, channel, sink);
        dirtyMarker.run();
        return ConnectionResult.OK;
    }

    public boolean containsConnection(
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        return sinks.getOrDefault(source.asLong(), Map.of())
            .getOrDefault(channel, Set.of())
            .contains(PowerNetworkSink.of(sinkPos, sinkDirection));
    }

    public boolean removeConnectionInternal(
        final Level level,
        final BlockPos source,
        final BlockPos sinkPos,
        final Direction sinkDirection,
        final String channel
    ) {
        final long sourceKey = source.asLong();
        final Map<String, Set<PowerNetworkSink>> perChannel = sinks.get(sourceKey);
        if (perChannel == null) {
            return false;
        }

        final Set<PowerNetworkSink> sinksOnChannel = perChannel.get(channel);
        if (sinksOnChannel == null) {
            return false;
        }

        final PowerNetworkSink sink = PowerNetworkSink.of(sinkPos, sinkDirection);
        if (!sinksOnChannel.remove(sink)) {
            return false;
        }

        removeSinkReference(sourceKey, channel, sink);
        flows.remove(ConnectionKey.of(source, channel, sinkPos, sinkDirection));

        if (sinksOnChannel.isEmpty()) {
            perChannel.remove(channel);
        }
        if (perChannel.isEmpty()) {
            sinks.remove(sourceKey);
        }

        dirtyMarker.run();
        return true;
    }

    public boolean removeAllFromSourceInternal(final Level level, final BlockPos source) {
        final long sourceKey = source.asLong();
        final Map<String, Set<PowerNetworkSink>> perChannel = sinks.remove(sourceKey);
        if (perChannel == null) {
            return false;
        }

        perChannel.forEach((channel, sinksOnChannel) -> sinksOnChannel.forEach(sink -> {
            removeSinkReference(sourceKey, channel, sink);
            flows.remove(new ConnectionKey(sourceKey, channel, sink.position(), sink.direction()));
        }));
        dirtyMarker.run();
        return true;
    }

    public Map<Long, Map<String, Set<PowerNetworkSink>>> getNetwork() {
        final Map<Long, Map<String, Set<PowerNetworkSink>>> copy = new HashMap<>();
        sinks.forEach((source, perChannel) -> {
            final Map<String, Set<PowerNetworkSink>> channelCopy = new HashMap<>();
            perChannel.forEach((channel, sinksOnChannel) -> channelCopy.put(channel, Set.copyOf(sinksOnChannel)));
            copy.put(source, Map.copyOf(channelCopy));
        });
        return Map.copyOf(copy);
    }

    public void forEachConnection(final ConnectionConsumer consumer) {
        sinks.forEach((source, perChannel) -> perChannel.forEach((channel, sinksOnChannel) -> {
            final BlockPos sourcePos = BlockPos.of(source);
            sinksOnChannel.forEach(sink -> consumer.accept(
                sourcePos,
                channel,
                BlockPos.of(sink.position()),
                Direction.from3DDataValue(sink.direction())
            ));
        }));
    }

    public List<ConnectionInfo> getConnectionsFor(final BlockPos pos) {
        final long posKey = pos.asLong();
        final List<ConnectionInfo> connections = new ArrayList<>();

        final Map<String, Set<PowerNetworkSink>> sourceConnections = sinks.get(posKey);
        if (sourceConnections != null) {
            sourceConnections.forEach((channel, sinksOnChannel) -> sinksOnChannel.forEach(sink -> {
                final BlockPos sinkPos = BlockPos.of(sink.position());
                final Direction direction = Direction.from3DDataValue(sink.direction());
                connections.add(new ConnectionInfo(pos, channel, sinkPos, direction, true, getFlow(pos, channel, sinkPos, direction)));
            }));
        }

        final Set<SinkReference> references = sinkReferences.get(posKey);
        if (references != null) {
            references.forEach(reference -> {
                final BlockPos sourcePos = BlockPos.of(reference.sourcePos());
                final Direction direction = Direction.from3DDataValue(reference.direction());
                connections.add(new ConnectionInfo(sourcePos, reference.channel(), pos, direction, false, getFlow(sourcePos, reference.channel(), pos, direction)));
            });
        }

        connections.sort(Comparator
            .comparing((ConnectionInfo connection) -> connection.channel())
            .thenComparing(connection -> connection.source().asLong())
            .thenComparing(connection -> connection.sink().asLong())
            .thenComparing(connection -> connection.sinkDirection().get3DDataValue())
        );
        return List.copyOf(connections);
    }

    public void setFlow(
        final BlockPos source,
        final String channel,
        final BlockPos sink,
        final Direction sinkDirection,
        final int energy
    ) {
        final ConnectionKey key = ConnectionKey.of(source, channel, sink, sinkDirection);
        if (energy <= 0) {
            flows.remove(key);
            return;
        }

        flows.put(key, energy);
    }

    public int getFlow(final BlockPos source, final String channel, final BlockPos sink, final Direction sinkDirection) {
        return flows.getOrDefault(ConnectionKey.of(source, channel, sink, sinkDirection), 0);
    }

    public BackupSnapshot createBackupSnapshot(final Level level, final BlockPos backupPos, final Direction savedFacing) {
        final SubLevel backupSubLevel = Sable.HELPER.getContaining(level, backupPos);
        if (backupSubLevel == null) {
            return new BackupSnapshot(new CompoundTag(), 0, 0);
        }

        final SubLevelSchematicSerializationContext context = SubLevelSchematicSerializationContext.getCurrentContext();
        if (context != null && context.getType() == SubLevelSchematicSerializationContext.Type.SAVE) {
            return createSchematicBackupSnapshot(level, backupPos, backupSubLevel, context);
        }

        return createRelativeBackupSnapshot(level, backupPos, backupSubLevel, savedFacing);
    }

    public RestoreResult restoreBackupSnapshot(
        final Level level,
        final BlockPos backupPos,
        final Direction currentFacing,
        final CompoundTag snapshot
    ) {
        final int snapshotVersion = snapshot.getInt(SNAPSHOT_VERSION_KEY);
        if (snapshotVersion >= OWNER_AWARE_SNAPSHOT_VERSION) {
            return restoreOwnerAwareBackupSnapshot(level, snapshot);
        }

        return restoreRelativeBackupSnapshot(level, backupPos, currentFacing, snapshot);
    }

    private BackupSnapshot createRelativeBackupSnapshot(
        final Level level,
        final BlockPos backupPos,
        final SubLevel backupSubLevel,
        final Direction savedFacing
    ) {
        final CompoundTag tag = new CompoundTag();
        final ListTag connections = new ListTag();
        int internalConnections = 0;
        int skippedConnections = 0;

        for (final Map.Entry<Long, Map<String, Set<PowerNetworkSink>>> sourceEntry : sinks.entrySet()) {
            final BlockPos sourcePos = BlockPos.of(sourceEntry.getKey());
            final boolean sourceInside = isSameSubLevel(backupSubLevel, Sable.HELPER.getContaining(level, sourcePos));

            for (final Map.Entry<String, Set<PowerNetworkSink>> channelEntry : sourceEntry.getValue().entrySet()) {
                for (final PowerNetworkSink sink : channelEntry.getValue()) {
                    final BlockPos sinkPos = BlockPos.of(sink.position());
                    final boolean sinkInside = isSameSubLevel(backupSubLevel, Sable.HELPER.getContaining(level, sinkPos));

                    if (sourceInside && sinkInside) {
                        final CompoundTag connection = new CompoundTag();
                        connection.putLong(SOURCE_KEY, sourcePos.subtract(backupPos).asLong());
                        connection.putLong(SINK_KEY, sinkPos.subtract(backupPos).asLong());
                        connection.putByte(DIRECTION_KEY, (byte) sink.direction());
                        connection.putString(CHANNEL_KEY, channelEntry.getKey());
                        connections.add(connection);
                        internalConnections++;
                    } else if (sourceInside || sinkInside) {
                        skippedConnections++;
                    }
                }
            }
        }

        if (!connections.isEmpty()) {
            tag.put(CONNECTIONS_KEY, connections);
            tag.putString(FACING_KEY, savedFacing.getName());
            tag.putInt(SNAPSHOT_VERSION_KEY, RELATIVE_SNAPSHOT_VERSION);
        }
        if (skippedConnections > 0) {
            tag.putInt(UNSUPPORTED_CONNECTIONS_KEY, skippedConnections);
        }

        return new BackupSnapshot(tag, internalConnections, skippedConnections);
    }

    private BackupSnapshot createSchematicBackupSnapshot(
        final Level level,
        final BlockPos backupPos,
        final SubLevel backupSubLevel,
        final SubLevelSchematicSerializationContext context
    ) {
        final CompoundTag tag = new CompoundTag();
        final SubLevelSchematicSerializationContext.SchematicMapping ownerMapping = context.getMapping(backupSubLevel);
        if (ownerMapping != null) {
            tag.putUUID(OWNER_SUB_LEVEL_KEY, ownerMapping.newUUID());
        }

        final ListTag connections = new ListTag();
        int preservedConnections = 0;
        int skippedConnections = 0;

        for (final Map.Entry<Long, Map<String, Set<PowerNetworkSink>>> sourceEntry : sinks.entrySet()) {
            final BlockPos sourcePos = BlockPos.of(sourceEntry.getKey());
            final SubLevel sourceSubLevel = Sable.HELPER.getContaining(level, sourcePos);
            if (!isSameSubLevel(backupSubLevel, sourceSubLevel)) {
                continue;
            }

            for (final Map.Entry<String, Set<PowerNetworkSink>> channelEntry : sourceEntry.getValue().entrySet()) {
                for (final PowerNetworkSink sink : channelEntry.getValue()) {
                    final BlockPos sinkPos = BlockPos.of(sink.position());
                    final SubLevel sinkSubLevel = Sable.HELPER.getContaining(level, sinkPos);

                    final CompoundTag connection = new CompoundTag();
                    final boolean wroteSource = writeSchematicEndpoint(connection, SOURCE_KEY, SOURCE_OWNER_KEY, sourcePos, sourceSubLevel, context);
                    final boolean wroteSink = writeSchematicEndpoint(connection, SINK_KEY, SINK_OWNER_KEY, sinkPos, sinkSubLevel, context);
                    if (!wroteSource || !wroteSink) {
                        skippedConnections++;
                        continue;
                    }

                    connection.putByte(DIRECTION_KEY, (byte) sink.direction());
                    connection.putString(CHANNEL_KEY, channelEntry.getKey());
                    connections.add(connection);
                    preservedConnections++;
                }
            }
        }

        if (!connections.isEmpty()) {
            tag.put(CONNECTIONS_KEY, connections);
            tag.putInt(SNAPSHOT_VERSION_KEY, OWNER_AWARE_SNAPSHOT_VERSION);
        }
        if (skippedConnections > 0) {
            tag.putInt(UNSUPPORTED_CONNECTIONS_KEY, skippedConnections);
        }

        return new BackupSnapshot(tag, preservedConnections, skippedConnections);
    }

    private boolean writeSchematicEndpoint(
        final CompoundTag connection,
        final String positionKey,
        final String ownerKey,
        final BlockPos endpointPos,
        final SubLevel endpointSubLevel,
        final SubLevelSchematicSerializationContext context
    ) {
        if (endpointSubLevel == null) {
            return false;
        }

        final SubLevelSchematicSerializationContext.SchematicMapping mapping = context.getMapping(endpointSubLevel);
        if (mapping != null) {
            connection.putUUID(ownerKey, mapping.newUUID());
            connection.putLong(positionKey, mapping.transform().apply(endpointPos).asLong());
            return true;
        }

        return context.getBoundingBox() != null
            && context.getBoundingBox().contains(endpointPos.getX(), endpointPos.getY(), endpointPos.getZ())
            && writeMainTemplateEndpoint(connection, positionKey, endpointPos, context);
    }

    private boolean writeMainTemplateEndpoint(
        final CompoundTag connection,
        final String positionKey,
        final BlockPos endpointPos,
        final SubLevelSchematicSerializationContext context
    ) {
        if (context.getPlaceTransform() == null) {
            return false;
        }

        connection.putLong(positionKey, context.getPlaceTransform().apply(endpointPos).asLong());
        return true;
    }

    private RestoreResult restoreRelativeBackupSnapshot(
        final Level level,
        final BlockPos backupPos,
        final Direction currentFacing,
        final CompoundTag snapshot
    ) {
        final SubLevel backupSubLevel = Sable.HELPER.getContaining(level, backupPos);
        if (backupSubLevel == null) {
            return new RestoreResult(0, 0, 0, snapshot.getInt(UNSUPPORTED_CONNECTIONS_KEY), 0, false);
        }

        final int snapshotVersion = snapshot.getInt(SNAPSHOT_VERSION_KEY);
        final Direction savedFacing = Direction.byName(snapshot.getString(FACING_KEY));
        final Rotation rotation = snapshotVersion >= OWNER_AWARE_SNAPSHOT_VERSION || savedFacing == null
            ? Rotation.NONE
            : getRotation(savedFacing, currentFacing);
        int restoredConnections = 0;
        int deferredConnections = 0;
        int existingConnections = 0;
        int expectedConnections = 0;

        if (snapshot.contains(CONNECTIONS_KEY, Tag.TAG_LIST)) {
            final ListTag connections = snapshot.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND);
            for (final Tag entry : connections) {
                if (!(entry instanceof final CompoundTag connection)) {
                    continue;
                }

                if (!connection.contains(SOURCE_KEY, Tag.TAG_LONG)
                    || !connection.contains(SINK_KEY, Tag.TAG_LONG)
                    || !connection.contains(DIRECTION_KEY, Tag.TAG_BYTE)
                    || !connection.contains(CHANNEL_KEY, Tag.TAG_STRING)) {
                    continue;
                }

                expectedConnections++;
                final BlockPos sourcePos = backupPos.offset(rotateRelative(BlockPos.of(connection.getLong(SOURCE_KEY)), rotation));
                final BlockPos sinkPos = backupPos.offset(rotateRelative(BlockPos.of(connection.getLong(SINK_KEY)), rotation));
                final Direction sinkDirection = rotateDirection(Direction.from3DDataValue(connection.getByte(DIRECTION_KEY)), rotation);
                final String channel = connection.getString(CHANNEL_KEY);

                if (!isSameSubLevel(backupSubLevel, Sable.HELPER.getContaining(level, sourcePos))
                    || !isSameSubLevel(backupSubLevel, Sable.HELPER.getContaining(level, sinkPos))) {
                    deferredConnections++;
                    continue;
                }

                if (containsConnection(sourcePos, sinkPos, sinkDirection, channel)) {
                    existingConnections++;
                    continue;
                }

                if (addConnection(level, sourcePos, sinkPos, sinkDirection, channel).isSuccess()) {
                    restoredConnections++;
                }
            }
        }

        return new RestoreResult(
            restoredConnections,
            existingConnections,
            deferredConnections,
            snapshot.getInt(UNSUPPORTED_CONNECTIONS_KEY),
            expectedConnections,
            true
        );
    }

    private RestoreResult restoreOwnerAwareBackupSnapshot(final Level level, final CompoundTag snapshot) {
        int restoredConnections = 0;
        int deferredConnections = 0;
        int existingConnections = 0;
        int expectedConnections = 0;

        if (snapshot.contains(CONNECTIONS_KEY, Tag.TAG_LIST)) {
            final ListTag connections = snapshot.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND);
            for (final Tag entry : connections) {
                if (!(entry instanceof final CompoundTag connection)) {
                    continue;
                }

                if (!connection.contains(SOURCE_KEY, Tag.TAG_LONG)
                    || !connection.contains(SINK_KEY, Tag.TAG_LONG)
                    || !connection.contains(DIRECTION_KEY, Tag.TAG_BYTE)
                    || !connection.contains(CHANNEL_KEY, Tag.TAG_STRING)) {
                    continue;
                }

                expectedConnections++;
                final ResolvedEndpoint source = resolveOwnerAwareEndpoint(level, connection, SOURCE_KEY, SOURCE_OWNER_KEY);
                final ResolvedEndpoint sink = resolveOwnerAwareEndpoint(level, connection, SINK_KEY, SINK_OWNER_KEY);
                if (source.isDeferred() || sink.isDeferred()) {
                    deferredConnections++;
                    continue;
                }

                final BlockPos sourcePos = source.position();
                final BlockPos sinkPos = sink.position();
                final Direction sinkDirection = Direction.from3DDataValue(connection.getByte(DIRECTION_KEY));
                final String channel = connection.getString(CHANNEL_KEY);

                if (containsConnection(sourcePos, sinkPos, sinkDirection, channel)) {
                    existingConnections++;
                    continue;
                }

                if (addConnection(level, sourcePos, sinkPos, sinkDirection, channel).isSuccess()) {
                    restoredConnections++;
                }
            }
        }

        return new RestoreResult(
            restoredConnections,
            existingConnections,
            deferredConnections,
            snapshot.getInt(UNSUPPORTED_CONNECTIONS_KEY),
            expectedConnections,
            true
        );
    }

    private ResolvedEndpoint resolveOwnerAwareEndpoint(
        final Level level,
        final CompoundTag connection,
        final String positionKey,
        final String ownerKey
    ) {
        if (connection.hasUUID(ownerKey)) {
            final UUID ownerId = connection.getUUID(ownerKey);
            final SubLevel ownerSubLevel = SubLevelContainer.getContainer(level).getSubLevel(ownerId);
            if (ownerSubLevel == null) {
                DriveByPowerMod.LOGGER.info(
                    "[schematic-debug] Deferred owner-aware endpoint {} because subLevel {} is not available yet.",
                    positionKey,
                    ownerId
                );
                return ResolvedEndpoint.waiting();
            }

            return ResolvedEndpoint.resolved(ownerSubLevel.getPlot().getCenterBlock().offset(BlockPos.of(connection.getLong(positionKey))));
        }

        return ResolvedEndpoint.resolved(BlockPos.of(connection.getLong(positionKey)));
    }

    public void flushPendingGraphRebuild(final Level level) {
        if (!graphDirty) {
            return;
        }

        graphDirty = false;
    }

    public CompoundTag save(final CompoundTag tag) {
        final ListTag connections = new ListTag();
        sinks.forEach((sourceKey, perChannel) -> perChannel.forEach((channel, sinksOnChannel) -> sinksOnChannel.forEach(sink -> {
            final CompoundTag connection = new CompoundTag();
            connection.putLong(SOURCE_KEY, sourceKey);
            connection.putLong(SINK_KEY, sink.position());
            connection.putByte(DIRECTION_KEY, (byte) sink.direction());
            connection.putString(CHANNEL_KEY, channel);
            connections.add(connection);
        })));
        tag.put(CONNECTIONS_KEY, connections);
        return tag;
    }

    public CompoundTag saveClientSnapshot(final CompoundTag tag) {
        save(tag);

        final ListTag flowTags = new ListTag();
        flows.forEach((connection, energy) -> {
            final CompoundTag flow = new CompoundTag();
            flow.putLong(SOURCE_KEY, connection.source());
            flow.putLong(SINK_KEY, connection.sink());
            flow.putByte(DIRECTION_KEY, (byte) connection.direction());
            flow.putString(CHANNEL_KEY, connection.channel());
            flow.putInt(ENERGY_KEY, energy);
            flowTags.add(flow);
        });
        tag.put(FLOWS_KEY, flowTags);
        return tag;
    }

    public void load(final CompoundTag tag) {
        sinks.clear();
        sinkReferences.clear();
        flows.clear();
        graphDirty = false;

        if (!tag.contains(CONNECTIONS_KEY, Tag.TAG_LIST)) {
            return;
        }

        final ListTag connections = tag.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND);
        for (final Tag entry : connections) {
            if (!(entry instanceof final CompoundTag connection)) {
                continue;
            }

            if (!connection.contains(SOURCE_KEY, Tag.TAG_LONG)
                || !connection.contains(SINK_KEY, Tag.TAG_LONG)
                || !connection.contains(DIRECTION_KEY, Tag.TAG_BYTE)
                || !connection.contains(CHANNEL_KEY, Tag.TAG_STRING)) {
                continue;
            }

            final long sourceKey = connection.getLong(SOURCE_KEY);
            final long sinkKey = connection.getLong(SINK_KEY);
            final int direction = connection.getByte(DIRECTION_KEY);
            final String channel = connection.getString(CHANNEL_KEY);
            final PowerNetworkSink sink = new PowerNetworkSink(sinkKey, direction);
            getOrCreateSinksOnChannel(BlockPos.of(sourceKey), channel).add(sink);
            addSinkReference(sourceKey, channel, sink);
        }

        if (!tag.contains(FLOWS_KEY, Tag.TAG_LIST)) {
            return;
        }

        final ListTag flowTags = tag.getList(FLOWS_KEY, Tag.TAG_COMPOUND);
        for (final Tag entry : flowTags) {
            if (!(entry instanceof final CompoundTag flow)
                || !flow.contains(SOURCE_KEY, Tag.TAG_LONG)
                || !flow.contains(SINK_KEY, Tag.TAG_LONG)
                || !flow.contains(DIRECTION_KEY, Tag.TAG_BYTE)
                || !flow.contains(CHANNEL_KEY, Tag.TAG_STRING)
                || !flow.contains(ENERGY_KEY, Tag.TAG_INT)) {
                continue;
            }

            flows.put(
                new ConnectionKey(
                    flow.getLong(SOURCE_KEY),
                    flow.getString(CHANNEL_KEY),
                    flow.getLong(SINK_KEY),
                    flow.getByte(DIRECTION_KEY)
                ),
                flow.getInt(ENERGY_KEY)
            );
        }
    }

    private void remapMovedBlockInternal(final BlockPos oldPos, final BlockPos newPos) {
        if (oldPos.equals(newPos)) {
            return;
        }

        final long oldKey = oldPos.asLong();
        final long newKey = newPos.asLong();
        final Map<String, Set<PowerNetworkSink>> movedSourceConnections = sinks.remove(oldKey);
        final Set<SinkReference> movedSinkReferences = sinkReferences.remove(oldKey);
        if (movedSourceConnections == null && movedSinkReferences == null) {
            return;
        }

        boolean changed = false;

        if (movedSourceConnections != null) {
            final Map<String, Set<PowerNetworkSink>> targetPerChannel = sinks.computeIfAbsent(newKey, ignored -> new HashMap<>());
            movedSourceConnections.forEach((channel, movedSinksOnChannel) -> {
                targetPerChannel.computeIfAbsent(channel, ignored -> new HashSet<>()).addAll(movedSinksOnChannel);
                movedSinksOnChannel.forEach(sink -> {
                    final Integer flow = flows.remove(new ConnectionKey(oldKey, channel, sink.position(), sink.direction()));
                    if (flow != null) {
                        flows.put(new ConnectionKey(newKey, channel, sink.position(), sink.direction()), flow);
                    }
                    removeSinkReference(oldKey, channel, sink);
                    addSinkReference(newKey, channel, sink);
                });
            });
            changed = true;
        }

        if (movedSinkReferences != null) {
            for (final SinkReference reference : movedSinkReferences) {
                final Map<String, Set<PowerNetworkSink>> perChannel = sinks.get(reference.sourcePos());
                if (perChannel == null) {
                    continue;
                }

                final Set<PowerNetworkSink> sinksOnChannel = perChannel.get(reference.channel());
                if (sinksOnChannel == null) {
                    continue;
                }

                if (sinksOnChannel.remove(new PowerNetworkSink(oldKey, reference.direction()))) {
                    sinksOnChannel.add(new PowerNetworkSink(newKey, reference.direction()));
                    final Integer flow = flows.remove(new ConnectionKey(reference.sourcePos(), reference.channel(), oldKey, reference.direction()));
                    if (flow != null) {
                        flows.put(new ConnectionKey(reference.sourcePos(), reference.channel(), newKey, reference.direction()), flow);
                    }
                    addSinkReference(newKey, reference.sourcePos(), reference.channel(), reference.direction());
                    changed = true;
                }
            }
        }

        if (changed) {
            graphDirty = true;
            dirtyMarker.run();
        }
    }

    private Set<PowerNetworkSink> getOrCreateSinksOnChannel(final BlockPos source, final String channel) {
        return sinks.computeIfAbsent(source.asLong(), ignored -> new HashMap<>())
            .computeIfAbsent(channel, ignored -> new HashSet<>());
    }

    private void addSinkReference(final long sourcePos, final String channel, final PowerNetworkSink sink) {
        addSinkReference(sink.position(), sourcePos, channel, sink.direction());
    }

    private void addSinkReference(final long sinkPos, final long sourcePos, final String channel, final int direction) {
        sinkReferences.computeIfAbsent(sinkPos, ignored -> new HashSet<>())
            .add(new SinkReference(sourcePos, channel, direction));
    }

    private void removeSinkReference(final long sourcePos, final String channel, final PowerNetworkSink sink) {
        final Set<SinkReference> references = sinkReferences.get(sink.position());
        if (references == null) {
            return;
        }

        references.remove(new SinkReference(sourcePos, channel, sink.direction()));
        if (references.isEmpty()) {
            sinkReferences.remove(sink.position());
        }
    }

    private int countSourcesInSameDomain(final Level level, final BlockPos source) {
        final SubLevel sourceSubLevel = Sable.HELPER.getContaining(level, source);
        final UUID sourceSubLevelId = sourceSubLevel == null ? null : sourceSubLevel.getUniqueId();

        int count = 0;
        for (final long existingSourceKey : sinks.keySet()) {
            if (isSameSourceDomain(level, BlockPos.of(existingSourceKey), sourceSubLevelId)) {
                count++;
            }
        }
        return count;
    }

    private boolean isSameSourceDomain(final Level level, final BlockPos source, final UUID expectedSubLevelId) {
        final SubLevel sourceSubLevel = Sable.HELPER.getContaining(level, source);
        final UUID sourceSubLevelId = sourceSubLevel == null ? null : sourceSubLevel.getUniqueId();
        return Objects.equals(sourceSubLevelId, expectedSubLevelId);
    }

    public enum ConnectionResult {
        OK(""),
        FAIL_EXISTS("Connection already exists!"),
        FAIL_TOO_MANY_SOURCES("Exceeded source limit for this structure!"),
        FAIL_TOO_MANY_SINKS("Exceeded sink limit for this source!"),
        FAIL_SAME_BLOCK("Source and sink must be different blocks!");

        private final String description;

        ConnectionResult(final String description) {
            this.description = description;
        }

        public boolean isSuccess() {
            return this == OK;
        }

        public String getDescription() {
            return description;
        }
    }

    public record BackupSnapshot(CompoundTag data, int internalConnections, int skippedConnections) {
    }

    public record RestoreResult(
        int restoredConnections,
        int existingConnections,
        int deferredConnections,
        int skippedConnections,
        int expectedConnections,
        boolean attempted
    ) {
    }

    private record ResolvedEndpoint(BlockPos position, boolean isDeferred) {
        private static ResolvedEndpoint resolved(final BlockPos position) {
            return new ResolvedEndpoint(position, false);
        }

        private static ResolvedEndpoint waiting() {
            return new ResolvedEndpoint(BlockPos.ZERO, true);
        }
    }

    private record SinkReference(long sourcePos, String channel, int direction) {
    }

    private record ConnectionKey(long source, String channel, long sink, int direction) {
        private static ConnectionKey of(
            final BlockPos source,
            final String channel,
            final BlockPos sink,
            final Direction direction
        ) {
            return new ConnectionKey(source.asLong(), channel, sink.asLong(), direction.get3DDataValue());
        }
    }

    public record ConnectionInfo(
        BlockPos source,
        String channel,
        BlockPos sink,
        Direction sinkDirection,
        boolean sourceEndpoint,
        int energyPerTick
    ) {
    }

    @FunctionalInterface
    public interface ConnectionConsumer {
        void accept(BlockPos source, String channel, BlockPos sink, Direction sinkDirection);
    }

    public static int countConnectionsInBackupSnapshot(final CompoundTag snapshot) {
        if (!snapshot.contains(CONNECTIONS_KEY, Tag.TAG_LIST)) {
            return 0;
        }

        return snapshot.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND).size();
    }

    public static int countUnsupportedConnectionsInBackupSnapshot(final CompoundTag snapshot) {
        return snapshot.getInt(UNSUPPORTED_CONNECTIONS_KEY);
    }

    public static boolean isSubLevelOwnedBackupSnapshot(final CompoundTag snapshot) {
        return snapshot.hasUUID(OWNER_SUB_LEVEL_KEY);
    }

    public static CompoundTag transformBackupSnapshotForPlacement(
        final CompoundTag snapshot,
        final BlockPos schematicBackupPos,
        final SubLevelSchematicSerializationContext context
    ) {
        if (context == null) {
            return snapshot;
        }

        final int snapshotVersion = snapshot.getInt(SNAPSHOT_VERSION_KEY);
        if (snapshotVersion >= OWNER_AWARE_SNAPSHOT_VERSION) {
            return transformOwnerAwareSnapshotForPlacement(snapshot, context);
        }

        if (snapshotVersion < RELATIVE_SNAPSHOT_VERSION || isSubLevelOwnedBackupSnapshot(snapshot)) {
            return snapshot;
        }

        return transformRelativeSnapshotForPlacement(snapshot, schematicBackupPos, context.getSetupTransform());
    }

    private static CompoundTag transformOwnerAwareSnapshotForPlacement(
        final CompoundTag snapshot,
        final SubLevelSchematicSerializationContext context
    ) {
        if (snapshot.getBoolean(PLACEMENT_RESOLVED_KEY)
            || context.getSetupTransform() == null
            || context.getPlaceTransform() == null) {
            return snapshot;
        }

        final CompoundTag transformed = snapshot.copy();
        if (!transformed.contains(CONNECTIONS_KEY, Tag.TAG_LIST)) {
            return transformed;
        }

        boolean changed = false;
        final ListTag connections = transformed.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND);
        for (final Tag entry : connections) {
            if (!(entry instanceof final CompoundTag connection)) {
                continue;
            }

            changed |= rewriteOwnerUuidForPlacement(connection, SOURCE_OWNER_KEY, context);
            changed |= rewriteOwnerUuidForPlacement(connection, SINK_OWNER_KEY, context);

            if (connection.contains(SOURCE_KEY, Tag.TAG_LONG) && !connection.hasUUID(SOURCE_OWNER_KEY)) {
                final BlockPos sourcePos = BlockPos.of(connection.getLong(SOURCE_KEY));
                connection.putLong(SOURCE_KEY, transformMainTemplatePosition(sourcePos, context).asLong());
                changed = true;
            }

            if (connection.contains(SINK_KEY, Tag.TAG_LONG) && !connection.hasUUID(SINK_OWNER_KEY)) {
                final BlockPos sinkPos = BlockPos.of(connection.getLong(SINK_KEY));
                connection.putLong(SINK_KEY, transformMainTemplatePosition(sinkPos, context).asLong());
                if (connection.contains(DIRECTION_KEY, Tag.TAG_BYTE)) {
                    final Direction direction = Direction.from3DDataValue(connection.getByte(DIRECTION_KEY));
                    connection.putByte(DIRECTION_KEY, (byte) transformDirection(direction, sinkPos, context.getSetupTransform()).get3DDataValue());
                }
                changed = true;
            }
        }

        changed |= rewriteOwnerUuidForPlacement(transformed, OWNER_SUB_LEVEL_KEY, context);

        if (changed) {
            transformed.putBoolean(PLACEMENT_RESOLVED_KEY, true);
        }

        return transformed;
    }

    private static boolean rewriteOwnerUuidForPlacement(
        final CompoundTag tag,
        final String ownerKey,
        final SubLevelSchematicSerializationContext context
    ) {
        if (!tag.hasUUID(ownerKey)) {
            return false;
        }

        final SubLevelSchematicSerializationContext.SchematicMapping mapping = context.getMapping(tag.getUUID(ownerKey));
        if (mapping == null) {
            return false;
        }

        tag.putUUID(ownerKey, mapping.newUUID());
        return true;
    }

    private static CompoundTag transformRelativeSnapshotForPlacement(
        final CompoundTag snapshot,
        final BlockPos schematicBackupPos,
        final Function<BlockPos, BlockPos> setupTransform
    ) {
        if (setupTransform == null) {
            return snapshot;
        }

        final CompoundTag transformed = snapshot.copy();
        final BlockPos transformedBackupPos = setupTransform.apply(schematicBackupPos);
        final ListTag connections = transformed.getList(CONNECTIONS_KEY, Tag.TAG_COMPOUND);
        for (final Tag entry : connections) {
            if (!(entry instanceof final CompoundTag connection)) {
                continue;
            }

            if (!connection.contains(SOURCE_KEY, Tag.TAG_LONG)
                || !connection.contains(SINK_KEY, Tag.TAG_LONG)
                || !connection.contains(DIRECTION_KEY, Tag.TAG_BYTE)) {
                continue;
            }

            final BlockPos sourcePos = schematicBackupPos.offset(BlockPos.of(connection.getLong(SOURCE_KEY)));
            final BlockPos sinkPos = schematicBackupPos.offset(BlockPos.of(connection.getLong(SINK_KEY)));

            final BlockPos transformedSourcePos = setupTransform.apply(sourcePos);
            final BlockPos transformedSinkPos = setupTransform.apply(sinkPos);

            connection.putLong(SOURCE_KEY, transformedSourcePos.subtract(transformedBackupPos).asLong());
            connection.putLong(SINK_KEY, transformedSinkPos.subtract(transformedBackupPos).asLong());

            final Direction direction = Direction.from3DDataValue(connection.getByte(DIRECTION_KEY));
            final Direction transformedDirection = transformDirection(direction, schematicBackupPos, setupTransform);
            connection.putByte(DIRECTION_KEY, (byte) transformedDirection.get3DDataValue());
        }

        final Direction savedFacing = Direction.byName(transformed.getString(FACING_KEY));
        if (savedFacing != null) {
            transformed.putString(FACING_KEY, transformDirection(savedFacing, schematicBackupPos, setupTransform).getName());
        }

        return transformed;
    }

    private static BlockPos transformMainTemplatePosition(
        final BlockPos schematicPosition,
        final SubLevelSchematicSerializationContext context
    ) {
        return context.getPlaceTransform().apply(context.getSetupTransform().apply(schematicPosition));
    }

    private static boolean isSameSubLevel(final SubLevel expected, final SubLevel actual) {
        return expected != null && actual != null && Objects.equals(expected.getUniqueId(), actual.getUniqueId());
    }

    private static Rotation getRotation(final Direction from, final Direction to) {
        if (from == to) {
            return Rotation.NONE;
        }
        if (from.getClockWise() == to) {
            return Rotation.CLOCKWISE_90;
        }
        if (from.getOpposite() == to) {
            return Rotation.CLOCKWISE_180;
        }
        if (from.getCounterClockWise() == to) {
            return Rotation.COUNTERCLOCKWISE_90;
        }
        return Rotation.NONE;
    }

    private static Direction rotateDirection(final Direction direction, final Rotation rotation) {
        return direction.getAxis().isVertical() ? direction : rotation.rotate(direction);
    }

    private static Direction transformDirection(
        final Direction direction,
        final BlockPos origin,
        final Function<BlockPos, BlockPos> setupTransform
    ) {
        if (direction.getAxis().isVertical()) {
            final BlockPos delta = setupTransform.apply(origin.relative(direction)).subtract(setupTransform.apply(origin));
            return Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ());
        }

        final BlockPos delta = setupTransform.apply(origin.relative(direction)).subtract(setupTransform.apply(origin));
        final Direction transformed = Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ());
        return transformed == null ? direction : transformed;
    }

    private static BlockPos rotateRelative(final BlockPos relative, final Rotation rotation) {
        return switch (rotation) {
            case NONE -> relative;
            case CLOCKWISE_90 -> new BlockPos(-relative.getZ(), relative.getY(), relative.getX());
            case CLOCKWISE_180 -> new BlockPos(-relative.getX(), relative.getY(), -relative.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(relative.getZ(), relative.getY(), -relative.getX());
        };
    }
}
