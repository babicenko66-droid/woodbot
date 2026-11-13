package dev.woodbot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous tree-chopping bot. Priorities each tick:
 * 1. self-defense  2. eating  3. axe management  4. chop trees / collect drops.
 */
public final class WoodBot {
    private static final double COMBAT_RANGE = 5.0;
    private static final double COMBAT_DISENGAGE = 7.0;
    private static final double MELEE_REACH = 2.9;
    private static final int HUNGER_START_BELOW = 16;
    private static final int HUNGER_FULL = 20;
    private static final int MIN_AXE_DURABILITY = 15;
    private static final double MINE_REACH = 4.0;
    private static final double WALK_REACH = 3.3;
    private static final int SCAN_RADIUS = 25;
    private static final int ITEM_RADIUS = 12;

    private enum State { SEARCH, CHOP, COLLECT, WANDER }

    private boolean active;
    private State state = State.SEARCH;
    private final Random rng = new Random();

    private final Set<Long> ignoredLogs = new HashSet<>();
    private final Map<Long, Integer> pathFails = new HashMap<>();
    private final Set<Integer> ignoredItems = new HashSet<>();

    private TreeScanner.Tree tree;
    private PathFollower follower;
    private BlockPos pathTargetLog;

    private LivingEntity combatTarget;
    private boolean eating;
    private boolean noFoodWarned;

    private BlockPos miningPos;
    private int miningTicks;

    private int collectDelay;
    private ItemEntity collectTarget;
    private int collectTicks;
    private int emptyWait;

    private boolean wanderAnnounced;

    // ---- control -------------------------------------------------------------------------

