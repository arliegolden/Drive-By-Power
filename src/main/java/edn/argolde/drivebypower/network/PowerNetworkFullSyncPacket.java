package edn.argolde.drivebypower.network;

import edn.argolde.drivebypower.DriveByPowerMod;
import edn.argolde.drivebypower.wire.PowerNetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PowerNetworkFullSyncPacket(CompoundTag network) implements CustomPacketPayload {
    public static final Type<PowerNetworkFullSyncPacket> TYPE = new Type<>(DriveByPowerMod.asResource("power_network_full_sync"));
    public static final StreamCodec<ByteBuf, PowerNetworkFullSyncPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.COMPOUND_TAG, PowerNetworkFullSyncPacket::network,
        PowerNetworkFullSyncPacket::new
    );

    @Override
    public Type<PowerNetworkFullSyncPacket> type() {
        return TYPE;
    }

    public static void sendTo(final ServerPlayer player) {
        final CompoundTag tag = PowerNetworkManager.get(player.serverLevel()).saveClientSnapshot(new CompoundTag());
        PacketDistributor.sendToPlayer(player, new PowerNetworkFullSyncPacket(tag));
    }

    public static void handle(final PowerNetworkFullSyncPacket payload, final IPayloadContext context) {
        context.enqueueWork(() -> PowerNetworkManager.get(context.player().level()).load(payload.network()));
    }
}
