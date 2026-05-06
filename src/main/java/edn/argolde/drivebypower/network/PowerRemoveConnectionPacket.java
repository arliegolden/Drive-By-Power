package edn.argolde.drivebypower.network;

import edn.argolde.drivebypower.DriveByPowerMod;
import edn.argolde.drivebypower.PowerSounds;
import edn.argolde.drivebypower.wire.PowerNetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PowerRemoveConnectionPacket(BlockPos source, BlockPos sink, Direction direction, String channel) implements CustomPacketPayload {
    public static final Type<PowerRemoveConnectionPacket> TYPE = new Type<>(DriveByPowerMod.asResource("power_remove_connection"));
    public static final StreamCodec<ByteBuf, PowerRemoveConnectionPacket> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, PowerRemoveConnectionPacket::source,
        BlockPos.STREAM_CODEC, PowerRemoveConnectionPacket::sink,
        ByteBufCodecs.VAR_INT, packet -> packet.direction().get3DDataValue(),
        ByteBufCodecs.STRING_UTF8, PowerRemoveConnectionPacket::channel,
        (source, sink, direction, channel) -> new PowerRemoveConnectionPacket(source, sink, Direction.from3DDataValue(direction), channel)
    );

    @Override
    public Type<PowerRemoveConnectionPacket> type() {
        return TYPE;
    }

    public static void handle(final PowerRemoveConnectionPacket payload, final IPayloadContext context) {
        if (!(context.player() instanceof final ServerPlayer player)) {
            return;
        }

        if (PowerNetworkManager.removeConnection(player.level(), payload.source(), payload.sink(), payload.direction(), payload.channel())) {
            player.level().playSound(null, payload.sink(), PowerSounds.PLUG_OUT.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        PowerNetworkFullSyncPacket.sendTo(player);
    }
}
