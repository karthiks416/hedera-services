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

package com.swirlds.platform.state.nexus;

import com.swirlds.common.wiring.component.InputWireLabel;
import com.swirlds.platform.consensus.NonAncientEventWindow;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A nexus that holds the latest complete signed state.
 */
public interface LatestCompleteStateNexus extends SignedStateNexus {

    /**
     * Update the current event window. May cause the latest complete state to be thrown away if it has been a long
     * time since a state has been completely signed.
     */
    @InputWireLabel("non-ancient event window")
    void updateEventWindow(@NonNull NonAncientEventWindow eventWindow);

    /**
     * Replace the current state with the given state if the given state is newer than the current state.
     *
     * @param reservedSignedState the new state
     */
    @InputWireLabel("complete state")
    void setStateIfNewer(@NonNull ReservedSignedState reservedSignedState);
}
