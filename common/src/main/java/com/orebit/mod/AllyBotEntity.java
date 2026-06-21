package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class AllyBotEntity extends FakePlayerEntity {

    private final Player owner;

    public AllyBotEntity(MinecraftServer server, ServerLevel world, GameProfile profile, ClientInformation options, Player owner) {
        super(server, world, profile, options);
        this.owner = owner;
    }

    public void lookAtPlayer(Player player) {
        double dx = player.getX() - this.getX();
        double dy = (player.getEyeY()) - this.getEyeY();
        double dz = player.getZ() - this.getZ();

        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float)(Math.toDegrees(-Math.atan2(dy, distXZ)));

        this.setYHeadRot(yaw);    // where the head turns
        this.setYRot(yaw);        // body rotation
        this.setYBodyRot(yaw);    // optional for full facing
        this.setXRot(pitch);      // up/down looking
    }

    @Override
    public void tick() {
        super.tick();

        lookAtPlayer(owner);

        if (owner == this) return;

        // Face the player
        double dx = owner.getX() - this.getX();
        double dz = owner.getZ() - this.getZ();
        float yaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);

        this.xxa = 0.0f;

        // Forward motion toward target
        float distance = (float)Math.sqrt(Math.pow(dx, 2) + Math.pow(dz, 2));
        if (distance > 3) {
            this.zza = 1.0f;
        } else {
            this.zza = 0.0f;
        }

        if (this.isInWater()) {
            this.yya = 1.0f;
        } else {
            this.yya = 0.0f;
        }

        // Jump if stuck
        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = distance > 3 && velocity.horizontalDistanceSqr() < 0.001;

        if (stuck && this.onGround()) {
            this.jumpFromGround();
        }

        this.aiStep();
    }
}
