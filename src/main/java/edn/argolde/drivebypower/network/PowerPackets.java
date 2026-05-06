package edn.argolde.drivebypower.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class PowerPackets {
    private PowerPackets() {
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .playToClient(PowerNetworkFullSyncPacket.TYPE, PowerNetworkFullSyncPacket.STREAM_CODEC, PowerNetworkFullSyncPacket::handle)
            .playToServer(PowerAddConnectionPacket.TYPE, PowerAddConnectionPacket.STREAM_CODEC, PowerAddConnectionPacket::handle)
            .playToServer(PowerRemoveConnectionPacket.TYPE, PowerRemoveConnectionPacket.STREAM_CODEC, PowerRemoveConnectionPacket::handle)
            .playToServer(PowerNetworkRequestSyncPacket.TYPE, PowerNetworkRequestSyncPacket.STREAM_CODEC, PowerNetworkRequestSyncPacket::handle);
    }
}
