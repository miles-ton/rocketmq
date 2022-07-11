/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.proxy.grpc.v2.common;

import apache.rocketmq.v2.Address;
import apache.rocketmq.v2.AddressScheme;
import apache.rocketmq.v2.CustomizedBackoff;
import apache.rocketmq.v2.Endpoints;
import apache.rocketmq.v2.ExponentialBackoff;
import apache.rocketmq.v2.Metric;
import apache.rocketmq.v2.Publishing;
import apache.rocketmq.v2.RetryPolicy;
import apache.rocketmq.v2.Settings;
import apache.rocketmq.v2.Subscription;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.rocketmq.common.subscription.CustomizedRetryPolicy;
import org.apache.rocketmq.common.subscription.ExponentialRetryPolicy;
import org.apache.rocketmq.common.subscription.GroupRetryPolicy;
import org.apache.rocketmq.common.subscription.GroupRetryPolicyType;
import org.apache.rocketmq.common.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.common.utils.ProxyUtils;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.config.MetricCollectorMode;
import org.apache.rocketmq.proxy.config.ProxyConfig;
import org.apache.rocketmq.proxy.processor.MessagingProcessor;

public class GrpcClientSettingsManager {

    // TODO: read config from topic or subscription configManager
    protected static final Settings DEFAULT_PRODUCER_SETTINGS = Settings.newBuilder()
        .setBackoffPolicy(RetryPolicy.newBuilder()
            .setMaxAttempts(3)
            .setExponentialBackoff(ExponentialBackoff.newBuilder()
                .setInitial(Durations.fromSeconds(1))
                .setMax(Durations.fromSeconds(3))
                .setMultiplier(2)
                .build())
            .build())
        .setPublishing(Publishing.newBuilder()
            .setMaxBodySize(4 * 1024 * 1024)
            .build())
        .build();
    protected static final Settings DEFAULT_CONSUMER_SETTINGS = mergeSubscriptionData(Settings.newBuilder()
        .setSubscription(Subscription.newBuilder()
            .setReceiveBatchSize(ProxyUtils.MAX_MSG_NUMS_FOR_POP_REQUEST)
            .setLongPollingTimeout(Durations.fromSeconds(30))
            .build())
        .build(), new SubscriptionGroupConfig());

    protected static final Map<String, Settings> CLIENT_SETTINGS_MAP = new ConcurrentHashMap<>();

    private final MessagingProcessor messagingProcessor;

    public GrpcClientSettingsManager(MessagingProcessor messagingProcessor) {
        this.messagingProcessor = messagingProcessor;
    }

    public Settings getClientSettings(ProxyContext ctx) {
        String clientId = ctx.getClientID();
        Settings settings = CLIENT_SETTINGS_MAP.get(clientId);
        if (settings == null) {
            return null;
        }
        if (settings.hasSubscription()) {
            settings = mergeSubscriptionData(ctx, settings,
                GrpcConverter.getInstance().wrapResourceWithNamespace(settings.getSubscription().getGroup()));
        }
        return mergeMetric(settings);
    }

    private Settings mergeSubscriptionData(ProxyContext ctx, Settings settings, String consumerGroup) {
        SubscriptionGroupConfig config = this.messagingProcessor.getSubscriptionGroupConfig(ctx, consumerGroup);
        if (config == null) {
            return settings;
        }

        return mergeSubscriptionData(settings, config);
    }

