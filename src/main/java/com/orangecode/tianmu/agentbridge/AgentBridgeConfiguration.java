package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
@EnableConfigurationProperties(BridgeProperties.class)
public class AgentBridgeConfiguration {
    @Bean(destroyMethod="close") BusinessReadDatabase businessReadDatabase(DataSource existing,
            @Value("${BUSINESS_JDBC_URL:}") String url, @Value("${BUSINESS_DB_USER:}") String user,
            @Value("${BUSINESS_DB_PASSWORD:}") String password) throws Exception {
        return new BusinessReadDatabase(existing, url, user, password);
    }
    @Bean BusinessAuthentication businessAuthentication(BusinessReadDatabase reader, StringRedisTemplate redis, BridgeProperties properties) {
        return new BusinessAuthentication(new JdbcTemplate(reader.source()), redis, properties);
    }
    @Bean BusinessMetrics businessMetrics(BusinessReadDatabase reader, BridgeProperties properties) { return new BusinessMetrics(new JdbcTemplate(reader.source()), properties); }
    @Bean BusinessVideoContent businessVideoContent(BusinessReadDatabase reader,
            org.springframework.beans.factory.ObjectProvider<io.minio.MinioClient> minio,
            @Value("${minio.bucket-name:tianmu-upload}") String bucket) {
        return new BusinessVideoContent(new JdbcTemplate(reader.source()), minio.getIfAvailable(), bucket);
    }
    @Bean BusinessDanmakuAnalysis businessDanmakuAnalysis(BusinessReadDatabase reader, BridgeProperties properties) { return new BusinessDanmakuAnalysis(new JdbcTemplate(reader.source()), properties); }
    @Bean BusinessCommentAnalysis businessCommentAnalysis(BusinessReadDatabase reader, BridgeProperties properties) { return new BusinessCommentAnalysis(new JdbcTemplate(reader.source()), properties); }
    @Bean BusinessAudienceAnalysis businessAudienceAnalysis(BusinessReadDatabase reader,BridgeProperties properties,BusinessDanmakuAnalysis danmaku,BusinessCommentAnalysis comments) {
        return new BusinessAudienceAnalysis(new JdbcTemplate(reader.source()),properties,danmaku,comments);
    }
}
