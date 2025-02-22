/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.approxChangeFromSnapshot;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.getResourcePath;

import com.google.common.io.Files;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;

@HapiTestSuite(fuzzyMatch = true)
@Tag(SMART_CONTRACT)
public class ContractGetBytecodeSuite extends HapiSuite {

    private static final Logger log = LogManager.getLogger(ContractGetBytecodeSuite.class);
    private static final String NON_EXISTING_CONTRACT =
            HapiSpecSetup.getDefaultInstance().invalidContractName();

    public static void main(String... args) {
        new ContractGetBytecodeSuite().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(getByteCodeWorks(), invalidContractFromCostAnswer(), invalidContractFromAnswerOnly());
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @HapiTest
    final HapiSpec getByteCodeWorks() {
        final var contract = "EmptyConstructor";
        final var canonicalUsdFee = 0.05;
        final var canonicalQueryFeeAtActiveRate = new AtomicLong();
        return HapiSpec.defaultHapiSpec("GetByteCodeWorks")
                .given(
                        cryptoCreate(CIVILIAN_PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(contract),
                        contractCreate(contract))
                .when(balanceSnapshot("beforeQuery", CIVILIAN_PAYER))
                .then(
                        withOpContext((spec, opLog) -> {
                            final var getBytecode = getContractBytecode(contract)
                                    .payingWith(CIVILIAN_PAYER)
                                    .saveResultTo("contractByteCode")
                                    .exposingBytecodeTo(bytes -> {
                                        canonicalQueryFeeAtActiveRate.set(
                                                spec.ratesProvider().toTbWithActiveRates((long)
                                                        (canonicalUsdFee * 100 * TINY_PARTS_PER_WHOLE)));
                                        log.info(
                                                "Canoncal tinybar cost at active rate: {}",
                                                canonicalQueryFeeAtActiveRate.get());
                                    });
                            allRunFor(spec, getBytecode);

                            @SuppressWarnings("UnstableApiUsage")
                            final var originalBytecode =
                                    Hex.decode(Files.toByteArray(new File(getResourcePath(contract, ".bin"))));
                            final var actualBytecode = spec.registry().getBytes("contractByteCode");
                            // The original bytecode is modified on deployment
                            final var expectedBytecode =
                                    Arrays.copyOfRange(originalBytecode, 29, originalBytecode.length);
                            Assertions.assertArrayEquals(expectedBytecode, actualBytecode);
                        }),
                        // Wait for the query payment transaction to be handled
                        sleepFor(5_000),
                        sourcing(() -> getAccountBalance(CIVILIAN_PAYER)
                                .hasTinyBars(
                                        // Just sanity-check a fee within 50% of the canonical fee to be safe
                                        approxChangeFromSnapshot(
                                                "beforeQuery",
                                                -canonicalQueryFeeAtActiveRate.get(),
                                                canonicalQueryFeeAtActiveRate.get() / 2))));
    }

    @HapiTest
    final HapiSpec invalidContractFromCostAnswer() {
        return defaultHapiSpec("InvalidContractFromCostAnswer")
                .given()
                .when()
                .then(getContractBytecode(NON_EXISTING_CONTRACT)
                        .hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }

    @HapiTest
    final HapiSpec invalidContractFromAnswerOnly() {
        return defaultHapiSpec("InvalidContractFromAnswerOnly")
                .given()
                .when()
                .then(getContractBytecode(NON_EXISTING_CONTRACT)
                        .nodePayment(27_159_182L)
                        .hasAnswerOnlyPrecheck(ResponseCodeEnum.INVALID_CONTRACT_ID));
    }
}
