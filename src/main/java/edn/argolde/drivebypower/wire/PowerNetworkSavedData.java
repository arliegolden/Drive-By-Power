package edn.stratodonut.drivebypower.wire;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class PowerNetworkSavedData extends SavedData {
    private static final String DATA_NAME = "drivebypower_network";

    private final PowerNetworkManager manager;

    private PowerNetworkSavedData() {
        this.manager = new PowerNetworkManager(this::setDirty);
    }

    public static SavedData.Factory<PowerNetworkSavedData> factory() {
        return new SavedData.Factory<>(PowerNetworkSavedData::new, PowerNetworkSavedData::load);
    }

    public static PowerNetworkManager get(final ServerLevel level) {
        final PowerNetworkSavedData data = level.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
        return data.manager;
    }

    private static PowerNetworkSavedData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final PowerNetworkSavedData data = new PowerNetworkSavedData();
        data.manager.load(tag);
        return data;
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        return manager.save(tag);
    }
}
