package edn.stratodonut.drivebypower.network;

import edn.stratodonut.drivebypower.DriveByPowerMod;
import edn.stratodonut.drivebypower.PowerSounds;
import edn.stratodonut.drivebypower.wire.PowerNetworkManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PowerAddConnectionPacket(BlockPos source, BlockPos sink, Direction direction, String channel) implements CustomPacketPayload {
    public static final Type<PowerAddConnectionPacket> TYPE = new Type<>(DriveByPowerMod.asResource("power_add_connection"));
    public static final StreamCodec<ByteBuf, PowerAddConnectionPacket> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, PowerAddConnectionPacket::source,
        BlockPos.STREAM_CODEC, PowerAddConnectionPacket::sink,
        ByteBufCodecs.VAR_INT, packet -> packet.direction().get3DDataValue(),
        ByteBufCodecs.STRING_UTF8, PowerAddConnectionPacket::channel,
        (source, sink, direction, channel) -> new PowerAddConnectionPacket(source, sink, Direction.from3DDataValue(direction), channel)
    );

    @Override
    public Type<PowerAddConnectionPacket> type() {
        return TYPE;
    }

    public static void handle(final PowerAddConnectionPacket payload, final IPayloadContext context) {
        if (!(context.player() instanceof final ServerPlayer player)) {
            return;
        }

        final PowerNetworkManager.ConnectionResult result = PowerNetworkManager.createConnection(
            player.level(),
            payload.source(),
            payload.sink(),
            payload.direction(),
            payload.channel()
        );
        if (result.isSuccess()) {
            player.level().playSound(null, payload.sink(), PowerSounds.PLUG_IN.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
            PowerNetworkFullSyncPacket.sendTo(player);
            return;
        }

        player.displayClientMessage(Component.literal(result.getDescription()).withStyle(ChatFormatting.RED), true);
    }
}
