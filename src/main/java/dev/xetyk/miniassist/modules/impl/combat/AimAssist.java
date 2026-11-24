package dev.xetyk.miniassist.modules.impl.combat;

import dev.xetyk.miniassist.modules.api.BooleanSetting;
import dev.xetyk.miniassist.modules.api.Category;
import dev.xetyk.miniassist.modules.api.Module;
import dev.xetyk.miniassist.modules.api.MultiBooleanSetting;
import dev.xetyk.miniassist.modules.api.NumberSetting;
import dev.xetyk.miniassist.util.TargetSelector;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Client-side view assistance. Uses bounded per-tick rotations and a short,
 * filtered horizontal motion lead; it never sends attack or rotation packets.
 */
public final class AimAssist extends Module {
    private final NumberSetting range = add(new NumberSetting(
            "Дистанция", "Максимальная дистанция выбора цели", 4.5, 2.0, 6.0, 0.5));
    private final NumberSetting fov = add(new NumberSetting(
            "FOV", "Полная ширина конуса поиска цели", 70.0, 20.0, 160.0, 5.0));
    private final NumberSetting smoothing = add(new NumberSetting(
            "Плавность", "Меньше значение — мягче и медленнее доводка", 0.12, 0.04, 0.45, 0.01));
    private final NumberSetting maxTurn = add(new NumberSetting(
            "Макс. поворот/тик", "Ограничение изменения камеры за один игровой тик", 3.0, 0.75, 10.0, 0.25));
    private final NumberSetting prediction = add(new NumberSetting(
            "Упреждение (тики)", "Компенсация горизонтального движения цели", 0.7, 0.0, 2.5, 0.1));
    private final NumberSetting aimHeight = add(new NumberSetting(
            "Точка прицела (%)", "Высота точки прицеливания внутри хитбокса; 0.55 — грудь", 0.55, 0.35, 0.75, 0.01));
    private final NumberSetting deadzone = add(new NumberSetting(
            "Мёртвая зона", "Не корректировать микроскопическую ошибку угла", 0.10, 0.0, 0.5, 0.02));
    private final BooleanSetting onlyWhileAttacking = add(new BooleanSetting(
            "Только при атаке", "Наводить только при удержании ЛКМ", false));
    private final BooleanSetting ignoreInvisible = add(new BooleanSetting(
            "Игнорировать невидимых", "Не выбирать невидимые цели", true));
    private final BooleanSetting onlyVisible = add(new BooleanSetting(
            "Только видимые", "Не выбирать цели за блоками", true));
    private final BooleanSetting keepTarget = add(new BooleanSetting(
            "Удерживать цель", "Сохранять текущую цель и избегать резких переключений", true));
    private final MultiBooleanSetting targets = add(MultiBooleanSetting.create(
            "Типы целей", "Какие сущности можно выбирать", List.of("Игроков", "Враждебных", "Мирных")));

    private int lockedTargetId = -1;
    private UUID lockedTargetUuid;
    private Object lockedWorld;
    private Object lockedPlayer;

    // A filtered velocity estimate prevents a single knockback/interpolation spike
    // from moving the predicted point outside the target's useful hit area.
    private int motionTargetId = -1;
    private Vec3d lastTargetPosition;
    private Vec3d filteredVelocity = Vec3d.ZERO;

    public AimAssist() {
        super("Aim Assist", Category.COMBAT, "Мягко сопровождает выбранную цель без резких рывков камеры");
        setKeyCode(GLFW.GLFW_KEY_F6);
        targets.set("Враждебных", false);
        targets.set("Мирных", false);
    }

    @Override
    protected void onDisable() {
        clearLockedTarget();
    }

