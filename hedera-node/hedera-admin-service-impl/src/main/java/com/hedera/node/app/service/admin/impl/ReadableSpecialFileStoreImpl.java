/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.admin.impl;

import com.hedera.node.app.service.admin.ReadableSpecialFileStore;
import com.hedera.node.app.spi.state.ReadableKVState;
import com.hedera.node.app.spi.state.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Optional;

/**
 * Implementation of {@link ReadableSpecialFileStore}
 */
public class ReadableSpecialFileStoreImpl implements ReadableSpecialFileStore {

    /** The underlying data storage class that holds the file data. */
    private final ReadableKVState<Long, byte[]> freezeFilesById;

    /**
     * Create a new {@link ReadableSpecialFileStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableSpecialFileStoreImpl(@NonNull final ReadableStates states) {
        Objects.requireNonNull(states);
        this.freezeFilesById = states.get(FreezeServiceImpl.UPGRADE_FILES_KEY);
    }

    @Override
    @NonNull
    public Optional<byte[]> get(long fileId) {
        final var file = freezeFilesById.get(fileId);
        return Optional.ofNullable(file);
    }
}