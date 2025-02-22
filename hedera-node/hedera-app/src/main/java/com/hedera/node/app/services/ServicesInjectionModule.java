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

package com.hedera.node.app.services;

import com.hedera.node.app.service.consensus.impl.ConsensusServiceInjectionModule;
import com.hedera.node.app.service.file.impl.FileServiceInjectionModule;
import com.hedera.node.app.service.networkadmin.impl.NetworkAdminServiceInjectionModule;
import com.hedera.node.app.service.schedule.impl.ScheduleServiceInjectionModule;
import com.hedera.node.app.service.token.impl.TokenServiceInjectionModule;
import com.hedera.node.app.service.util.impl.UtilServiceInjectionModule;
import dagger.Module;

/**
 * Dagger module for all services
 */
@Module(
        includes = {
            ConsensusServiceInjectionModule.class,
            FileServiceInjectionModule.class,
            NetworkAdminServiceInjectionModule.class,
            ScheduleServiceInjectionModule.class,
            TokenServiceInjectionModule.class,
            UtilServiceInjectionModule.class,
        })
public interface ServicesInjectionModule {}
