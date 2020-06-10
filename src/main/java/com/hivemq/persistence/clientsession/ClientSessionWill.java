/*
 * Copyright 2019 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.persistence.clientsession;

import com.hivemq.codec.encoder.mqtt5.Mqtt5PayloadFormatIndicator;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.mqtt.message.QoS;
import com.hivemq.mqtt.message.connect.MqttWillPublish;
import com.hivemq.mqtt.message.mqtt5.Mqtt5UserProperties;
import com.hivemq.mqtt.message.mqtt5.MqttUserProperty;
import com.hivemq.persistence.Sizable;
import com.hivemq.util.ObjectMemoryEstimation;

/**
 * @author Lukas Brandl
 */
public class ClientSessionWill implements Sizable {

    private final @NotNull MqttWillPublish mqttWillPublish;
    private final @Nullable Long payloadId;

    private volatile int inMemorySize = SIZE_NOT_CALCULATED;

    public ClientSessionWill(final @NotNull MqttWillPublish mqttWillPublish, final @NotNull Long payloadId) {
        this.mqttWillPublish = mqttWillPublish;
        this.payloadId = payloadId;
    }

    public MqttWillPublish getMqttWillPublish() {
        return mqttWillPublish;
    }

    public Long getPayloadId() {
        return payloadId;
    }

    public long getDelayInterval() {
        return mqttWillPublish.getDelayInterval();
    }

    public String getHivemqId() {
        return mqttWillPublish.getHivemqId();
    }

    public String getTopic() {
        return mqttWillPublish.getTopic();
    }

    public byte[] getPayload() {
        return mqttWillPublish.getPayload();
    }

    public QoS getQos() {
        return mqttWillPublish.getQos();
    }

    public boolean isRetain() {
        return mqttWillPublish.isRetain();
    }

    public long getMessageExpiryInterval() {
        return mqttWillPublish.getMessageExpiryInterval();
    }

    public Mqtt5PayloadFormatIndicator getPayloadFormatIndicator() {
        return mqttWillPublish.getPayloadFormatIndicator();
    }

    public String getContentType() {
        return mqttWillPublish.getContentType();
    }

    public String getResponseTopic() {
        return mqttWillPublish.getResponseTopic();
    }

    public byte[] getCorrelationData() {
        return mqttWillPublish.getCorrelationData();
    }

    public Mqtt5UserProperties getUserProperties() {
        return mqttWillPublish.getUserProperties();
    }

    public @NotNull ClientSessionWill deepCopyWithoutPayload() {
        return new ClientSessionWill(this.getMqttWillPublish().deepCopyWithoutPayload(), this.payloadId);
    }

    @Override
    public int getEstimatedSize() {
        if (inMemorySize != SIZE_NOT_CALCULATED) {
            return inMemorySize;
        }

        int size = ObjectMemoryEstimation.objectShellSize();

        size += ObjectMemoryEstimation.longWrapperSize(); //payload id
        size += ObjectMemoryEstimation.objectRefSize();
        size += ObjectMemoryEstimation.intSize(); // size

        size += ObjectMemoryEstimation.enumSize(); // QoS
        size += ObjectMemoryEstimation.longSize(); // expiry interval

        size += 24; //User Properties Overhead
        for (final MqttUserProperty userProperty : getUserProperties().asList()) {
            size += 8; //UserProperty Object Overhead
            size += ObjectMemoryEstimation.stringSize(userProperty.getName());
            size += ObjectMemoryEstimation.stringSize(userProperty.getValue());
        }

        size += ObjectMemoryEstimation.longSize(); //delay interval
        size += ObjectMemoryEstimation.stringSize(mqttWillPublish.getResponseTopic());
        size += ObjectMemoryEstimation.stringSize(mqttWillPublish.getContentType());
        size += ObjectMemoryEstimation.byteArraySize(mqttWillPublish.getCorrelationData());

        size += ObjectMemoryEstimation.enumSize(); // Payload format indicator
        size += ObjectMemoryEstimation.longSize(); // timestamp
        size += ObjectMemoryEstimation.booleanSize(); // isRetain

        inMemorySize = size;
        return inMemorySize;
    }
}
