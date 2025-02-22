/*
 * Copyright (C) 2021-2024 Hedera Hashgraph, LLC
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

package com.swirlds.merkledb.files.hashmap;

import com.swirlds.merkledb.serialize.KeySerializer;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyFixedSize;

final class ReusableBucketPoolTest extends ReusableBucketPoolTestBase {

    @Override
    protected ReusableBucketPool<ExampleLongKeyFixedSize> createPool(
            final int size, final KeySerializer<ExampleLongKeyFixedSize> keySerializer) {
        return new ReusableBucketPool<>(size, pool -> new Bucket<>(keySerializer, pool));
    }
}
