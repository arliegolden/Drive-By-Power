package edn.argolde.drivebypower.client;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint.Mode;
import com.simibubi.create.foundation.utility.CreateLang;

import edn.argolde.drivebypower.DriveByPowerMod;
import edn.argolde.drivebypower.PowerItems;
import edn.argolde.drivebypower.network.PowerAddConnectionPacket;
import edn.argolde.drivebypower.network.PowerNetworkRequestSyncPacket;
import edn.argolde.drivebypower.network.PowerRemoveConnectionPacket;
import edn.argolde.drivebypower.util.BlockFace;
import edn.argolde.drivebypower.util.FaceOutlines;
import edn.argolde.drivebypower.wire.PowerNetworkManager;
import edn.argolde.drivebypower.wire.PowerNetworkManager.ConnectionInfo;
import edn.argolde.drivebypower.wire.graph.PowerNetworkNode.PowerNetworkSink;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
            selectedSource = null;
            if (GogglesItem.isWearingGoggles(player) && --syncCooldown <= 0) {
                syncManager();
            }
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

    public static boolean addGoggleInformation(final Level level, final BlockPos pos, final List<Component> tooltip) {
        return addGoggleInformation(level, pos, tooltip, false);
    }

    public static boolean addGoggleInformation(
        final Level level,
        final BlockPos pos,
        final List<Component> tooltip,
        final boolean addSeparator
    ) {
        final List<ConnectionInfo> connections = PowerNetworkManager.get(level).getConnectionsFor(pos);
        if (connections.isEmpty()) {
            return false;
        }

        if (addSeparator && !tooltip.isEmpty()) {
            tooltip.add(CommonComponents.EMPTY);
        }

        addGoggleLine(tooltip, Component.translatable("gui.drivebypower.goggles.title").withStyle(ChatFormatting.GOLD), 0);
        for (final ConnectionInfo connection : connections) {
            addConnectionTooltip(tooltip, connection);
            drawConnection(
                level,
                connection.source(),
                connection.sink(),
                connection.sinkDirection(),
                connection.sourceEndpoint() ? LineColor.SINK.SELECTED.getColor() : LineColor.SOURCE.SAME_NETWORK.getColor(),
                animatedWireColor(connection.energyPerTick(), 0.0F),
                connection.energyPerTick()
            );
        }
        return true;
    }

    private static void addConnectionTooltip(final List<Component> tooltip, final ConnectionInfo connection) {
        final boolean sourceEndpoint = connection.sourceEndpoint();
        final BlockPos otherEndpoint = sourceEndpoint ? connection.sink() : connection.source();
        final ChatFormatting directionColor = sourceEndpoint ? ChatFormatting.GREEN : ChatFormatting.AQUA;

        addGoggleLine(tooltip, Component.translatable(sourceEndpoint ? "gui.drivebypower.goggles.sending" : "gui.drivebypower.goggles.receiving")
            .withStyle(directionColor)
            .append(Component.literal(" "))
            .append(formatEnergy(connection.energyPerTick())), 1);

        addGoggleLine(tooltip, Component.translatable(sourceEndpoint ? "gui.drivebypower.goggles.to" : "gui.drivebypower.goggles.from")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(" "))
            .append(Component.translatable(sourceEndpoint ? "gui.drivebypower.goggles.sink" : "gui.drivebypower.goggles.source")
                .withStyle(ChatFormatting.WHITE))
            .append(Component.literal("  "))
            .append(Component.translatable("gui.drivebypower.goggles.channel")
                .withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" "))
            .append(Component.literal(connection.channel()).withStyle(ChatFormatting.DARK_AQUA)), 2);

        addGoggleLine(tooltip, Component.literal(formatPos(otherEndpoint)).withStyle(ChatFormatting.DARK_GRAY), 2);
    }

    private static Component formatEnergy(final int energyPerTick) {
        final ChatFormatting color = energyPerTick > 0 ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY;
        return Component.literal(String.valueOf(energyPerTick))
            .withStyle(color)
            .append(Component.literal(" FE/t").withStyle(ChatFormatting.GRAY));
    }

    private static void addGoggleLine(final List<Component> tooltip, final Component component, final int indent) {
        CreateLang.builder()
            .add(component.copy())
            .forGoggles(tooltip, indent);
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
                            level,
                            source,
                            BlockPos.of(sink.position()),
                            Direction.from3DDataValue(sink.direction()),
                            LineColor.SINK.SELECTED.getColor(),
                            LineColor.WIRE.SELECTED.getColor(),
                            0
                        );
                    }
                }
            } else {
                drawOutline(level, source, LineColor.SOURCE.SAME_NETWORK.getColor());
            }
        }
    }

    private static void drawConnection(
        final Level level,
        final BlockPos start,
        final BlockPos end,
        final Direction direction,
        final int faceColor,
        final int wireColor,
        final int energyPerTick
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

    private static String formatPos(final BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static int animatedWireColor(final int energyPerTick, final float partialTick) {
        if (energyPerTick <= 0) {
            return 0x703030;
        }

        final float pulse = (float) ((Math.sin((Minecraft.getInstance().level.getGameTime() + partialTick) * 0.45D) + 1.0D) * 0.5D);
        final int red = 180 + Math.round(75 * pulse);
        final int green = 35 + Math.round(45 * pulse);
        final int blue = 25 + Math.round(25 * pulse);
        return (red << 16) | (green << 8) | blue;
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
