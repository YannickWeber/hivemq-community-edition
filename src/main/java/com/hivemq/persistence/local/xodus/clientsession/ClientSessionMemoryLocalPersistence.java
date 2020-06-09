/*
 * Copyright 2020 dc-square GmbH
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

package com.hivemq.persistence.local.xodus.clientsession;

import com.google.common.collect.ImmutableSet;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.configuration.service.MqttConfigurationService;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.logging.EventLog;
import com.hivemq.persistence.NoSessionException;
import com.hivemq.persistence.PersistenceEntry;
import com.hivemq.persistence.PersistenceFilter;
import com.hivemq.persistence.clientsession.ClientSession;
import com.hivemq.persistence.clientsession.ClientSessionWill;
import com.hivemq.persistence.clientsession.PendingWillMessages;
import com.hivemq.persistence.exception.InvalidSessionExpiryIntervalException;
import com.hivemq.persistence.local.ClientSessionLocalPersistence;
import com.hivemq.persistence.local.xodus.BucketChunkResult;
import com.hivemq.persistence.local.xodus.bucket.BucketUtils;
import com.hivemq.persistence.payload.PublishPayloadPersistence;
import com.hivemq.util.ClientSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.hivemq.mqtt.message.connect.Mqtt5CONNECT.SESSION_EXPIRE_ON_DISCONNECT;
import static com.hivemq.mqtt.message.connect.Mqtt5CONNECT.SESSION_EXPIRY_NOT_SET;

/**
 * @author Georg Held
 */
public class ClientSessionMemoryLocalPersistence implements ClientSessionLocalPersistence {

    private static final @NotNull Logger log = LoggerFactory.getLogger(ClientSessionMemoryLocalPersistence.class);

    private final @NotNull PublishPayloadPersistence payloadPersistence;
    private final @NotNull EventLog eventLog;

    private final long configuredSessionExpiryInterval;
    private final @NotNull AtomicInteger sessionsCount = new AtomicInteger(0);

    private final int bucketCount;
    private final @NotNull Map<String, PersistenceEntry<ClientSession>>[] buckets;

    @Inject
    ClientSessionMemoryLocalPersistence(
            final @NotNull MqttConfigurationService mqttConfigurationService,
            final @NotNull PublishPayloadPersistence payloadPersistence,
            final @NotNull EventLog eventLog) {

        this.payloadPersistence = payloadPersistence;
        this.eventLog = eventLog;
        this.configuredSessionExpiryInterval = mqttConfigurationService.maxSessionExpiryInterval();

        bucketCount = InternalConfigurations.PERSISTENCE_BUCKET_COUNT.get();
        buckets = new ConcurrentHashMap[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new ConcurrentHashMap<>();
        }
    }

    private @NotNull Map<String, PersistenceEntry<ClientSession>> getBucket(final int bucketIndex) {
        checkArgument(bucketIndex <= bucketCount, "Bucket must be less or equal than bucketCount");
        return buckets[bucketIndex];
    }

    @Override
    public @Nullable ClientSession getSession(
            final @NotNull String clientId, final int bucketIndex, final boolean checkExpired) {
        return getSession(clientId, bucketIndex, checkExpired, true);
    }

    @Override
    public @Nullable ClientSession getSession(final @NotNull String clientId, final int bucketIndex) {
        return getSession(clientId, bucketIndex, true, true);
    }

    @Override
    public @Nullable ClientSession getSession(final @NotNull String clientId, final boolean checkExpired) {
        final int bucketIndex = BucketUtils.getBucket(clientId, bucketCount);
        return getSession(clientId, bucketIndex, checkExpired, true);
    }

    @Override
    public @Nullable ClientSession getSession(
            final @NotNull String clientId, final boolean checkExpired, final boolean includeWill) {
        final int bucketIndex = BucketUtils.getBucket(clientId, bucketCount);
        return getSession(clientId, bucketIndex, checkExpired, includeWill);
    }

    @Override
    public @Nullable ClientSession getSession(final @NotNull String clientId) {
        final int bucketIndex = BucketUtils.getBucket(clientId, bucketCount);
        return getSession(clientId, bucketIndex, true, true);
    }

    private @Nullable ClientSession getSession(
            final @NotNull String clientId,
            final int bucketIndex,
            final boolean checkExpired,
            final boolean includeWill) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final PersistenceEntry<ClientSession> storedSession = bucket.get(clientId);
        if (storedSession == null) {
            return null;
        }

        final ClientSession clientSession = storedSession.getObject().deepCopyWithoutPayload();

