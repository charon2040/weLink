package com.epsilon.welink.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${welink.kafka.partitions.private:8}")
    private int privatePartitions;

    @Value("${welink.kafka.partitions.private-ingress:8}")
    private int privateIngressPartitions;

    @Value("${welink.kafka.partitions.group:8}")
    private int groupPartitions;

    @Value("${welink.kafka.partitions.group-ingress:8}")
    private int groupIngressPartitions;

    @Value("${welink.kafka.partitions.large-group:4}")
    private int largeGroupPartitions;

    @Value("${welink.kafka.partitions.presence:4}")
    private int presencePartitions;

    @Value("${welink.kafka.partitions.recall:4}")
    private int recallPartitions;

    @Value("${welink.kafka.replicas:1}")
    private short replicas;

    @Bean
    public NewTopic imPrivateMessageTopic() {
        return TopicBuilder.name("im-private-message")
                .partitions(privatePartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imPrivateIngressTopic() {
        return TopicBuilder.name("im-private-ingress")
                .partitions(privateIngressPartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imGroupMessageTopic() {
        return TopicBuilder.name("im-group-message")
                .partitions(groupPartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imGroupIngressTopic() {
        return TopicBuilder.name("im-group-ingress")
                .partitions(groupIngressPartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imLargeGroupMessageTopic() {
        return TopicBuilder.name("im-large-group-message")
                .partitions(largeGroupPartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imPresenceTopic() {
        return TopicBuilder.name("im-presence")
                .partitions(presencePartitions)
                .replicas(replicas)
                .build();
    }

    @Bean
    public NewTopic imRecallTopic() {
        return TopicBuilder.name("im-recall")
                .partitions(recallPartitions)
                .replicas(replicas)
                .build();
    }
}
