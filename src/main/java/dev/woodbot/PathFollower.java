package dev.woodbot;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** Walks the player along a list of feet positions by driving the movement keys. */
final class PathFollower {
    enum Status { RUNNING, DONE, FAILED }

    private final List<BlockPos> path;
    private int idx;
    private int ticks;
    private int stuck;
    private double lastX, lastZ;
    private boolean sampled;

    PathFollower(List<BlockPos> path) {
        this.path = path;
    }

    Status tick(Minecraft mc, LocalPlayer p) {
        ticks++;
        if (ticks > path.size() * 25 + 120) return Status.FAILED;

        // advance to the next node that has not been reached yet
        while (idx < path.size()) {
            BlockPos n = path.get(idx);
            double dx = n.getX() + 0.5 - p.getX();
            double dz = n.getZ() + 0.5 - p.getZ();
            boolean closeXZ = dx * dx + dz * dz < 0.12;
            boolean sameLevel = Math.abs(p.getY() - n.getY()) < 0.9;
            if ((closeXZ && sameLevel) || p.blockPosition().equals(n)) idx++;
            else break;
        }
        if (idx >= path.size()) return Status.DONE;

        BlockPos n = path.get(idx);
        double tx = n.getX() + 0.5, tz = n.getZ() + 0.5;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);

        if (dist > 6.0 || Math.abs(p.getY() - n.getY()) > 4.0) return Status.FAILED; // knocked off course

        // stuck detection (sample every 20 ticks)
        if (ticks % 20 == 0) {
            if (sampled && Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.15) stuck++;
            else stuck = 0;
            lastX = p.getX();
            lastZ = p.getZ();
            sampled = true;
            if (stuck >= 3) return Status.FAILED;
        }

        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot(0.0f);
        mc.options.keyUp.setDown(true);

        boolean needUp = n.getY() > p.getY() + 0.3;
        if (p.onGround() && ((needUp && dist < 1.5) || p.horizontalCollision)) {
            mc.options.keyJump.setDown(true);
        }
        return Status.RUNNING;
    }
}
