package edn.argolde.drivebypower;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PowerCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(
        Registries.CREATIVE_MODE_TAB,
        DriveByPowerMod.MOD_ID
    );

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BASE_CREATIVE_TAB = CREATIVE_MODE_TABS.register(
        "base",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.drivebypower"))
            .icon(() -> PowerItems.WIRE.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(PowerItems.WIRE.get());
                output.accept(PowerItems.WIRE_CUTTER.get());
            })
            .build()
    );

    private PowerCreativeTabs() {
    }

    public static void register(final IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
