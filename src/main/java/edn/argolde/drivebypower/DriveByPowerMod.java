package edn.stratodonut.drivebypower;

import com.mojang.logging.LogUtils;
import edn.stratodonut.drivebypower.network.PowerPackets;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(DriveByPowerMod.MOD_ID)
public class DriveByPowerMod {
    public static final String MOD_ID = "drivebypower";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DriveByPowerMod(final IEventBus modEventBus, final ModContainer modContainer) {
        PowerItems.register(modEventBus);
        PowerCreativeTabs.register(modEventBus);
        PowerSounds.register(modEventBus);
        modEventBus.addListener(PowerPackets::register);
        modContainer.registerConfig(ModConfig.Type.COMMON, PowerConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(PowerCommonEvents::onLevelTick);
    }

    public static ResourceLocation asResource(final String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
