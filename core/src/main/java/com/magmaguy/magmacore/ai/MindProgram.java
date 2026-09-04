package com.magmaguy.magmacore.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MindProgram {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    private final String identifier;
    private final long revision;
    private final MindSchema schema;
    private final List<MindSensor> sensors;
    private final List<MindBehavior> behaviors;
    private final MindExecutionPolicy executionPolicy;
    private final AtomicBoolean claimed = new AtomicBoolean();

    private MindProgram(Builder builder) {
        if (builder.identifier == null || !IDENTIFIER.matcher(builder.identifier).matches()) {
            throw new IllegalStateException("Mind program identifier must be a namespaced lower-case key");
        }
        if (builder.revision < 1) throw new IllegalStateException("Mind program revision must be positive");
        this.identifier = builder.identifier;
        this.revision = builder.revision;
        this.schema = builder.schema;
        this.sensors = immutableUnique(builder.sensors, "sensor");
        List<MindBehavior> orderedBehaviors = new ArrayList<>(builder.behaviors);
        orderedBehaviors.sort(Comparator.comparingInt(MindBehavior::priority)
                .thenComparing(MindBehavior::identifier));
        this.behaviors = immutableUnique(orderedBehaviors, "behavior");
        this.executionPolicy = builder.executionPolicy;
    }

    public static Builder builder(String identifier, long revision) {
        return new Builder(identifier, revision);
    }

    public String identifier() {
        return identifier;
    }

    public long revision() {
        return revision;
    }

    public MindSchema schema() {
        return schema;
    }

    public List<MindSensor> sensors() {
        return sensors;
    }

    public List<MindBehavior> behaviors() {
        return behaviors;
    }

    public MindExecutionPolicy executionPolicy() {
        return executionPolicy;
    }

    /**
     * Claims this executable program for one entity. Programs contain callback instances and are
     * deliberately single-use; catalogs must retain {@link MindProgramFactory} values instead.
     */
    public void claimForBinding() {
        if (!claimed.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Mind program instances cannot be shared across entity bindings: " + identifier);
        }
    }

    private static <T> List<T> immutableUnique(List<T> values, String kind) {
        Set<String> identifiers = new HashSet<>();
        for (T value : values) {
            String identifier = value instanceof MindSensor sensor
                    ? sensor.identifier()
                    : ((MindBehavior) value).identifier();
            if (identifier == null || identifier.isBlank()) {
                throw new IllegalStateException("Mind " + kind + " identifier cannot be blank");
            }
            if (!identifiers.add(identifier)) {
                throw new IllegalStateException("Duplicate mind " + kind + ": " + identifier);
            }
            if (value instanceof MindSensor sensor && sensor.intervalTicks() <= 0) {
                throw new IllegalStateException("Mind sensor interval must be positive: " + identifier);
            }
            if (value instanceof MindBehavior behavior) {
                Set<MindControl> controls = Objects.requireNonNull(
                        behavior.controls(), "Mind behavior controls");
                if (controls.stream().anyMatch(Objects::isNull)) {
                    throw new IllegalStateException(
                            "Mind behavior controls cannot contain null: " + identifier);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Builder {
        private final String identifier;
        private final long revision;
        private MindSchema schema = MindSchema.empty();
        private final List<MindSensor> sensors = new ArrayList<>();
        private final List<MindBehavior> behaviors = new ArrayList<>();
        private MindExecutionPolicy executionPolicy = MindExecutionPolicy.DEFAULT;

        private Builder(String identifier, long revision) {
            this.identifier = identifier;
            this.revision = revision;
        }

        public Builder schema(MindSchema schema) {
            this.schema = Objects.requireNonNull(schema, "schema");
            return this;
        }

        public Builder sensor(MindSensor sensor) {
            sensors.add(Objects.requireNonNull(sensor, "sensor"));
            return this;
        }

        public Builder behavior(MindBehavior behavior) {
            behaviors.add(Objects.requireNonNull(behavior, "behavior"));
            return this;
        }

        public Builder executionPolicy(MindExecutionPolicy executionPolicy) {
            this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
            return this;
        }

        public MindProgram build() {
            return new MindProgram(this);
        }
    }
}
