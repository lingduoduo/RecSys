package com.recsys.infrastructure.autoscaling;

public interface InstanceProvisioner {
    Ec2Instance launch(LaunchTemplate template, String availabilityZone);
    void terminate(String instanceId);
}
