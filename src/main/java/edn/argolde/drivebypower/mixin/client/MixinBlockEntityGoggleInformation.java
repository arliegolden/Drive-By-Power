package edn.argolde.drivebypower.mixin.client;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import edn.argolde.drivebypower.client.ClientPowerNetworkHandler;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntity.class)
public abstract class MixinBlockEntityGoggleInformation implements IHaveGoggleInformation {
    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        final BlockEntity blockEntity = (BlockEntity) (Object) this;
        if (blockEntity.getLevel() == null) {
            return false;
        }

        return ClientPowerNetworkHandler.addGoggleInformation(blockEntity.getLevel(), blockEntity.getBlockPos(), tooltip);
    }
}
