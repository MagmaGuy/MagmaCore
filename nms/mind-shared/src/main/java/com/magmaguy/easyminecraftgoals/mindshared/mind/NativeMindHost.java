package com.magmaguy.easyminecraftgoals.mindshared.mind;

import com.magmaguy.magmacore.ai.MindBodyRehydration;
import com.magmaguy.magmacore.ai.MindActionContext;
import com.magmaguy.magmacore.ai.MindActionRequest;
import com.magmaguy.magmacore.ai.MindActionResult;
import com.magmaguy.magmacore.ai.MindActionSink;
import com.magmaguy.magmacore.ai.MindBodyCapabilities;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindBodyProfile;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindFailureListener;
import com.magmaguy.magmacore.ai.MindHandle;
import com.magmaguy.magmacore.ai.MindHost;
import com.magmaguy.magmacore.ai.MindPersistentState;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MobBody;
import com.magmaguy.easyminecraftgoals.TransientMovementOverride;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class NativeMindHost implements MindHost {
    private final String hostIdentity;
    private final MindFailureListener failureListener;
    private final MindActionSink actionSink;
    private final MindBodyCapabilities bodyCapabilities;
    private final Map<UUID, NativeMindSession> sessions = new LinkedHashMap<>();
    private final NativeMindServerBudget serverBudget = new NativeMindServerBudget();
    private final NativeMindEnvironment environment;
    private boolean closed;

    public NativeMindHost(NamespacedKey hostIdentity, MindFailureListener failureListener) {
        this(hostIdentity, failureListener, MindActionSink.rejecting());
    }

    public NativeMindHost(
            NamespacedKey hostIdentity,
            MindFailureListener failureListener,
            MindActionSink actionSink) {
        this.hostIdentity = Objects.requireNonNull(hostIdentity, "hostIdentity").toString();
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        this.actionSink = Objects.requireNonNull(actionSink, "actionSink");
        if (!(Attributes.SCALE.value() instanceof RangedAttribute scale)) {
            throw new IllegalStateException("Minecraft's scale attribute no longer exposes numeric bounds");
        }
        bodyCapabilities = new MindBodyCapabilities(
                EnumSet.allOf(MindBodyLocomotion.class),
                EnumSet.of(
                        MindBodyLocomotion.GROUNDED,
                        MindBodyLocomotion.FLYING,
                        MindBodyLocomotion.AQUATIC,
                        MindBodyLocomotion.AMPHIBIOUS),
                true,
                scale.getMinValue(),
                scale.getMaxValue(),
                false,
                true,
                false);
        environment = new NativeMindEnvironment(this.hostIdentity);
    }

    @Override
    public MindHandle open(UUID logicalOwner, MindProgram program, MindPersistentState restoredState) {
        requirePrimaryThread();
        requireOpen();
        Objects.requireNonNull(logicalOwner, "logicalOwner");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(restoredState, "restoredState");
        if (sessions.containsKey(logicalOwner)) {
            throw new IllegalStateException("Logical mind session is already open: " + logicalOwner);
        }

        program.claimForBinding();
        NativeMindSession session = new NativeMindSession(
                this,
                logicalOwner,
                program,
                restoredState,
                serverBudget);
        sessions.put(logicalOwner, session);
        return session;
    }

    @Override
    public MindBodyCapabilities bodyCapabilities() {
        return bodyCapabilities;
    }

    @Override
    public MobBody spawnBody(Location location, MindBodyProfile profile, Consumer<MobBody> preparation) {
        requirePrimaryThread();
        requireOpen();
        Objects.requireNonNull(location, "location");
        bodyCapabilities.validate(profile);
        Objects.requireNonNull(preparation, "preparation");
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Native mind body requires a world");
        }

        ServerLevel level = NativeMindVersion.getServerLevel(location);
        EntityType<? extends Mob> carrierType = resolveCarrierType(profile.carrierType());
        net.minecraft.world.entity.Entity created = carrierType.create(level, EntitySpawnReason.COMMAND);
        if (!(created instanceof Mob mob) || mob.getType() != carrierType) {
            if (created != null) created.discard();
            throw new IllegalStateException("Minecraft did not create the requested native Mind carrier: "
                    + profile.carrierType());
        }
        NativeMindVersion.position(mob,
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
        mob.setPersistenceRequired();
        LivingEntity bukkitEntity = null;
        boolean published = false;
        NativeMindBody body = null;
        try {
            body = new NativeMindBody(this, mob, profile);
            bukkitEntity = body.entity();
            bukkitEntity.setCollidable(profile.entityCollidable());
            // CreatureSpawnEvent fires inside addFreshEntity. Mark the not-yet-added wrapper first so
            // consumers can distinguish this carrier before they run their normal spawn conversion.
            MindCarrierState.markBody(bukkitEntity, hostIdentity, profile);
            body.beginPreparation();
            preparation.accept(body);
            body.endPreparation();
            if (mob.isRemoved() || !mob.isAlive()) {
                throw new IllegalStateException("Mind body was removed during preparation");
            }
            if (!level.addFreshEntity(mob, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM)) {
                throw new IllegalStateException("Minecraft rejected the native mind body spawn");
            }
            published = true;
            return body;
        } finally {
            if (!published) {
                try {
                    if (body != null) body.abortPreparation();
                } finally {
                    if (bukkitEntity != null) MindCarrierState.clear(bukkitEntity, hostIdentity);
                    mob.discard();
                }
            }
        }
    }

    @Override
    public void tick() {
        requirePrimaryThread();
        requireOpen();
        for (NativeMindSession session : serverBudget.fairOrder(
                new ArrayList<>(sessions.values()))) {
            session.tickBody();
        }
    }

    public static boolean isMindCarrier(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return entity instanceof LivingEntity livingEntity
                && MindCarrierState.isMarked(livingEntity);
    }

    public Optional<TransientMovementOverride> beginFlee(
            LivingEntity body,
            Location threatLocation,
            double speedModifier) {
        requirePrimaryThread();
        requireOpen();
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(threatLocation, "threatLocation");
        for (NativeMindSession session : sessions.values()) {
            if (session.hasBody(body.getUniqueId())) {
                return session.beginFlee(threatLocation, speedModifier);
            }
        }
        return Optional.empty();
    }

    public Optional<com.magmaguy.easyminecraftgoals.PathfindingHandle> openPathfinding(LivingEntity body) {
        requirePrimaryThread();
        requireOpen();
        for (NativeMindSession session : sessions.values())
            if (session.hasBody(body.getUniqueId())) return session.openPathfinding();
        return Optional.empty();
    }

    @Override
    public Optional<MindBodyRehydration> rehydrateBody(LivingEntity carrier) {
        requirePrimaryThread();
        requireOpen();
        Objects.requireNonNull(carrier, "carrier");
        Optional<MindCarrierState.Stored> stored = MindCarrierState.read(carrier, hostIdentity);
        if (stored.isEmpty()) return Optional.empty();
        MindCarrierState.Stored marker = stored.get();
        bodyCapabilities.validate(marker.profile());

        net.minecraft.world.entity.LivingEntity loaded = NativeMindVersion.getNMSLivingEntity(carrier);
        if (!(loaded.level() instanceof ServerLevel)) {
            throw new IllegalArgumentException("Mind carrier is not in a server level");
        }
        if (!(loaded instanceof Mob mob)) {
            throw new IllegalStateException("Stored MagmaCore mind carrier is not a native Mob");
        }
        EntityType<? extends Mob> carrierType = resolveCarrierType(marker.profile().carrierType());
        if (mob.getType() != carrierType) {
            throw new IllegalStateException("MagmaCore mind carrier type disagrees with its marker");
        }
        NativeMindBody body = new NativeMindBody(this, mob, marker.profile());
        carrier.setCollidable(marker.profile().entityCollidable());

        return Optional.of(new MindBodyRehydration(
                body,
                marker.profile(),
                marker.logicalOwner(),
                marker.programIdentifier(),
                marker.programRevision(),
                marker.persistentState()));
    }

    String hostIdentity() {
        return hostIdentity;
    }

    void sessionClosed(NativeMindSession session) {
        sessions.remove(session.logicalOwner(), session);
    }

    void report(MindFailure failure) {
        try {
            failureListener.onFailure(failure);
        } catch (RuntimeException listenerFailure) {
            Bukkit.getLogger().warning(
                    "MagmaCore mind failure listener threw " + listenerFailure.getClass().getSimpleName());
        }
    }

    MindActionResult requestAction(
            NativeMindSession session,
            NativeMindBody body,
            long gameTick,
            long generation,
            MindActionRequest request) {
        requirePrimaryThread();
        Objects.requireNonNull(request, "request");
        MindActionResult result = actionSink.request(
                new MindActionContext(
                        session.logicalOwner(),
                        body,
                        gameTick,
                        generation),
                request);
        return Objects.requireNonNull(result, "Mind action sink result");
    }

    public void shutdown() {
        requirePrimaryThread();
        if (closed) return;
        closed = true;
        for (NativeMindSession session : new ArrayList<>(sessions.values())) {
            session.closeForShutdown();
        }
        sessions.clear();
        environment.close();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Native mind host is closed");
    }

    static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Native mind operations require the server thread");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EntityType<? extends Mob> resolveCarrierType(String carrierKey) {
        NamespacedKey bukkitKey = NamespacedKey.fromString(carrierKey);
        org.bukkit.entity.EntityType bukkitType = bukkitKey == null
                ? null
                : Registry.ENTITY_TYPE.get(bukkitKey);
        if (bukkitType == null
                || !bukkitType.isSpawnable()
                || bukkitType.getEntityClass() == null
                || !org.bukkit.entity.Mob.class.isAssignableFrom(bukkitType.getEntityClass())) {
            throw new IllegalArgumentException("Mind carrier is not a spawnable Mob type: "
                    + carrierKey);
        }
        EntityType<?> entityType = NativeMindVersion.entityType(carrierKey);
        if (entityType == null) {
            throw new IllegalArgumentException("Unknown Minecraft Mind carrier type: " + carrierKey);
        }
        return (EntityType) entityType;
    }

}
