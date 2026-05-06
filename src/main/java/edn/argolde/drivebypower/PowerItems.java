package edn.stratodonut.drivebypower;

import edn.stratodonut.drivebypower.items.WireCutterItem;
import edn.stratodonut.drivebypower.items.WireItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PowerItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DriveByPowerMod.MOD_ID);

    public static final DeferredItem<WireItem> WIRE = ITEMS.register("wire", () -> new WireItem(new Item.Properties()));
    public static final DeferredItem<WireCutterItem> WIRE_CUTTER = ITEMS.register(
        "wire_cutter",
        () -> new WireCutterItem(new Item.Properties().stacksTo(1))
    );

    private PowerItems() {
    }

    public static void register(final IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
