package com.recsys.microservice;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public final class NacosServiceRegistry implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(NacosServiceRegistry.class);

    private final boolean enabled;
    private final String groupName;
    private final NamingService namingService;
    private final AtomicInteger cursor = new AtomicInteger();

    private NacosServiceRegistry(boolean enabled, String groupName, NamingService namingService) {
        this.enabled = enabled;
        this.groupName = groupName;
        this.namingService = namingService;
    }

    public static NacosServiceRegistry fromEnv() {
        boolean enabled = readBooleanEnv("NACOS_DISCOVERY_ENABLED", readBooleanEnv("NACOS_ENABLED", false));
        String groupName = readEnv("NACOS_GROUP", "DEFAULT_GROUP");
        if (!enabled) {
            return new NacosServiceRegistry(false, groupName, null);
        }

        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, readEnv("NACOS_SERVER_ADDR", "localhost:8848"));
        putIfPresent(properties, PropertyKeyConst.NAMESPACE, System.getenv("NACOS_NAMESPACE"));
        putIfPresent(properties, PropertyKeyConst.USERNAME, System.getenv("NACOS_USERNAME"));
        putIfPresent(properties, PropertyKeyConst.PASSWORD, System.getenv("NACOS_PASSWORD"));

        try {
            return new NacosServiceRegistry(true, groupName, NacosFactory.createNamingService(properties));
        } catch (NacosException e) {
            throw new IllegalStateException("failed to create Nacos naming service", e);
        }
    }

    static NacosServiceRegistry disabled() {
        return new NacosServiceRegistry(false, "DEFAULT_GROUP", null);
    }

    public boolean enabled() {
        return enabled;
    }

    public URI resolve(String serviceName, URI fallback) {
        if (!enabled || serviceName == null || serviceName.isBlank()) {
            return fallback;
        }
        try {
            List<Instance> instances = namingService.selectInstances(serviceName, groupName, true);
            if (instances == null || instances.isEmpty()) {
                log.warn("No healthy Nacos instances for service {}, falling back to {}", serviceName, fallback);
                return fallback;
            }

            Instance selected = instances.get(Math.floorMod(cursor.getAndIncrement(), instances.size()));
            String scheme = selected.getMetadata().getOrDefault("scheme", "http");
            String contextPath = selected.getMetadata().getOrDefault("contextPath", "");
            return URI.create(scheme + "://" + selected.getIp() + ":" + selected.getPort() + contextPath);
        } catch (NacosException e) {
            log.warn("Nacos lookup failed for service {}, falling back to {}: {}", serviceName, fallback, e.toString());
            return fallback;
        }
    }

    public Registration register(String serviceName, int port, Map<String, String> metadata) {
        if (!enabled || serviceName == null || serviceName.isBlank()) {
            return Registration.noop();
        }
        Instance instance = new Instance();
        instance.setIp(readEnv("NACOS_REGISTER_IP", localHostAddress()));
        instance.setPort(port);
        instance.setEnabled(true);
        instance.setHealthy(true);
        instance.setEphemeral(readBooleanEnv("NACOS_EPHEMERAL", true));
        instance.setWeight(readDoubleEnv("NACOS_WEIGHT", 1.0D));
        instance.setMetadata(metadata);

        try {
            namingService.registerInstance(serviceName, groupName, instance);
            log.info("Registered service {} with Nacos group={} endpoint={}:{}", serviceName, groupName,
                    instance.getIp(), port);
            return new Registration(namingService, groupName, serviceName, instance);
        } catch (NacosException e) {
            throw new IllegalStateException("failed to register service with Nacos: " + serviceName, e);
        }
    }

    @Override
    public void close() {
        if (namingService != null) {
            try {
                namingService.shutDown();
            } catch (NacosException e) {
                log.warn("Failed to shut down Nacos naming service: {}", e.toString());
            }
        }
    }

    private static void putIfPresent(Properties properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.setProperty(key, value);
        }
    }

    private static String readEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static boolean readBooleanEnv(String name, boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static double readDoubleEnv(String name, double defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String localHostAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1";
        }
    }

    public static final class Registration implements AutoCloseable {
        private final NamingService namingService;
        private final String groupName;
        private final String serviceName;
        private final Instance instance;

        private Registration(NamingService namingService, String groupName, String serviceName, Instance instance) {
            this.namingService = namingService;
            this.groupName = groupName;
            this.serviceName = serviceName;
            this.instance = instance;
        }

        private static Registration noop() {
            return new Registration(null, "", "", null);
        }

        @Override
        public void close() {
            if (namingService == null || instance == null) {
                return;
            }
            try {
                namingService.deregisterInstance(serviceName, groupName, instance);
                log.info("Deregistered service {} from Nacos group={}", serviceName, groupName);
            } catch (NacosException e) {
                log.warn("Failed to deregister service {} from Nacos: {}", serviceName, e.toString());
            }
        }
    }
}