    /** Current target used by Target HUD; null when no valid target is held. */
    public LivingEntity getLockedTarget() {
        if (!isEnabled() || mc.world == null || lockedTargetId < 0
                || lockedWorld != mc.world || lockedPlayer != mc.player) return null;
        Entity entity = mc.world.getEntityById(lockedTargetId);
        return entity instanceof LivingEntity living && lockedTargetUuid != null
                && lockedTargetUuid.equals(living.getUuid()) && isValidTarget(living) ? living : null;
    }

    private void clearLockedTarget() {
        lockedTargetId = -1;
        lockedTargetUuid = null;
        lockedWorld = null;
        lockedPlayer = null;
        motionTargetId = -1;
        lastTargetPosition = null;
        filteredVelocity = Vec3d.ZERO;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.getCameraEntity() != mc.player || mc.player.isSpectator() || !mc.player.isAlive()) {
            clearLockedTarget();
            return;
        }
        if (lockedWorld != mc.world || lockedPlayer != mc.player) clearLockedTarget();
        if (onlyWhileAttacking.isEnabled() && !mc.options.attackKey.isPressed()) {
            clearLockedTarget();
            return;
        }

        LivingEntity target = findTarget();
        if (target == null) {
            clearLockedTarget();
            return;
        }

        lockedTargetId = target.getId();
        lockedTargetUuid = target.getUuid();
        lockedWorld = mc.world;
        lockedPlayer = mc.player;
        updateMotionEstimate(target);

