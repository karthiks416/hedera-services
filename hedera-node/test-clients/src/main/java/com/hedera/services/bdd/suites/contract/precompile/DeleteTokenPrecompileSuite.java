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

package com.hedera.services.bdd.suites.contract.precompile;

import static com.google.protobuf.ByteString.copyFromUtf8;
import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedAddress;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;

@HapiTestSuite
@Tag(SMART_CONTRACT)
public class DeleteTokenPrecompileSuite extends HapiSuite {

    private static final Logger log = LogManager.getLogger(DeleteTokenPrecompileSuite.class);

    private static final long GAS_TO_OFFER = 4_000_000L;
    public static final String DELETE_TOKEN_CONTRACT = "DeleteTokenContract";
    public static final String TOKEN_DELETE_FUNCTION = "tokenDelete";
    private static final String ACCOUNT = "anybody";
    private static final String MULTI_KEY = "purpose";
    private static final String DELETE_TXN = "deleteTxn";

    public static final String THRESHOLD_KEY = "ThreshKey";
    private static final KeyShape THRESHOLD_KEY_SHAPE = KeyShape.threshOf(1, ED25519, CONTRACT);

    public static void main(String... args) {
        new DeleteTokenPrecompileSuite().runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(deleteFungibleToken(), deleteNftToken());
    }

    @HapiTest
    final HapiSpec deleteFungibleToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final var tokenAlreadyDeletedTxn = "tokenAlreadyDeletedTxn";

        return defaultHapiSpec("deleteFungibleToken")
                .given(
                        newKeyNamed(MULTI_KEY),
                        cryptoCreate(ACCOUNT)
                                .key(MULTI_KEY)
                                .balance(100 * ONE_HBAR)
                                .exposingCreatedIdTo(accountID::set),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(VANILLA_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .adminKey(MULTI_KEY)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id)))
                                .initialSupply(1110),
                        uploadInitCode(DELETE_TOKEN_CONTRACT),
                        contractCreate(DELETE_TOKEN_CONTRACT),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT)))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, DELETE_TOKEN_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).adminKey(THRESHOLD_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        DELETE_TOKEN_CONTRACT,
                                        TOKEN_DELETE_FUNCTION,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .gas(GAS_TO_OFFER)
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(DELETE_TXN),
                        getTokenInfo(VANILLA_TOKEN).isDeleted().logged(),
                        cryptoTransfer(moving(500, VANILLA_TOKEN).between(TOKEN_TREASURY, ACCOUNT))
                                .hasKnownStatus(TOKEN_WAS_DELETED),
                        contractCall(
                                        DELETE_TOKEN_CONTRACT,
                                        TOKEN_DELETE_FUNCTION,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .gas(GAS_TO_OFFER)
                                .via(tokenAlreadyDeletedTxn)
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))))
                .then(childRecordsCheck(
                        tokenAlreadyDeletedTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(TOKEN_WAS_DELETED)
                                .contractCallResult(resultWith()
                                        .contractCallResult(
                                                htsPrecompileResult().withStatus(TOKEN_WAS_DELETED)))));
    }

    @HapiTest
    final HapiSpec deleteNftToken() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final AtomicReference<AccountID> accountID = new AtomicReference<>();
        final var notAnAdminTxn = "notAnAdminTxn";

        return defaultHapiSpec("deleteNftToken")
                .given(
                        newKeyNamed(MULTI_KEY),
                        cryptoCreate(ACCOUNT).balance(100 * ONE_HBAR).exposingCreatedIdTo(accountID::set),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(VANILLA_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .treasury(TOKEN_TREASURY)
                                .adminKey(MULTI_KEY)
                                .supplyKey(MULTI_KEY)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id)))
                                .initialSupply(0),
                        mintToken(VANILLA_TOKEN, List.of(copyFromUtf8("First!"))),
                        uploadInitCode(DELETE_TOKEN_CONTRACT),
                        contractCreate(DELETE_TOKEN_CONTRACT),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        cryptoTransfer(movingUnique(VANILLA_TOKEN, 1L).between(TOKEN_TREASURY, ACCOUNT)))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        DELETE_TOKEN_CONTRACT,
                                        TOKEN_DELETE_FUNCTION,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .gas(GAS_TO_OFFER)
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .via(notAnAdminTxn)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        newKeyNamed(THRESHOLD_KEY)
                                .shape(THRESHOLD_KEY_SHAPE.signedWith(sigs(ON, DELETE_TOKEN_CONTRACT))),
                        tokenUpdate(VANILLA_TOKEN).adminKey(THRESHOLD_KEY),
                        cryptoUpdate(ACCOUNT).key(THRESHOLD_KEY),
                        contractCall(
                                        DELETE_TOKEN_CONTRACT,
                                        TOKEN_DELETE_FUNCTION,
                                        asHeadlongAddress(asHexedAddress(vanillaTokenID.get())))
                                .signedBy(GENESIS, ACCOUNT)
                                .alsoSigningWithFullPrefix(ACCOUNT)
                                .gas(GAS_TO_OFFER),
                        getTokenInfo(VANILLA_TOKEN).isDeleted().logged())))
                .then(childRecordsCheck(
                        notAnAdminTxn,
                        CONTRACT_REVERT_EXECUTED,
                        recordWith()
                                .status(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)
                                .contractCallResult(resultWith()
                                        .contractCallResult(htsPrecompileResult()
                                                .withStatus(INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE)))));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