    private Settings mergeMetric(Settings settings) {
        // Construct metric according to the proxy config
        final ProxyConfig proxyConfig = ConfigurationManager.getProxyConfig();
        final MetricCollectorMode metricCollectorMode =
            MetricCollectorMode.getEnumByOrdinal(proxyConfig.getMetricCollectorMode());
        final String metricCollectorAddress = proxyConfig.getMetricCollectorAddress();
        final Metric.Builder metricBuilder = Metric.newBuilder();
        switch (metricCollectorMode) {
            case ON:
                final String[] split = metricCollectorAddress.split(":");
                final String host = split[0];
                final int port = Integer.parseInt(split[1]);
                Address address = Address.newBuilder().setHost(host).setPort(port).build();
                final Endpoints endpoints = Endpoints.newBuilder().setScheme(AddressScheme.IPv4)
                    .addAddresses(address).build();
                metricBuilder.setOn(true).setEndpoints(endpoints);
                break;
            case PROXY:
                metricBuilder.setOn(true).setEndpoints(settings.getAccessPoint());
                break;
            case OFF:
            default:
                metricBuilder.setOn(false);
                break;
        }
        Metric metric = metricBuilder.build();
        return settings.toBuilder().setMetric(metric).build();
    }

    protected static Settings mergeSubscriptionData(Settings settings, SubscriptionGroupConfig config) {
        Settings.Builder resultSettingsBuilder = settings.toBuilder();

        resultSettingsBuilder.getSubscriptionBuilder().setFifo(config.isConsumeMessageOrderly());

        resultSettingsBuilder.getBackoffPolicyBuilder().setMaxAttempts(config.getRetryMaxTimes() + 1);

        GroupRetryPolicy groupRetryPolicy = config.getGroupRetryPolicy();
        if (groupRetryPolicy.getType().equals(GroupRetryPolicyType.EXPONENTIAL)) {
            ExponentialRetryPolicy exponentialRetryPolicy = groupRetryPolicy.getExponentialRetryPolicy();
            if (exponentialRetryPolicy == null) {
                exponentialRetryPolicy = new ExponentialRetryPolicy();
            }
            resultSettingsBuilder.getBackoffPolicyBuilder().setExponentialBackoff(convertToExponentialBackoff(exponentialRetryPolicy));
        } else {
            CustomizedRetryPolicy customizedRetryPolicy = groupRetryPolicy.getCustomizedRetryPolicy();
            if (customizedRetryPolicy == null) {
                customizedRetryPolicy = new CustomizedRetryPolicy();
            }
            resultSettingsBuilder.getBackoffPolicyBuilder().setCustomizedBackoff(convertToCustomizedRetryPolicy(customizedRetryPolicy));
        }

        return resultSettingsBuilder.build();
    }

    protected static ExponentialBackoff convertToExponentialBackoff(ExponentialRetryPolicy retryPolicy) {
        return ExponentialBackoff.newBuilder()
            .setInitial(Durations.fromMillis(retryPolicy.getInitial()))
            .setMax(Durations.fromMillis(retryPolicy.getMax()))
            .setMultiplier(retryPolicy.getMultiplier())
            .build();
    }

    protected static CustomizedBackoff convertToCustomizedRetryPolicy(CustomizedRetryPolicy retryPolicy) {
        List<Duration> durationList = Arrays.stream(retryPolicy.getNext())
            .mapToObj(Durations::fromMillis).collect(Collectors.toList());
        return CustomizedBackoff.newBuilder()
            .addAllNext(durationList)
            .build();
    }

    public void updateClientSettings(String clientId, Settings settings) {
        if (settings.hasPublishing()) {
            settings = DEFAULT_PRODUCER_SETTINGS.toBuilder().mergeFrom(settings).build();
        } else if (settings.hasSubscription()) {
            settings = DEFAULT_CONSUMER_SETTINGS.toBuilder().mergeFrom(settings).build();
        }
        CLIENT_SETTINGS_MAP.put(clientId, settings);
    }

    public void removeClientSettings(String clientId) {
        CLIENT_SETTINGS_MAP.remove(clientId);
    }

    public Settings removeAndGetClientSettings(ProxyContext ctx) {
        String clientId = ctx.getClientID();
        Settings settings = CLIENT_SETTINGS_MAP.remove(clientId);
        if (settings == null) {
            return null;
        }
        return mergeSubscriptionData(ctx, settings,
            GrpcConverter.getInstance().wrapResourceWithNamespace(settings.getSubscription().getGroup()));
    }
}
