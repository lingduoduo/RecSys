package com.recsys.infrastructure.autoscaling;

import com.recsys.infrastructure.alb.AlbTarget;
import com.recsys.infrastructure.alb.AlbTargetGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Auto Scaling Group — manages a fleet of EC2 instances as a collective unit.
 *
 * Scale-out adds to the least-loaded AZ and registers each new instance with the
 * optional ALB target group. Scale-in drains and deregisters from the most-loaded
 * AZ first to maintain AZ balance. replaceUnhealthy() uses the ALB health state to
 * detect and replace failed instances without changing the running count.
 */
public final class AutoScalingGroup {

    private final String name;
    private final LaunchTemplate launchTemplate;
    private final NetworkConfig networkConfig;
    private final ScalingConfig scalingConfig;
    private final InstanceProvisioner provisioner;
    private final AlbTargetGroup targetGroup;   // null when no ELB is attached

    private final CopyOnWriteArrayList<Ec2Instance> instances = new CopyOnWriteArrayList<>();
    private final ReentrantLock scalingLock = new ReentrantLock();

    private AutoScalingGroup(Builder builder) {
        this.name = builder.name;
        this.launchTemplate = builder.launchTemplate;
        this.networkConfig = builder.networkConfig;
        this.scalingConfig = builder.scalingConfig;
        this.provisioner = builder.provisioner;
        this.targetGroup = builder.targetGroup;
    }

    // ── Read accessors ────────────────────────────────────────────────────────

    public String name() { return name; }
    public ScalingConfig scalingConfig() { return scalingConfig; }
    public NetworkConfig networkConfig() { return networkConfig; }
    public Optional<AlbTargetGroup> targetGroup() { return Optional.ofNullable(targetGroup); }

    public List<Ec2Instance> instances() {
        return List.copyOf(instances);
    }

    public int runningCount() {
        return (int) instances.stream().filter(Ec2Instance::isRunning).count();
    }

    // ── Scaling operations ────────────────────────────────────────────────────

    public void setDesiredCapacity(int desired) {
        int clamped = scalingConfig.clamp(desired);
        scalingLock.lock();
        try {
            int current = runningCount();
            if (clamped > current) {
                scaleOut(clamped - current);
            } else if (clamped < current) {
                scaleIn(current - clamped);
            }
        } finally {
            scalingLock.unlock();
        }
    }

    public void scaleOut(int count) {
        if (count <= 0) return;
        scalingLock.lock();
        try {
            for (int i = 0; i < count; i++) {
                if (runningCount() >= scalingConfig.maxSize()) break;
                String az = leastLoadedAz();
                Ec2Instance instance = provisioner.launch(launchTemplate, az);
                instances.add(instance);
                if (targetGroup != null) {
                    targetGroup.registerTarget(
                            AlbTarget.of(instance.privateIpAddress(), launchTemplate.servicePort()));
                }
            }
        } finally {
            scalingLock.unlock();
        }
    }

    public void scaleIn(int count) {
        if (count <= 0) return;
        scalingLock.lock();
        try {
            List<Ec2Instance> toTerminate = selectForTermination(count);
            for (Ec2Instance instance : toTerminate) {
                if (runningCount() <= scalingConfig.minSize()) break;
                if (targetGroup != null) {
                    targetGroup.updateHealth(instance.privateIpAddress(),
                            launchTemplate.servicePort(), AlbTarget.TargetHealth.DRAINING);
                    targetGroup.deregisterTarget(instance.privateIpAddress(), launchTemplate.servicePort());
                }
                provisioner.terminate(instance.instanceId());
                instances.remove(instance);
            }
        } finally {
            scalingLock.unlock();
        }
    }

    /**
     * Checks ALB health state and replaces any instance that the load balancer
     * has marked UNHEALTHY. The running count stays the same: one replacement
     * is launched before the failed instance is terminated.
     */
    public void replaceUnhealthyInstances() {
        if (targetGroup == null) return;
        scalingLock.lock();
        try {
            List<String> unhealthyIps = targetGroup.targets().stream()
                    .filter(t -> t.health() == AlbTarget.TargetHealth.UNHEALTHY)
                    .map(AlbTarget::host)
                    .toList();

            for (String ip : unhealthyIps) {
                instances.stream()
                        .filter(i -> i.privateIpAddress().equals(ip))
                        .findFirst()
                        .ifPresent(failed -> {
                            String az = failed.availabilityZone();
                            targetGroup.deregisterTarget(ip, launchTemplate.servicePort());
                            provisioner.terminate(failed.instanceId());
                            instances.remove(failed);

                            Ec2Instance replacement = provisioner.launch(launchTemplate, az);
                            instances.add(replacement);
                            targetGroup.registerTarget(
                                    AlbTarget.of(replacement.privateIpAddress(),
                                            launchTemplate.servicePort()));
                        });
            }
        } finally {
            scalingLock.unlock();
        }
    }

    // ── AZ balancing ──────────────────────────────────────────────────────────

    private String leastLoadedAz() {
        Map<String, Long> countByAz = instances.stream()
                .collect(Collectors.groupingBy(Ec2Instance::availabilityZone, Collectors.counting()));
        return networkConfig.availabilityZones().stream()
                .min(Comparator.comparingLong(az -> countByAz.getOrDefault(az, 0L)))
                .orElseThrow();
    }

    // Selects instances from the most-loaded AZ first to restore balance on scale-in.
    private List<Ec2Instance> selectForTermination(int count) {
        Map<String, List<Ec2Instance>> byAz = instances.stream()
                .filter(Ec2Instance::isRunning)
                .collect(Collectors.groupingBy(Ec2Instance::availabilityZone));

        List<Ec2Instance> selected = new ArrayList<>();
        while (selected.size() < count) {
            String mostLoaded = byAz.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty())
                    .max(Comparator.comparingInt(e -> e.getValue().size()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (mostLoaded == null) break;
            List<Ec2Instance> azInstances = byAz.get(mostLoaded);
            selected.add(azInstances.remove(azInstances.size() - 1));
        }
        return selected;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private LaunchTemplate launchTemplate;
        private NetworkConfig networkConfig;
        private ScalingConfig scalingConfig;
        private InstanceProvisioner provisioner;
        private AlbTargetGroup targetGroup;

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            this.name = name;
        }

        public Builder launchTemplate(LaunchTemplate t) {
            this.launchTemplate = Objects.requireNonNull(t, "launchTemplate");
            return this;
        }

        public Builder networkConfig(NetworkConfig n) {
            this.networkConfig = Objects.requireNonNull(n, "networkConfig");
            return this;
        }

        public Builder scalingConfig(ScalingConfig s) {
            this.scalingConfig = Objects.requireNonNull(s, "scalingConfig");
            return this;
        }

        public Builder provisioner(InstanceProvisioner p) {
            this.provisioner = Objects.requireNonNull(p, "provisioner");
            return this;
        }

        public Builder targetGroup(AlbTargetGroup tg) {
            this.targetGroup = Objects.requireNonNull(tg, "targetGroup");
            return this;
        }

        public AutoScalingGroup build() {
            if (launchTemplate == null)  throw new IllegalStateException("launchTemplate is required");
            if (networkConfig == null)   throw new IllegalStateException("networkConfig is required");
            if (scalingConfig == null)   throw new IllegalStateException("scalingConfig is required");
            if (provisioner == null)     throw new IllegalStateException("provisioner is required");
            return new AutoScalingGroup(this);
        }
    }
}