        if (checkExpired &&
                ClientSessions.isExpired(clientSession, System.currentTimeMillis() - storedSession.getTimestamp())) {
            return null;
        }
        if (includeWill) {
            dereferenceWillPayload(clientSession);
        }
        return clientSession;
    }

    @Override
    public @Nullable Long getTimestamp(final @NotNull String clientId) {
        final int bucketIndex = BucketUtils.getBucket(clientId, bucketCount);
        return getTimestamp(clientId, bucketIndex);
    }

    @Override
    public @Nullable Long getTimestamp(final @NotNull String clientId, final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final PersistenceEntry<ClientSession> storedSession = bucket.get(clientId);
        if (storedSession != null) {
            return storedSession.getTimestamp();
        }
        return null;
    }

    @Override
    public void put(
            final @NotNull String clientId,
            final @NotNull ClientSession clientSession,
            final long timestamp,
            final int bucketIndex) {
        checkNotNull(clientId, "Client id must not be null");
        checkNotNull(clientSession, "Client session must not be null");
        checkArgument(timestamp > 0, "Timestamp must be greater than 0");

        final Map<String, PersistenceEntry<ClientSession>> sessions = getBucket(bucketIndex);
        final ClientSession usedSession = clientSession.deepCopyWithoutPayload();
        final boolean isPersistent = persistent(usedSession);

        sessions.compute(clientId, (ignored, storedSession) -> {

            if (storedSession != null) {
                final ClientSession oldSession = storedSession.getObject();
                if (oldSession.getWillPublish() != null) {
                    removeWillReference(oldSession);
                }

                final boolean prevIsPersistent = persistent(oldSession);

                if ((isPersistent || usedSession.isConnected()) && (!prevIsPersistent && !oldSession.isConnected())) {
                    sessionsCount.incrementAndGet();
                } else if ((prevIsPersistent || oldSession.isConnected()) &&
                        (!isPersistent && !usedSession.isConnected())) {
                    sessionsCount.decrementAndGet();
                }

            } else if (isPersistent || usedSession.isConnected()) {
                sessionsCount.incrementAndGet();
            }

            // We remove the payload of the MqttWillPublish for storage.
            // It was already put into the PayloadPersistence in
            // ClientSessionPersistence.clientConnected().
            final ClientSessionWill willPublish = usedSession.getWillPublish();
            if (willPublish != null) {
                willPublish.getMqttWillPublish().setPayload(null);
            }

            return new PersistenceEntry<>(usedSession, timestamp);
        });
    }

    @Override
    public @NotNull ClientSession disconnect(
            final @NotNull String clientId,
            final long timestamp,
            final boolean sendWill,
            final int bucketIndex,
            final long expiry) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final PersistenceEntry<ClientSession> storedSession = bucket.compute(clientId, (ignored, oldSession) -> {

            if (oldSession == null) {
                // we create a tombstone here
                final ClientSession clientSession = new ClientSession(false, configuredSessionExpiryInterval);
                return new PersistenceEntry<>(clientSession, timestamp);
            }

            final ClientSession clientSession = oldSession.getObject();

            if (expiry != SESSION_EXPIRY_NOT_SET) {
                clientSession.setSessionExpiryInterval(expiry);
            }

            if (clientSession.isConnected() && !persistent(clientSession)) {
                sessionsCount.decrementAndGet();
            }

            clientSession.setConnected(false);

            if (!sendWill) {
                removeWillReference(clientSession);
                clientSession.setWillPublish(null);
            }

            dereferenceWillPayload(clientSession);
            return new PersistenceEntry<>(clientSession, timestamp);
        });

        return storedSession.getObject();
    }

    @Override
    public @NotNull Set<String> getAllClients(final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        return ImmutableSet.copyOf(bucket.keySet());
    }

    @Override
    public void removeWithTimestamp(final @NotNull String clientId, final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);
        final PersistenceEntry<ClientSession> remove = bucket.remove(clientId);
        if (remove != null) {
            final ClientSession clientSession = remove.getObject();
            if (persistent(clientSession) || clientSession.isConnected()) {
                sessionsCount.decrementAndGet();
            }
            removeWillReference(clientSession);
        }
    }

    @Override
    public @NotNull Set<String> cleanUp(final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final long currentTimeMillis = System.currentTimeMillis();
        final Iterator<Map.Entry<String, PersistenceEntry<ClientSession>>> iterator = bucket.entrySet().iterator();

        final ImmutableSet.Builder<String> expiredClientIds = ImmutableSet.builder();

        while (iterator.hasNext()) {
            final Map.Entry<String, PersistenceEntry<ClientSession>> entry = iterator.next();
            final PersistenceEntry<ClientSession> storedSession = entry.getValue();

            final long timestamp = storedSession.getTimestamp();
            final ClientSession clientSession = storedSession.getObject();

            final long sessionExpiryInterval = clientSession.getSessionExpiryInterval();

            if (ClientSessions.isExpired(clientSession, currentTimeMillis - timestamp)) {

                if (sessionExpiryInterval > SESSION_EXPIRE_ON_DISCONNECT) {
                    sessionsCount.decrementAndGet();
                }
                eventLog.clientSessionExpired(timestamp + sessionExpiryInterval * 1000, entry.getKey());
                expiredClientIds.add(entry.getKey());
                iterator.remove();
            }

        }

        return expiredClientIds.build();
    }

    @Override
    public @NotNull Set<String> getDisconnectedClients(final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);
        final long currentTimeMillis = System.currentTimeMillis();

        return bucket.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().getObject().isConnected())
                .filter(entry -> entry.getValue().getObject().getSessionExpiryInterval() > 0)
                .filter(entry -> {
                    final PersistenceEntry<ClientSession> storedSession = entry.getValue();
                    final long timeSinceDisconnect = currentTimeMillis - storedSession.getTimestamp();
                    final long sessionExpiryIntervalInMillis =
                            storedSession.getObject().getSessionExpiryInterval() * 1000L;
                    return timeSinceDisconnect < sessionExpiryIntervalInMillis;
                })
                .map(Map.Entry::getKey)
                .collect(ImmutableSet.toImmutableSet());
    }

    @Override
    public int getSessionsCount() {
        return sessionsCount.get();
    }

    @Override
    public void setSessionExpiryInterval(
            final @NotNull String clientId, final long sessionExpiryInterval, final int bucketIndex) {
        checkNotNull(clientId, "Client Id must not be null");

        if (sessionExpiryInterval < 0) {
            throw new InvalidSessionExpiryIntervalException("Invalid session expiry interval " + sessionExpiryInterval);
        }

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        bucket.compute(clientId, (ignored, storedSession) -> {


            if (storedSession == null) {
                throw NoSessionException.INSTANCE;
            }

            final ClientSession clientSession = storedSession.getObject();

            // is tombstone?
            if (!clientSession.isConnected() && !persistent(clientSession)) {
                throw NoSessionException.INSTANCE;
            }

            clientSession.setSessionExpiryInterval(sessionExpiryInterval);

            return new PersistenceEntry<>(clientSession, System.currentTimeMillis());
        });
    }

    @Override
    public @NotNull Map<String, PendingWillMessages.PendingWill> getPendingWills(
            final int bucketIndex) {


        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        return bucket.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().getObject().isConnected())
                .filter(entry -> entry.getValue().getObject().getWillPublish() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> {

                    final PersistenceEntry<ClientSession> storedSession = entry.getValue();
                    final ClientSessionWill willPublish = storedSession.getObject().getWillPublish();

                    return new PendingWillMessages.PendingWill(Math.min(willPublish.getDelayInterval(),
                            storedSession.getObject().getSessionExpiryInterval()),
                            willPublish.getDelayInterval());
                }));
    }

    @Override
    public @Nullable PersistenceEntry<ClientSession> removeWill(final @NotNull String clientId, final int bucketIndex) {

        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final PersistenceEntry<ClientSession> persistenceEntry =
                bucket.computeIfPresent(clientId, (ignored, storedSession) -> {
                    final ClientSession clientSession = storedSession.getObject();

                    // Just to be save, we do nothing
                    if (clientSession.isConnected()) {
                        return storedSession;
                    }

                    removeWillReference(clientSession);
                    clientSession.setWillPublish(null);

                    return storedSession;
                });

        if (persistenceEntry == null) {
            return null;
        }

        final ClientSession session = persistenceEntry.getObject();
        if (session.isConnected()) {
            return null;
        }
        return new PersistenceEntry<>(session.deepCopyWithoutPayload(), persistenceEntry.getTimestamp());
    }

    // in contrast to the file persistence method we already have everything in memory. The sizing and pagination are ignored.
    @Override
    public @NotNull BucketChunkResult<Map<String, ClientSession>> getAllClientsChunk(
            final @NotNull PersistenceFilter filter,
            final int bucketIndex,
            final @Nullable String ignored,
            final int alsoIgnored) {

        final long currentTimeMillis = System.currentTimeMillis();
        final Map<String, PersistenceEntry<ClientSession>> bucket = getBucket(bucketIndex);

        final Map<String, ClientSession> sessions =
                bucket.entrySet()
                        .stream()
                        .filter(entry -> filter.match(entry.getKey()))
                        .filter(entry -> {
                            final PersistenceEntry<ClientSession> value = entry.getValue();
                            return !ClientSessions.isExpired(value.getObject(),
                                    currentTimeMillis - value.getTimestamp());
                        })
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                entry -> entry.getValue().getObject().copyWithoutWill()));

        return new BucketChunkResult<>(sessions, true, null, bucketIndex);
    }

    @Override
    public void closeDB(final int ignored) {
        // noop
    }

    private void removeWillReference(final @NotNull ClientSession clientSession) {
        final ClientSessionWill willPublish = clientSession.getWillPublish();
        if (willPublish == null) {
            return;
        }
        payloadPersistence.decrementReferenceCounter(willPublish.getPayloadId());
    }

    private void dereferenceWillPayload(final @NotNull ClientSession clientSession) {
        final ClientSessionWill willPublish = clientSession.getWillPublish();
        if (willPublish == null) {
            return;
        }
        if (willPublish.getPayload() != null) {
            return;
        }
        final Long payloadId = willPublish.getPayloadId();
        final byte[] payload = payloadPersistence.getPayloadOrNull(payloadId);
        if (payload == null) {
            clientSession.setWillPublish(null);
            log.warn("Will Payload for payloadid {} not found", payloadId);
            return;
        }
        willPublish.getMqttWillPublish().setPayload(payload);
    }

    private boolean persistent(final @NotNull ClientSession clientSession) {
        return clientSession.getSessionExpiryInterval() > SESSION_EXPIRE_ON_DISCONNECT;
    }
}
