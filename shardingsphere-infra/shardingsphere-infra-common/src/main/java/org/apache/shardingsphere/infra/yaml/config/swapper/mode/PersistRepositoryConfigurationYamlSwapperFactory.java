/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.infra.yaml.config.swapper.mode;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.shardingsphere.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.spi.type.typed.TypedSPIRegistry;

import java.util.Optional;

/**
 * Persist repository configuration YAML swapper factory.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings("rawtypes")
public final class PersistRepositoryConfigurationYamlSwapperFactory {
    
    static {
        ShardingSphereServiceLoader.register(PersistRepositoryConfigurationYamlSwapper.class);
    }
    
    /**
     * Create new instance of persist repository configuration YAML swapper.
     * 
     * @param type swapper type
     * @return new instance of persist repository configuration YAML swapper
     */
    public static PersistRepositoryConfigurationYamlSwapper newInstance(final String type) {
        return TypedSPIRegistry.getRegisteredService(PersistRepositoryConfigurationYamlSwapper.class, type);
    }
    
    /**
     * Find instance of persist repository configuration YAML swapper.
     *
     * @param type swapper type
     * @return found of persist repository configuration YAML swapper
     */
    public static Optional<PersistRepositoryConfigurationYamlSwapper> findInstance(final String type) {
        return TypedSPIRegistry.findRegisteredService(PersistRepositoryConfigurationYamlSwapper.class, type);
    }
}
