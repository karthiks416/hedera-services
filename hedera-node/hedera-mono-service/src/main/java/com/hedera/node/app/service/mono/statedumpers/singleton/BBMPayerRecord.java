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

package com.hedera.node.app.service.mono.statedumpers.singleton;

import com.hedera.hapi.node.state.recordcache.TransactionRecordEntry;
import com.hedera.node.app.service.mono.state.submerkle.EntityId;
import com.hedera.node.app.service.mono.state.submerkle.ExpirableTxnRecord;
import com.hedera.node.app.service.mono.state.submerkle.RichInstant;
import com.hedera.node.app.service.mono.state.submerkle.TxnId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

public record BBMPayerRecord(TxnId transactionId, RichInstant consensusTime, EntityId payer) {

    public static BBMPayerRecord fromMod(@NonNull TransactionRecordEntry recordEntry) {
        Objects.requireNonNull(recordEntry.transactionRecord(), "Record is null");

        var modTransactionId = recordEntry.transactionRecord().transactionID();
        var accountId = EntityId.fromPbjAccountId(modTransactionId.accountID());
        var validStartTimestamp = modTransactionId.transactionValidStart();
        var txnId = new TxnId(
                accountId,
                new RichInstant(validStartTimestamp.seconds(), validStartTimestamp.nanos()),
                modTransactionId.scheduled(),
                modTransactionId.nonce());
        var consensusTimestamp = recordEntry.transactionRecord().consensusTimestamp();

        return new BBMPayerRecord(
                txnId,
                new RichInstant(consensusTimestamp.seconds(), consensusTimestamp.nanos()),
                EntityId.fromPbjAccountId(recordEntry.payerAccountId()));
    }

    public static BBMPayerRecord fromMono(@NonNull ExpirableTxnRecord record) {
        return new BBMPayerRecord(
                record.getTxnId(), record.getConsensusTime(), record.getTxnId().getPayerAccount());
    }
}
