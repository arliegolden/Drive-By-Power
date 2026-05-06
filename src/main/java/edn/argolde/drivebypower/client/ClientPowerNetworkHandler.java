package edn.stratodonut.drivebypower.client;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import edn.stratodonut.drivebypower.DriveByPowerMod;
import edn.stratodonut.drivebypower.PowerItems;
import edn.stratodonut.drivebypower.network.PowerAddConnectionPacket;
import edn.stratodonut.drivebypower.network.PowerNetworkRequestSyncPacket;
import edn.stratodonut.drivebypower.network.PowerRemoveConnectionPacket;
import edn.stratodonut.drivebypower.util.BlockFace;
import edn.stratodonut.drivebypower.util.FaceOutlines;
import edn.stratodonut.drivebypower.wire.PowerNetworkManager;
import edn.stratodonut.drivebypower.wire.graph.PowerNetworkNode.PowerNetworkSink;
import java.util.Map;
import java.util.Set;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DriveByPowerMod.MOD_ID, value = Dist.CLIENT)
public final class ClientPowerNetworkHandler {
    private static final AABB UNIT_CUBE = AABB.unitCubeFromLowerCorner(Vec3.ZERO);
    private static final Map<Long, Map<String, Set<PowerNetworkSink>>> EMPTY_NETWORK = Map.of();
    private static final String DEFAULT_CHANNEL = PowerNetworkManager.WORLD_CHANNEL;

    private static Map<Long, Map<String, Set<PowerNetworkSink>>> currentNetwork = EMPTY_NETWORK;
    private static BlockPos selectedSource;
    private static int syncCooldown;

    private ClientPowerNetworkHandler() {
    }

    @SubscribeEvent
    public static void onWorldUnload(final LevelEvent.Unload event) {
        clearSource();
    }

    @SubscribeEvent
    public static void onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide().isServer()) {
            return;
        }

        final Player player = event.getEntity();
        if (player == null || player.isSpectator() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }

        final ItemStack heldItem = event.getItemStack();
        if (!heldItem.is(PowerItems.WIRE.get())) {
            return;
        }

        final Direction face = event.getFace() == null ? Direction.UP : event.getFace();
        handleWireUse(event.getLevel(), event.getPos(), face);
        event.setCancellationResult(net.minecraft.world.InteractionResult.CONSUME);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        final Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }

        final Map<Long, Map<String, Set<PowerNetworkSink>>> latestNetwork = PowerNetworkManager.get(level).getNetwork();
        if (!latestNetwork.equals(currentNetwork)) {
            currentNetwork = latestNetwork;
        }

        final ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.is(PowerItems.WIRE.get()) && !mainHand.is(PowerItems.WIRE_CUTTER.get())) {
            clearSource();
            return;
        }

        if (--syncCooldown <= 0) {
            syncManager();
        }

        if (selectedSource != null) {
            drawOutline(level, selectedSource, LineColor.SOURCE.SELECTED.getColor());
        }
        drawOutlines(level, selectedSource, currentNetwork);
    }

    public static void clearSource() {
        currentNetwork = EMPTY_NETWORK;
        selectedSource = null;
        syncCooldown = 0;
    }

    private static void handleWireUse(final Level level, final BlockPos pos, final Direction face) {
        if (selectedSource == null) {
            selectedSource = pos.immutable();
            syncManager();
            return;
        }

        if (selectedSource.equals(pos)) {
            clearSource();
            return;
        }

        final Map<String, Set<PowerNetworkSink>> currentSelection = currentNetwork.get(selectedSource.asLong());
        final PowerNetworkSink sink = PowerNetworkSink.of(pos, face);
        if (currentSelection != null && currentSelection.getOrDefault(DEFAULT_CHANNEL, Set.of()).contains(sink)) {
            PacketDistributor.sendToServer(new PowerRemoveConnectionPacket(selectedSource, pos, face, DEFAULT_CHANNEL));
            return;
        }

        PacketDistributor.sendToServer(new PowerAddConnectionPacket(selectedSource, pos, face, DEFAULT_CHANNEL));
    }

    private static void syncManager() {
        PacketDistributor.sendToServer(PowerNetworkRequestSyncPacket.INSTANCE);
        syncCooldown = 20;
    }

    private static void drawOutlines(
        final Level level,
        final BlockPos selectedSource,
        final Map<Long, Map<String, Set<PowerNetworkSink>>> network
    ) {
        for (final Map.Entry<Long, Map<String, Set<PowerNetworkSink>>> entry : network.entrySet()) {
            final BlockPos source = BlockPos.of(entry.getKey());
            final Map<String, Set<PowerNetworkSink>> perChannel = entry.getValue();

            if (selectedSource != null && source.equals(selectedSource)) {
                for (final Set<PowerNetworkSink> sinks : perChannel.values()) {
                    for (final PowerNetworkSink sink : sinks) {
                        drawConnection(
                            source,
                            BlockPos.of(sink.position()),
                            Direction.from3DDataValue(sink.direction()),
                            LineColor.SINK.SELECTED.getColor(),
                            LineColor.WIRE.SELECTED.getColor()
                        );
                    }
                }
            } else {
                drawOutline(level, source, LineColor.SOURCE.SAME_NETWORK.getColor());
            }
        }
    }

    private static void drawConnection(
        final BlockPos start,
        final BlockPos end,
        final Direction direction,
        final int faceColor,
        final int wireColor
    ) {
        drawOutlineFace(end, direction, faceColor);
        Outliner.getInstance()
            .showLine(
                net.createmod.catnip.data.Pair.of("powerConnection", net.createmod.catnip.data.Pair.of(end, direction)),
                Vec3.atCenterOf(start),
                Vec3.atCenterOf(end).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5D))
            )
            .colored(wireColor);
    }

    private static void drawOutlineFace(final BlockPos pos, final Direction direction, final int color) {
        Outliner.getInstance()
            .showAABB(net.createmod.catnip.data.Pair.of("powerFace", BlockFace.of(pos, direction)), FaceOutlines.getOutline(direction).move(pos))
            .colored(color)
            .lineWidth(0.0625F);
    }

    private static void drawOutline(final Level level, final BlockPos pos, final int color) {
        final BlockState state = level.getBlockState(pos);
        final AABB box = state.getShape(level, pos).isEmpty() ? UNIT_CUBE : state.getShape(level, pos).bounds();
        Outliner.getInstance()
            .showAABB(net.createmod.catnip.data.Pair.of("powerBlock", pos), box.move(pos))
            .colored(color)
            .lineWidth(0.0625F);
    }

    private interface LineColor {
        int getColor();

        enum SINK implements LineColor {
            SELECTED(Mode.DEPOSIT.getColor());

            private final int color;

            SINK(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }

        enum SOURCE implements LineColor {
            SELECTED(Mode.TAKE.getColor()),
            SAME_NETWORK(0x5773d8);

            private final int color;

            SOURCE(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }

        enum WIRE implements LineColor {
            SELECTED(Color.RED.getRGB());

            private final int color;

            WIRE(final int color) {
                this.color = color;
            }

            @Override
            public int getColor() {
                return color;
            }
        }
    }
}
