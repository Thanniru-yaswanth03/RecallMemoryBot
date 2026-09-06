package com.recallbot.actuator;

import com.recallbot.config.properties.RecallProperties;
import com.recallbot.persistence.BasePostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class DeploymentReadinessTest extends BasePostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecallProperties properties;

    @Test
    @DisplayName("Actuator /actuator/health returns HTTP 200 with status UP under production profile")
    void healthEndpointReturnsUpInProd() throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Actuator /actuator/health/liveness probe responds with HTTP 200 UP for container orchestrators")
    void livenessProbeRespondsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Actuator /actuator/health/readiness probe responds with HTTP 200 UP when database is ready")
    void readinessProbeRespondsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Actuator /actuator/info endpoint is reachable and returns HTTP 200")
    void infoEndpointReachable() throws Exception {
        mockMvc.perform(get("/actuator/info")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Production configuration properties bind correctly with required vector dimension and parameters")
    void productionConfigurationPropertiesBound() {
        assertThat(properties).isNotNull();
        assertThat(properties.ai()).isNotNull();
        assertThat(properties.ai().embeddingDimension()).isEqualTo(1536);
        assertThat(properties.ai().embeddingModel()).isEqualTo("openai/text-embedding-3-small");
        assertThat(properties.rateLimit()).isNotNull();
        assertThat(properties.rateLimit().userPerMinute()).isGreaterThan(0);
        assertThat(properties.rateLimit().groupPerFiveMinutes()).isGreaterThan(0);
    }
}
