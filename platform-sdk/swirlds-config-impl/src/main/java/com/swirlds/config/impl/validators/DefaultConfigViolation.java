/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.swirlds.config.impl.validators;

import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.PropertyMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * An immutable default implementation of {@link ConfigViolation}.
 *
 * @param propertyName the name of the property that causes the violation
 * @param value        the value of the property that causes the violation
 * @param exists       defines whether the property that caused the violation exists
 * @param message      message of the violation
 */
public record DefaultConfigViolation(String propertyName, String value, boolean exists, String message)
        implements ConfigViolation {

    /**
     * Factory method to create a {@link ConfigViolation}.
     *
     * @param metadata the metadata of the property that causes the violation
     * @param message  the violation message
     * @return a new {@link ConfigViolation} instance
     */
    public static ConfigViolation of(@NonNull final PropertyMetadata<?> metadata, final String message) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        return new DefaultConfigViolation(metadata.getName(), metadata.getRawValue(), metadata.exists(), message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPropertyName() {
        return propertyName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getMessage() {
        return message();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPropertyValue() {
        return value();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean propertyExists() {
        return exists();
    }
}
