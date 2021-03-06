/**
 * Copyright 2011-2015 Yahoo Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yahoo.omid.tso;

import static com.yahoo.omid.tso.RequestProcessorImpl.TSO_MAX_ITEMS_KEY;
import static com.yahoo.omid.tso.PersistenceProcessorImpl.TSO_MAX_BATCH_SIZE_KEY;
import static com.yahoo.omid.tso.PersistenceProcessorImpl.TSO_BATCH_PERSIST_TIMEOUT_MS_KEY;
import static com.yahoo.omid.committable.hbase.HBaseCommitTable.HBASE_COMMIT_TABLE_NAME_KEY;
import static com.yahoo.omid.tso.hbase.HBaseTimestampStorage.HBASE_TIMESTAMPSTORAGE_TABLE_NAME_KEY;

import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;

public class TSOModule extends AbstractModule {

    private final TSOServerCommandLineConfig config;

    public TSOModule(TSOServerCommandLineConfig config) {
        this.config = config;
    }

    @Override
    protected void configure() {

        bind(TimestampOracle.class).to(TimestampOracleImpl.class).in(Singleton.class);
        bind(Panicker.class).to(SystemExitPanicker.class).in(Singleton.class);

        bindConstant().annotatedWith(Names.named(TSO_MAX_BATCH_SIZE_KEY))
                .to(config.getMaxBatchSize());
        bindConstant().annotatedWith(Names.named(TSO_MAX_ITEMS_KEY))
                .to(config.getMaxItems());
        bindConstant().annotatedWith(Names.named(TSO_BATCH_PERSIST_TIMEOUT_MS_KEY))
            .to(config.getBatchPersistTimeoutMS());

        bindConstant().annotatedWith(Names.named(HBASE_COMMIT_TABLE_NAME_KEY))
                .to(config.getHBaseCommitTable());

        bindConstant().annotatedWith(Names.named(HBASE_TIMESTAMPSTORAGE_TABLE_NAME_KEY))
                .to(config.getHBaseTimestampTable());

        // Disruptor setup
        install(new DisruptorModule());

        // ZK Module
        install(new ZKModule(config));
    }

    @Provides
    TSOServerCommandLineConfig provideTSOServerConfig() {
        return config;
    }

}