        Vec3d aimPoint = getAimPoint(target);
        Vec3d delta = aimPoint.subtract(mc.player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (delta.lengthSquared() < 1.0E-6 || horizontal < 1.0E-5) return;

        float wantedYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float wantedPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        float yawDelta = MathHelper.wrapDegrees(wantedYaw - mc.player.getYaw());
        float pitchDelta = MathHelper.wrapDegrees(wantedPitch - mc.player.getPitch());

        double angularError = Math.hypot(yawDelta, pitchDelta);
        if (angularError <= deadzone.getValue()) return;

        // Proportional following: small errors move gently, while large errors
        // remain constrained by the per-tick cap.
        float factor = (float) smoothing.getValue();
        float maxStep = (float) maxTurn.getValue();
        float yawStep = MathHelper.clamp(yawDelta * factor, -maxStep, maxStep);
        float pitchStep = MathHelper.clamp(pitchDelta * factor, -maxStep, maxStep);

        // Ignore sub-pixel camera changes to reduce tiny oscillations around the target.
        if (Math.abs(yawStep) < 0.015f) yawStep = 0.0f;
        if (Math.abs(pitchStep) < 0.015f) pitchStep = 0.0f;

        mc.player.setYaw(mc.player.getYaw() + yawStep);
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + pitchStep, -90.0f, 90.0f));
    }

    private LivingEntity findTarget() {
        var player = mc.player;
        double halfFov = fov.getValue() * 0.5;

        if (keepTarget.isEnabled() && lockedTargetId >= 0) {
            Entity existing = mc.world.getEntityById(lockedTargetId);
            if (existing instanceof LivingEntity living && lockedTargetUuid != null
                    && lockedTargetUuid.equals(living.getUuid()) && isValidTarget(living)
                    && angularDistance(living) <= halfFov + 8.0) {
                return living;
            }
        }

        List<Entity> nearby = mc.world.getOtherEntities(player,
                player.getBoundingBox().expand(range.getValue(), range.getValue() * 0.75, range.getValue()));
        return nearby.stream()
                .filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast)
                .filter(this::isValidTarget)
                .filter(entity -> angularDistance(entity) <= halfFov)
                .min(Comparator.comparingDouble(this::targetScore))
                .orElse(null);
    }

    private double targetScore(LivingEntity target) {
        // Crosshair alignment gets priority; distance is only a small tie-breaker.
        return angularDistance(target) + mc.player.distanceTo(target) * 0.18;
    }

    private void updateMotionEstimate(LivingEntity target) {
        Vec3d position = target.getPos();
        if (motionTargetId != target.getId() || lastTargetPosition == null) {
            motionTargetId = target.getId();
            lastTargetPosition = position;
            filteredVelocity = target.getVelocity();
            return;
        }

        Vec3d measured = position.subtract(lastTargetPosition);
        lastTargetPosition = position;
        // Ignore teleport/large correction deltas; they are not useful prediction data.
        if (measured.lengthSquared() > 1.25) measured = target.getVelocity();
        Vec3d reported = target.getVelocity();
        Vec3d observed = measured.multiply(0.65).add(reported.multiply(0.35));
        filteredVelocity = filteredVelocity.lerp(observed, 0.42);
    }

    private Vec3d getAimPoint(LivingEntity target) {
        var box = target.getBoundingBox();
        double boxWidth = box.maxX - box.minX;
        double boxDepth = box.maxZ - box.minZ;
        double boxHeight = box.maxY - box.minY;

        Vec3d velocity = motionTargetId == target.getId() ? filteredVelocity : target.getVelocity();
        double leadTicks = prediction.getValue();
        double leadX = velocity.x * leadTicks;
        double leadZ = velocity.z * leadTicks;
        double horizontalLead = Math.hypot(leadX, leadZ);
        // Cap prediction so velocity spikes never move the aim point far beyond the future hitbox.
        if (horizontalLead > 0.48) {
            double scale = 0.48 / horizontalLead;
            leadX *= scale;
            leadZ *= scale;
        }
        double leadY = MathHelper.clamp(velocity.y * leadTicks * 0.18, -0.12, 0.12);

        double minX = box.minX + leadX;
        double maxX = box.maxX + leadX;
        double minY = box.minY + leadY;
        double maxY = box.maxY + leadY;
        double minZ = box.minZ + leadZ;
        double maxZ = box.maxZ + leadZ;

        // Find the nearest safe horizontal point inside the predicted hitbox along
        // the player's current view ray. This avoids steering toward a fixed point
        // outside a narrow/side-on hitbox when the crosshair is already close.
        Vec3d eye = mc.player.getEyePos();
        Vec3d view = mc.player.getRotationVec(1.0f);
        Vec3d predictedCenter = new Vec3d((minX + maxX) * 0.5,
                (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
        double alongRay = Math.max(0.0, predictedCenter.subtract(eye).dotProduct(view));
        Vec3d rayPoint = eye.add(view.multiply(alongRay));

        double padX = Math.min(0.04, boxWidth * 0.12);
        double padY = Math.min(0.06, boxHeight * 0.08);
        double padZ = Math.min(0.04, boxDepth * 0.12);
        double aimX = MathHelper.clamp(rayPoint.x, minX + padX, maxX - padX);
        double aimZ = MathHelper.clamp(rayPoint.z, minZ + padZ, maxZ - padZ);
        double preferredY = minY + boxHeight * aimHeight.getValue();
        double aimY = MathHelper.clamp(preferredY, minY + padY, maxY - padY);
        return new Vec3d(aimX, aimY, aimZ);
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (entity == mc.player || entity.isRemoved() || !entity.isAlive() || entity.isSpectator()) return false;
        if (entity.getWorld() != mc.world || entity instanceof ArmorStandEntity) return false;
        if (ignoreInvisible.isEnabled() && entity.isInvisible()) return false;
        if (mc.player.isTeammate(entity)) return false;
        if (onlyVisible.isEnabled() && !mc.player.canSee(entity)) return false;
        if (mc.player.getEyePos().distanceTo(entity.getBoundingBox().getCenter()) > range.getValue()) return false;
        return TargetSelector.isValidType(entity, targets);
    }

    private float angularDistance(LivingEntity target) {
        Vec3d delta = getAimPoint(target).subtract(mc.player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        float yawDiff = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
        float pitchDiff = MathHelper.wrapDegrees(targetPitch - mc.player.getPitch());
        return (float) Math.hypot(yawDiff, pitchDiff);
    }
}
