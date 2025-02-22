/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.consensus;

import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.test.fixtures.event.IndexedEvent;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** A class containing utilities for consensus tests. */
public abstract class ConsensusUtils {

    public static void loadEventsIntoGenerator(
            @NonNull final List<GossipEvent> events,
            @NonNull final GraphGenerator<?> generator,
            @NonNull final Random random) {
        Instant lastTimestamp = Instant.MIN;
        for (final Address address : generator.getAddressBook()) {
            final EventSource<?> source = generator.getSource(address.getNodeId());
            final List<GossipEvent> eventsByCreator = events.stream()
                    .filter(e -> e.getHashedData().getCreatorId().id()
                            == address.getNodeId().id())
                    .toList();
            eventsByCreator.forEach(e -> {
                final IndexedEvent indexedEvent = new IndexedEvent(e.getHashedData(), e.getUnhashedData(), null, null);
                source.setLatestEvent(random, indexedEvent);
            });
            final Instant creatorMax = eventsByCreator.stream()
                    .max(Comparator.comparingLong(GossipEvent::getGeneration))
                    .map(e -> e.getHashedData().getTimeCreated())
                    .orElse(Instant.MIN);
            lastTimestamp = Collections.max(Arrays.asList(lastTimestamp, creatorMax));
        }
        generator.setPreviousTimestamp(lastTimestamp);
    }
}
