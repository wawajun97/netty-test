package com.example.netty_test.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusOutputFormat;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureObservability
@ActiveProfiles("test")
class PrometheusEndpointTest {
    @Autowired
    private PrometheusScrapeEndpoint prometheusScrapeEndpoint;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    void exposesPrometheusMetrics() {
        prometheusMeterRegistry.counter("robot.test.probe").increment();

        WebEndpointResponse<byte[]> response = prometheusScrapeEndpoint.scrape(
                PrometheusOutputFormat.CONTENT_TYPE_004,
                null
        );
        String body = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body).contains("robot_test_probe_total");
    }
}
