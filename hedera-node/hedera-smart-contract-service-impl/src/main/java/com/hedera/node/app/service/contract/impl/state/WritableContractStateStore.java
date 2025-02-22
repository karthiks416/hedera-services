/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.state;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;

/**
 * A fully mutable {@link ContractStateStore}.
 */
public class WritableContractStateStore implements ContractStateStore {
    private final WritableKVState<SlotKey, SlotValue> storage;
    private final WritableKVState<ContractID, Bytecode> bytecode;

    /**
     * Create a new {@link WritableContractStateStore} instance.
     *
     * @param states The state to use.
     * @param configuration The configuration used to read the maximum capacity.
     * @param metrics The metrics-API used to report utilization.
     */
    public WritableContractStateStore(
            @NonNull final WritableStates states,
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics) {
        requireNonNull(states);
        this.storage = states.get(InitialModServiceContractSchema.STORAGE_KEY);
        this.bytecode = states.get(InitialModServiceContractSchema.BYTECODE_KEY);

        final ContractsConfig contractsConfig = configuration.getConfigData(ContractsConfig.class);

        final long maxSlotStorageCapacity = contractsConfig.maxKvPairsAggregate();
        storage.setupMetrics(metrics, "storageSlots", "storage slots", maxSlotStorageCapacity);

        final long maxContractsCapacity = contractsConfig.maxNumber();
        bytecode.setupMetrics(metrics, "contracts", maxContractsCapacity);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bytecode getBytecode(@NonNull final ContractID contractID) {
        return bytecode.get(requireNonNull(contractID));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putBytecode(@NonNull final ContractID contractID, @NonNull final Bytecode code) {
        bytecode.put(requireNonNull(contractID), requireNonNull(code));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeSlot(@NonNull final SlotKey key) {
        storage.remove(requireNonNull(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putSlot(@NonNull final SlotKey key, @NonNull final SlotValue value) {
        storage.put(requireNonNull(key), requireNonNull(value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<SlotKey> getModifiedSlotKeys() {
        return storage.modifiedKeys();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getSlotValue(@NonNull final SlotKey key) {
        return storage.get(requireNonNull(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SlotValue getSlotValueForModify(@NonNull SlotKey key) {
        return storage.getForModify(requireNonNull(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @Nullable SlotValue getOriginalSlotValue(@NonNull final SlotKey key) {
        return storage.getOriginalValue(requireNonNull(key));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumSlots() {
        return storage.size();
    }

    @Override
    public long getNumBytecodes() {
        return bytecode.size();
    }
}
