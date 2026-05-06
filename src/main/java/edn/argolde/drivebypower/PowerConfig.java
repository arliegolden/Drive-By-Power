package edn.stratodonut.drivebypower;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class PowerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue MAX_FE_PER_TICK = BUILDER
        .comment("Maximum FE moved per cable connection each server tick.")
        .defineInRange("maxFePerTick", 1024, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private PowerConfig() {
    }
}
