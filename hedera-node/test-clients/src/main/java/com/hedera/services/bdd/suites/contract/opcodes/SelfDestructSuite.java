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

package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_LOG_DATA;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_TRANSACTION_FEES;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.existingSystemAccounts;
import static com.hedera.services.bdd.suites.contract.evm.Evm46ValidationSuite.nonExistingSystemAccounts;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.LOCAL_CALL_MODIFICATION_EXCEPTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;

@HapiTestSuite(fuzzyMatch = true)
@Tag(SMART_CONTRACT)
public class SelfDestructSuite extends HapiSuite {

    private final Logger LOGGER = LogManager.getLogger(SelfDestructSuite.class);

    private static final String SELF_DESTRUCT_CALLABLE_CONTRACT = "SelfDestructCallable";
    private static final String DESTROY_EXPLICIT_BENEFICIARY = "destroyExplicitBeneficiary";
    private static final String BENEFICIARY = "beneficiary";

    public static void main(String... args) {
        new SelfDestructSuite().runSuiteAsync();
    }

    @Override
    protected Logger getResultsLogger() {
        return LOGGER;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(
                hscsEvm008SelfDestructInConstructorWorks(),
                hscsEvm008SelfDestructWhenCalling(),
                selfDestructFailsWhenBeneficiaryHasReceiverSigRequiredAndHasNotSignedTheTxn(),
                selfDestructViaCallLocalWithAccount999ResultsInLocalCallModificationPrecheckFailed(),
                testSelfDestructForSystemAccounts());
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @HapiTest
    final HapiSpec hscsEvm008SelfDestructInConstructorWorks() {
        final var contract = "FactorySelfDestructConstructor";
        final var nextAccount = "civilian";
        return defaultHapiSpec("hscsEvm008SelfDestructInConstructorWorks", NONDETERMINISTIC_LOG_DATA)
                .given(cryptoCreate(BENEFICIARY).balance(ONE_HUNDRED_HBARS), uploadInitCode(contract))
                .when(
                        contractCreate(contract)
                                .balance(3 * ONE_HBAR)
                                .via("contractCreate")
                                .payingWith(BENEFICIARY),
                        cryptoCreate(nextAccount))
                .then(
                        getAccountInfo(contract).hasCostAnswerPrecheck(ACCOUNT_DELETED),
                        getContractInfo(contract).has(contractWith().isDeleted()),
                        withOpContext((spec, opLog) -> {
                            final var registry = spec.registry();
                            final var destroyedNum =
                                    registry.getContractId(contract).getContractNum();
                            final var childInfoQuery = getContractInfo("0.0." + (destroyedNum + 1))
                                    .has(contractWith().isNotDeleted());
                            allRunFor(spec, childInfoQuery);
                        }));
    }

    @HapiTest
    final HapiSpec hscsEvm008SelfDestructWhenCalling() {
        return defaultHapiSpec("hscsEvm008SelfDestructWhenCalling", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        cryptoCreate("acc").balance(5 * ONE_HUNDRED_HBARS),
                        uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT))
                .when(contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT)
                        .via("cc")
                        .payingWith("acc")
                        .hasKnownStatus(SUCCESS))
                .then(
                        contractCall(SELF_DESTRUCT_CALLABLE_CONTRACT, "destroy").payingWith("acc"),
                        getAccountInfo(SELF_DESTRUCT_CALLABLE_CONTRACT).hasCostAnswerPrecheck(ACCOUNT_DELETED),
                        getContractInfo(SELF_DESTRUCT_CALLABLE_CONTRACT)
                                .has(contractWith().isDeleted()));
    }

    @HapiTest
    final HapiSpec selfDestructFailsWhenBeneficiaryHasReceiverSigRequiredAndHasNotSignedTheTxn() {
        final AtomicLong beneficiaryId = new AtomicLong();
        return defaultHapiSpec("selfDestructFailsWhenBeneficiaryHasReceiverSigRequiredAndHasNotSignedTheTxn")
                .given(
                        cryptoCreate(BENEFICIARY)
                                .balance(ONE_HUNDRED_HBARS)
                                .receiverSigRequired(true)
                                .exposingCreatedIdTo(id -> beneficiaryId.set(id.getAccountNum())),
                        uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT),
                        contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT).balance(ONE_HBAR))
                .when(sourcing(() -> contractCall(
                                SELF_DESTRUCT_CALLABLE_CONTRACT,
                                "destroyExplicitBeneficiary",
                                mirrorAddrWith(beneficiaryId.get()))
                        .hasKnownStatus(INVALID_SIGNATURE)))
                .then(
                        getAccountInfo(BENEFICIARY).has(accountWith().balance(ONE_HUNDRED_HBARS)),
                        getContractInfo(SELF_DESTRUCT_CALLABLE_CONTRACT)
                                .has(contractWith().balance(ONE_HBAR)));
    }

    @HapiTest
    final HapiSpec selfDestructViaCallLocalWithAccount999ResultsInLocalCallModificationPrecheckFailed() {
        return defaultHapiSpec("selfDestructViaCallLocalWithAccount999ResultsInLocalCallModificationPrecheckFailed")
                .given(
                        uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT),
                        contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT).balance(ONE_HBAR))
                .when(contractCallLocal(
                                SELF_DESTRUCT_CALLABLE_CONTRACT, "destroyExplicitBeneficiary", mirrorAddrWith(999L))
                        .hasAnswerOnlyPrecheck(LOCAL_CALL_MODIFICATION_EXCEPTION))
                .then();
    }

    @HapiTest
    final HapiSpec testSelfDestructForSystemAccounts() {
        final AtomicLong deployer = new AtomicLong();
        final var nonExistingAccountsOps = createOpsArray(
                nonExistingSystemAccounts,
                SELF_DESTRUCT_CALLABLE_CONTRACT,
                DESTROY_EXPLICIT_BENEFICIARY,
                INVALID_SOLIDITY_ADDRESS);
        final var existingAccountsOps = createOpsArray(
                existingSystemAccounts, SELF_DESTRUCT_CALLABLE_CONTRACT, DESTROY_EXPLICIT_BENEFICIARY, SUCCESS);
        final var opsArray = new HapiSpecOperation[nonExistingAccountsOps.length + existingAccountsOps.length];

        System.arraycopy(nonExistingAccountsOps, 0, opsArray, 0, nonExistingAccountsOps.length);
        System.arraycopy(existingAccountsOps, 0, opsArray, nonExistingAccountsOps.length, existingAccountsOps.length);

        return defaultHapiSpec("testSelfDestructForSystemAccounts")
                .given(
                        cryptoCreate(BENEFICIARY)
                                .balance(ONE_HUNDRED_HBARS)
                                .receiverSigRequired(false)
                                .exposingCreatedIdTo(id -> deployer.set(id.getAccountNum())),
                        uploadInitCode(SELF_DESTRUCT_CALLABLE_CONTRACT),
                        contractCreate(SELF_DESTRUCT_CALLABLE_CONTRACT).balance(ONE_HBAR))
                .when()
                .then(nonExistingAccountsOps);
    }

    private HapiSpecOperation[] createOpsArray(
            List<Long> accounts, String contract, String methodName, ResponseCodeEnum status) {
        HapiSpecOperation[] opsArray = new HapiSpecOperation[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            opsArray[i] = contractCall(contract, methodName, mirrorAddrWith(accounts.get(i)))
                    .hasKnownStatus(status);
        }
        return opsArray;
    }
}
