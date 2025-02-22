/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.builder;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.crypto.KeysAndCerts;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.recovery.EmergencyRecoveryManager;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.SoftwareVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.function.Consumer;

/**
 * This record contains core utilities and basic objects needed to build a platform. It should not contain any platform
 * components.
 *
 * @param platformContext           the context for this platform
 * @param keysAndCerts              an object holding all the public/private key pairs and the CSPRNG state for this
 *                                  member
 * @param recycleBin                used to delete files that may be useful for later debugging
 * @param selfId                    the ID for this node
 * @param mainClassName             the name of the app class inheriting from SwirldMain
 * @param swirldName                the name of the swirld being run
 * @param appVersion                the current version of the running application
 * @param initialState              the initial state of the platform
 * @param emergencyRecoveryManager  used in emergency recovery.
 * @param preconsensusEventConsumer the consumer for preconsensus events, null if publishing this data has not been
 *                                  enabled
 * @param snapshotOverrideConsumer  the consumer for snapshot overrides, null if publishing this data has not been
 *                                  enabled
 * @param intakeEventCounter        counts events that have been received by gossip but not yet inserted into gossip
 *                                  event storage, per peer
 * @param firstPlatform             if this is the first platform being built (there is static setup that needs to be
 *                                  done, long term plan is to stop using static variables)
 */
public record PlatformBuildingBlocks(
        @NonNull PlatformContext platformContext,
        @NonNull KeysAndCerts keysAndCerts,
        @NonNull RecycleBin recycleBin,
        @NonNull NodeId selfId,
        @NonNull String mainClassName,
        @NonNull String swirldName,
        @NonNull SoftwareVersion appVersion,
        @NonNull ReservedSignedState initialState,
        @NonNull EmergencyRecoveryManager emergencyRecoveryManager,
        @Nullable Consumer<GossipEvent> preconsensusEventConsumer,
        @Nullable Consumer<ConsensusSnapshot> snapshotOverrideConsumer,
        @NonNull IntakeEventCounter intakeEventCounter,
        boolean firstPlatform) {}
