package com.recsys.api.gateway;

import com.linecorp.armeria.client.ClientFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientFactoryTest {

    @Test
    void buildLlmClientFactory_returnsUsableFactory() {
        ClientFactory factory = MicroserviceGatewayServer.buildLlmClientFactory(k -> null);
        try {
            assertThat(factory).isNotNull();
        } finally {
            factory.close();
        }
    }
}
