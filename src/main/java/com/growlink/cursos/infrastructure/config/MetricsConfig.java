package com.growlink.cursos.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.util.List;

// ATRIBUTO: observabilidad. Etiqueta comun "instancia" en TODAS las metricas, igual que trivia-service y
// usuarios-service. Sin ella, con varias replicas detras del balanceador, el tablero sumaria los valores de todas y no
// se veria que una sola esta mal (ni cuanto trafico le toca a cada una). Ver el tablero "tecnico por instancia" y el
// ADR-0009 del repo infra.
//
// El valor sale de WEBSITE_INSTANCE_ID, que pone Azure App Service; si no viene (local, docker-compose) se usa el
// nombre del host, que es tambien lo que va en la cabecera X-Instancia (ver InstanciaHeaderFilter).
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    private final String instancia;

    public MetricsConfig(@Value("${observabilidad.instancia:}") String instanciaConfigurada) {
        this.instancia = instanciaConfigurada == null || instanciaConfigurada.isBlank()
                ? nombreDeHost()
                : instanciaConfigurada.trim();
        log.info("metricas etiquetadas con instancia={}", instancia);
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> etiquetaDeInstancia() {
        return registro -> registro.config()
                .meterFilter(MeterFilter.commonTags(List.of(Tag.of("instancia", instancia))));
    }

    String instancia() {
        return instancia;
    }

    private static String nombreDeHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "desconocida";
        }
    }
}
