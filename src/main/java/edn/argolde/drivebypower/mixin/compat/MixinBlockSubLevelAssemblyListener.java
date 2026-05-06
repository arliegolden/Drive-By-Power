package edn.argolde.drivebypower.mixin.compat;

import dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener;
import edn.argolde.drivebypower.wire.PowerNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Block.class)
public abstract class MixinBlockSubLevelAssemblyListener implements BlockSubLevelAssemblyListener {
    @Override
    public void afterMove(
        final ServerLevel originLevel,
        final ServerLevel resultingLevel,
        final BlockState newState,
        final BlockPos oldPos,
        final BlockPos newPos
    ) {
        PowerNetworkManager.handleAssemblyMove(originLevel, resultingLevel, oldPos, newPos);
    }
}
