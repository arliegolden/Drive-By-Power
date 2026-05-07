package edn.argolde.drivebypower.mixin.client;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer;
import edn.argolde.drivebypower.client.ClientPowerNetworkHandler;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GoggleOverlayRenderer.class)
public abstract class MixinGoggleOverlayRenderer {
    @Redirect(
        method = "renderOverlay",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/api/equipment/goggles/IHaveGoggleInformation;addToGoggleTooltip(Ljava/util/List;Z)Z"
        )
    )
    private static boolean drivebypower$appendPowerNetworkGoggles(
        final IHaveGoggleInformation goggleInformation,
        final List<Component> tooltip,
        final boolean isPlayerSneaking
    ) {
        final boolean nativeAdded = goggleInformation.addToGoggleTooltip(tooltip, isPlayerSneaking);
        if (!(goggleInformation instanceof final BlockEntity blockEntity) || blockEntity.getLevel() == null) {
            return nativeAdded;
        }

        final boolean powerAdded = ClientPowerNetworkHandler.addGoggleInformation(
            blockEntity.getLevel(),
            blockEntity.getBlockPos(),
            tooltip,
            nativeAdded
        );
        return nativeAdded || powerAdded;
    }
}
