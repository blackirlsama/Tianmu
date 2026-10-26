package com.tianmu.agent.harness.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OtlpExportTest {
    @Test void configuredOtlpUsesStandardExporterAndSendsToCollectorEndpoint() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            if (exchange.getRequestBody().readAllBytes().length > 0) requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(TraceConfiguration.class).withBean(ObjectMapper.class, ObjectMapper::new)
                    .withPropertyValues("harness.trace.sink=otlp", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(context.getBean(SpanExporter.class) instanceof OutboxSpanExporter);
                        var provider = context.getBean(SdkTracerProvider.class);
                        provider.get("test").spanBuilder("local-fixture").startSpan().end();
                        assertTrue(provider.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
                        assertEquals(1, requests.get());
                    });
        } finally { server.stop(0); }
    }
}