    public void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (active) {
            msg(mc, "Already running.", ChatFormatting.YELLOW);
            return;
        }
        if (findBestAxe(p) < 0) {
            msg(mc, "No usable axe (durability >= " + MIN_AXE_DURABILITY + ") in your hotbar. Not starting.",
                    ChatFormatting.RED);
            return;
        }
        reset();
        active = true;
        msg(mc, "Activated.", ChatFormatting.GREEN);
    }

    public void stopByUser(Minecraft mc) {
        if (!active) {
            msg(mc, "Not running.", ChatFormatting.YELLOW);
            return;
        }
        stop(mc, "Deactivated.", ChatFormatting.GOLD);
    }

    private void stop(Minecraft mc, String message, ChatFormatting color) {
        active = false;
        releaseKeys(mc);
        reset();
        msg(mc, message, color);
    }

    private void reset() {
        state = State.SEARCH;
        ignoredLogs.clear();
        pathFails.clear();
        ignoredItems.clear();
        tree = null;
        follower = null;
        pathTargetLog = null;
        combatTarget = null;
        eating = false;
        noFoodWarned = false;
        miningPos = null;
        miningTicks = 0;
        collectTarget = null;
        wanderAnnounced = false;
    }

    // ---- main tick -----------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) { // left the world
            active = false;
            reset();
            return;
        }
        if (!p.isAlive()) {
            stop(mc, "Player died, deactivated.", ChatFormatting.RED);
            return;
        }
        if (mc.screen != null) { // GUI / pause menu open: hands off
            releaseKeys(mc);
            return;
        }

        releaseKeys(mc);
        if (p.isInWater()) mc.options.keyJump.setDown(true);

        if (tickCombat(mc, p, level)) return;
        if (tickEating(mc, p)) return;
        if (!tickAxe(mc, p)) return;

        switch (state) {
            case SEARCH -> tickSearch(mc, p, level);
            case CHOP -> tickChop(mc, p, level);
            case COLLECT -> tickCollect(mc, p, level);
            case WANDER -> tickWander(mc, p, level);
        }
    }

    // ---- 1. self-defense -----------------------------------------------------------------

    private boolean tickCombat(Minecraft mc, LocalPlayer p, ClientLevel level) {
        LivingEntity target = pickHostile(p, level);
        if (target == null) {
            combatTarget = null;
            return false;
        }
        combatTarget = target;
        follower = null;
        miningPos = null;
        selectWeapon(p);

        lookAt(p, target.getBoundingBox().getCenter());
        double d2 = target.getBoundingBox().distanceToSqr(p.getEyePosition());
        if (d2 <= MELEE_REACH * MELEE_REACH) {
            if (p.getAttackStrengthScale(0.5f) >= 0.95f) {
                mc.gameMode.attack(p, target);
                p.swing(InteractionHand.MAIN_HAND);
            }
        } else {
            mc.options.keyUp.setDown(true);
            if (p.onGround() && p.horizontalCollision) mc.options.keyJump.setDown(true);
        }
        return true;
    }

    private LivingEntity pickHostile(LocalPlayer p, ClientLevel level) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class,
                p.getBoundingBox().inflate(COMBAT_DISENGAGE));
        for (LivingEntity e : near) {
            if (e == p || !e.isAlive() || e.isSpectator()) continue;
            if (!(e instanceof Enemy) || e instanceof NeutralMob) continue; // skip endermen / piglins etc.
            double d = p.distanceTo(e);
            double limit = (e == combatTarget) ? COMBAT_DISENGAGE : COMBAT_RANGE;
            if (d > limit || !p.hasLineOfSight(e)) continue;
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private void selectWeapon(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            double s = weaponScore(inv.getItem(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) best = findBestAxe(p); // fall back to the axe
        if (best >= 0 && best != inv.getSelectedSlot()) inv.setSelectedSlot(best);
    }

    private static double weaponScore(ItemStack s) {
        if (s.isEmpty()) return -1;
        double base;
        if (s.is(ItemTags.SWORDS)) base = 100;
        else if (s.is(ItemTags.AXES) && remaining(s) >= MIN_AXE_DURABILITY) base = 50;
        else return -1;
        return base + Math.min(s.getMaxDamage(), 3000) / 50.0;
    }

    // ---- 2. eating -----------------------------------------------------------------------

    private boolean tickEating(Minecraft mc, LocalPlayer p) {
        int food = p.getFoodData().getFoodLevel();
        if (!eating) {
            if (food >= HUNGER_START_BELOW) {
                noFoodWarned = false;
                return false;
            }
            eating = true;
        }
        if (food >= HUNGER_FULL) {
            eating = false;
            return false;
        }
        int slot = findFoodSlot(p);
        if (slot < 0) {
            eating = false;
            if (!noFoodWarned) {
                noFoodWarned = true;
                msg(mc, "Hungry but no food in the hotbar.", ChatFormatting.YELLOW);
            }
            return false;
        }
        Inventory inv = p.getInventory();
        if (inv.getSelectedSlot() != slot) inv.setSelectedSlot(slot);
        if (!p.isUsingItem()) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        return true;
    }

    private static int findFoodSlot(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = -1000;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.has(DataComponents.FOOD) || isBadFood(s)) continue;
            FoodProperties fp = s.get(DataComponents.FOOD);
            if (fp == null) continue;
            double score = fp.nutrition();
            if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) score -= 100; // last resort
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean isBadFood(ItemStack s) {
        return s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.PUFFERFISH)
                || s.is(Items.POISONOUS_POTATO) || s.is(Items.CHORUS_FRUIT) || s.is(Items.CHICKEN)
                || s.is(Items.SUSPICIOUS_STEW);
    }

    // ---- 3. axe management ---------------------------------------------------------------

    private boolean tickAxe(Minecraft mc, LocalPlayer p) {
        Inventory inv = p.getInventory();
        if (isUsableAxe(inv.getItem(inv.getSelectedSlot()))) return true;
        int best = findBestAxe(p);
        if (best < 0) {
            stop(mc, "No usable axe left (durability < " + MIN_AXE_DURABILITY + "). Bot stopped!",
                    ChatFormatting.RED);
            return false;
        }
        inv.setSelectedSlot(best);
        return true;
    }

    private static int remaining(ItemStack s) {
        return s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : Integer.MAX_VALUE;
    }

    private static boolean isUsableAxe(ItemStack s) {
        return !s.isEmpty() && s.is(ItemTags.AXES) && remaining(s) >= MIN_AXE_DURABILITY;
    }

    private static int findBestAxe(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1;
        int bestRem = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!isUsableAxe(s)) continue;
            int rem = remaining(s);
            if (rem > bestRem) {
                bestRem = rem;
                best = i;
            }
        }
        return best;
    }

    // ---- 4. tree chopping ----------------------------------------------------------------

    private void tickSearch(Minecraft mc, LocalPlayer p, ClientLevel level) {
        tree = TreeScanner.findNearest(level, p, SCAN_RADIUS, ignoredLogs);
        follower = null;
        miningPos = null;
        if (tree == null) {
            state = State.WANDER;
            if (!wanderAnnounced) {
                wanderAnnounced = true;
                msg(mc, "No tree within " + SCAN_RADIUS + " blocks, exploring...", ChatFormatting.GRAY);
            }
            return;
        }
        wanderAnnounced = false;
        state = State.CHOP;
    }

    private void tickChop(Minecraft mc, LocalPlayer p, ClientLevel level) {
        if (tree == null) {
            state = State.SEARCH;
            return;
        }
        tree.logs.removeIf(pos -> !level.getBlockState(pos).is(BlockTags.LOGS)
                || ignoredLogs.contains(pos.asLong()));
        if (tree.logs.isEmpty()) {
            startCollect();
            return;
        }

        if (follower != null) {
            PathFollower.Status st = follower.tick(mc, p);
            if (st == PathFollower.Status.RUNNING) return;
            follower = null;
            if (st == PathFollower.Status.FAILED && pathTargetLog != null) {
                markPathFailure(pathTargetLog);
            }
            return;
        }

        // mine the nearest log that is already within reach
        Vec3 eye = p.getEyePosition();
        BlockPos reachable = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : tree.logs) {
            double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (d <= MINE_REACH * MINE_REACH && d < bestD) {
                bestD = d;
                reachable = pos;
            }
        }
        if (reachable != null) {
            mine(mc, p, reachable);
            return;
        }

        // otherwise walk towards the nearest log
        miningPos = null;
        if (!p.onGround() && !p.isInWater()) return; // wait until landed
        BlockPos target = null;
        bestD = Double.MAX_VALUE;
        for (BlockPos pos : tree.logs) {
            double d = pos.distSqr(p.blockPosition());
            if (d < bestD) {
                bestD = d;
                target = pos;
            }
        }
        List<BlockPos> path = Pathfinder.find(level, p.blockPosition(),
                new Pathfinder.ReachGoal(Vec3.atCenterOf(target), WALK_REACH), 6000);
        if (path == null || path.isEmpty()) {
            ignoreLog(target);
            return;
        }
        follower = new PathFollower(path);
        pathTargetLog = target;
    }

    private void markPathFailure(BlockPos log) {
        int n = pathFails.merge(log.asLong(), 1, Integer::sum);
        if (n >= 2) ignoreLog(log);
    }

    private void ignoreLog(BlockPos log) {
        ignoredLogs.add(log.asLong());
        if (tree != null) tree.logs.remove(log);
    }

    private void mine(Minecraft mc, LocalPlayer p, BlockPos pos) {
        if (!pos.equals(miningPos)) {
            miningPos = pos;
            miningTicks = 0;
        }
        if (++miningTicks > 300) { // 15 s without breaking: give up on this block
            ignoreLog(pos);
            miningPos = null;
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(pos);
        lookAt(p, c);
        Direction face = Direction.getApproximateNearest(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        if (mc.gameMode.continueDestroyBlock(pos, face)) {
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    // ---- picking up drops ----------------------------------------------------------------

    private void startCollect() {
        state = State.COLLECT;
        collectDelay = 12; // give the drops time to spawn
        collectTarget = null;
        follower = null;
        miningPos = null;
        emptyWait = 0;
    }

    private void tickCollect(Minecraft mc, LocalPlayer p, ClientLevel level) {
        if (collectDelay > 0) {
            collectDelay--;
            return;
        }
        if (follower != null) {
            if (collectTarget == null || collectTarget.isRemoved()) {
                follower = null;
                collectTarget = null;
            } else {
                if (++collectTicks > 400) {
                    ignoredItems.add(collectTarget.getId());
                    follower = null;
                    collectTarget = null;
                    return;
                }
                PathFollower.Status st = follower.tick(mc, p);
                if (st == PathFollower.Status.RUNNING) return;
                follower = null;
                if (st == PathFollower.Status.FAILED) {
                    ignoredItems.add(collectTarget.getId());
                    collectTarget = null;
                }
                return;
            }
        }

        BlockPos origin = tree != null ? tree.origin : p.blockPosition();
        AABB area = new AABB(origin).inflate(ITEM_RADIUS);
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, area)) {
            if (ie.isRemoved() || ignoredItems.contains(ie.getId())) continue;
            double d = p.distanceToSqr(ie);
            if (d < bestD) {
                bestD = d;
                best = ie;
            }
        }
        if (best == null) { // everything collected: next tree
            tree = null;
            state = State.SEARCH;
            return;
        }
        if (!p.onGround() && !p.isInWater()) return;

        List<BlockPos> path = Pathfinder.find(level, p.blockPosition(),
                new Pathfinder.ItemGoal(best.position()), 4000);
        if (path == null) {
            ignoredItems.add(best.getId());
            return;
        }
        if (path.isEmpty()) { // already on top of it, wait for pickup (or give up if inventory is full)
            if (++emptyWait > 40) {
                ignoredItems.add(best.getId());
                emptyWait = 0;
            }
            return;
        }
        emptyWait = 0;
        collectTarget = best;
        collectTicks = 0;
        follower = new PathFollower(path);
    }

    // ---- exploring -----------------------------------------------------------------------

    private void tickWander(Minecraft mc, LocalPlayer p, ClientLevel level) {
        if (follower != null) {
            PathFollower.Status st = follower.tick(mc, p);
            if (st != PathFollower.Status.RUNNING) {
                follower = null;
                state = State.SEARCH;
            }
            return;
        }
        if (!p.onGround() && !p.isInWater()) return;
        double ang = rng.nextDouble() * Math.PI * 2;
        Vec3 t = p.position().add(Math.cos(ang) * 16, 0, Math.sin(ang) * 16);
        List<BlockPos> path = Pathfinder.find(level, p.blockPosition(), new Pathfinder.AreaGoal(t, 4.0), 3000);
        if (path == null || path.isEmpty()) return; // try another direction next tick
        follower = new PathFollower(path);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        p.setYRot(yaw);
        p.setXRot(Mth.clamp(pitch, -90.0f, 90.0f));
    }

    private static void releaseKeys(Minecraft mc) {
        Options o = mc.options;
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        o.keySprint.setDown(false);
        o.keyUse.setDown(false);
        o.keyAttack.setDown(false);
    }

    private static void msg(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[WoodBot] " + text).withStyle(color), false);
        }
    }
}
