package com.yasin.veil;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(VeilMod.MOD_ID)
public final class VeilMod {
    public static final String MOD_ID = "veil";
    private static final Map<UUID, Integer> PLAYER_CLOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> EVENT_COOLDOWNS = new ConcurrentHashMap<>();

    public VeilMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.level().isClientSide || player.isSpectator() || player.isCreative()) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        UUID id = player.getUUID();
        int clock = PLAYER_CLOCKS.getOrDefault(id, 0) + 1;
        PLAYER_CLOCKS.put(id, clock);

        int cooldown = EVENT_COOLDOWNS.getOrDefault(id, 0);
        if (cooldown > 0) {
            EVENT_COOLDOWNS.put(id, cooldown - 1);
            return;
        }

        // Events begin only after the player has spent some time in the world.
        if (clock < 2400 || level.getDifficulty().getId() == 0) return;
        if (level.random.nextInt(7200) != 0) return;

        triggerPresence(level, player);
        EVENT_COOLDOWNS.put(id, 9000);
    }

    private static void triggerPresence(ServerLevel level, ServerPlayer player) {
        Vec3 look = player.getLookAngle().normalize();
        Vec3 behind = player.position().subtract(look.scale(18.0));
        BlockPos spawnPos = BlockPos.containing(behind.x, player.getY(), behind.z);
        spawnPos = findSafeSpawn(level, spawnPos);

        player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 70, 0, false, false, true));
        player.playNotifySound(SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, 0.9F, 0.72F);

        if (spawnPos == null) return;
        EnderMan figure = EntityType.ENDERMAN.create(level);
        if (figure == null) return;
        figure.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                level.random.nextFloat() * 360.0F, 0.0F);
        figure.setPersistenceRequired();
        figure.setTarget(player);
        level.addFreshEntity(figure);
    }

    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos origin) {
        for (int y = 0; y <= 5; y++) {
            BlockPos candidate = origin.above(y);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()
                    && !level.getBlockState(candidate.below()).isAir()) {
                return candidate;
            }
        }
        return null;
    }
}
