package edn.stratodonut.drivebypower.network;

import edn.stratodonut.drivebypower.DriveByPowerMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PowerNetworkRequestSyncPacket() implements CustomPacketPayload {
    public static final PowerNetworkRequestSyncPacket INSTANCE = new PowerNetworkRequestSyncPacket();
    public static final Type<PowerNetworkRequestSyncPacket> TYPE = new Type<>(DriveByPowerMod.asResource("power_network_request_sync"));
    public static final StreamCodec<ByteBuf, PowerNetworkRequestSyncPacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<PowerNetworkRequestSyncPacket> type() {
        return TYPE;
    }

    public static void handle(final PowerNetworkRequestSyncPacket payload, final IPayloadContext context) {
        if (!(context.player() instanceof final ServerPlayer player)) {
            return;
        }

        PowerNetworkFullSyncPacket.sendTo(player);
    }
}
