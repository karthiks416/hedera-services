/*
 * Copyright (C) 2016-2023 Hedera Hashgraph, LLC
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

package com.swirlds.demo.platform;

public class PlatformConfig {
    /** should this run with no windows? */
    boolean headless = true;
    /** number of milliseconds between writes to the log file */
    int writePeriod = 3000;
    /** milliseconds of sleep after each sync */
    long syncDelay = 0;
    /** if false SwirldState1 will be used, if true SwirldState2 will be used */
    boolean useSwirldState2 = true;

    private PlatformConfig() {}

    static PlatformConfig getDefault() {
        return new PlatformConfig();
    }
}