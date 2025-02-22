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

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFeeInheritingRoyaltyCollector;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.royaltyFeeWithFallback;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.exposeTargetLedgerIdTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.FULLY_NONDETERMINISTIC;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.HIGHLY_NON_DETERMINISTIC_FEES;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_CONTRACT_CALL_RESULTS;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_FUNCTION_PARAMETERS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FunctionType;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.queries.token.HapiGetTokenInfo;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.utils.contracts.precompile.TokenKeyType;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.Fraction;
import com.hederahashgraph.api.proto.java.FractionalFee;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.RoyaltyFee;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenInfo;
import com.hederahashgraph.api.proto.java.TokenNftInfo;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Tag;

@HapiTestSuite(fuzzyMatch = true)
@Tag(SMART_CONTRACT)
public class TokenInfoHTSSuite extends HapiSuite {

    private static final Logger LOG = LogManager.getLogger(TokenInfoHTSSuite.class);

    private static final String TOKEN_INFO_CONTRACT = "TokenInfoContract";
    private static final String ADMIN_KEY = TokenKeyType.ADMIN_KEY.name();
    private static final String KYC_KEY = TokenKeyType.KYC_KEY.name();
    private static final String SUPPLY_KEY = TokenKeyType.SUPPLY_KEY.name();
    private static final String FREEZE_KEY = TokenKeyType.FREEZE_KEY.name();
    private static final String WIPE_KEY = TokenKeyType.WIPE_KEY.name();
    private static final String FEE_SCHEDULE_KEY = TokenKeyType.FEE_SCHEDULE_KEY.name();
    private static final String PAUSE_KEY = TokenKeyType.PAUSE_KEY.name();
    private static final String AUTO_RENEW_ACCOUNT = "autoRenewAccount";
    private static final String FEE_DENOM = "denom";
    public static final String HTS_COLLECTOR = "denomFee";
    private static final String CREATE_TXN = "CreateTxn";
    private static final String TOKEN_INFO_TXN = "TokenInfoTxn";
    private static final String FUNGIBLE_TOKEN_INFO_TXN = "FungibleTokenInfoTxn";
    private static final String NON_FUNGIBLE_TOKEN_INFO_TXN = "NonFungibleTokenInfoTxn";
    private static final String GET_TOKEN_INFO_TXN = "GetTokenInfo";
    private static final String APPROVE_TXN = "approveTxn";
    private static final String SYMBOL = "T";
    private static final String FUNGIBLE_SYMBOL = "FT";
    private static final String FUNGIBLE_TOKEN_NAME = "FungibleToken";
    private static final String NON_FUNGIBLE_SYMBOL = "NFT";
    private static final String META = "First";
    private static final String MEMO = "JUMP";
    private static final String PRIMARY_TOKEN_NAME = "primary";
    private static final String NFT_OWNER = "NFT Owner";
    private static final String NFT_SPENDER = "NFT Spender";
    private static final String NON_FUNGIBLE_TOKEN_NAME = "NonFungibleToken";
    private static final String GET_INFORMATION_FOR_TOKEN = "getInformationForToken";
    private static final String GET_INFORMATION_FOR_FUNGIBLE_TOKEN = "getInformationForFungibleToken";
    private static final String GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN = "getInformationForNonFungibleToken";

    private static final int NUMERATOR = 1;
    private static final int DENOMINATOR = 2;
    private static final int MINIMUM_TO_COLLECT = 5;
    private static final int MAXIMUM_TO_COLLECT = 400;
    private static final int MAX_SUPPLY = 1000;
    public static final String GET_CUSTOM_FEES_FOR_TOKEN = "getCustomFeesForToken";

    public static void main(final String... args) {
        new TokenInfoHTSSuite().runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return allOf(positiveSpecs(), negativeSpecs());
    }

    List<HapiSpec> negativeSpecs() {
        return List.of(
                getInfoOnDeletedFungibleTokenWorks(),
                getInfoOnInvalidFungibleTokenFails(),
                getInfoOnDeletedNonFungibleTokenWorks(),
                getInfoOnInvalidNonFungibleTokenFails(),
                getInfoForFungibleTokenByNFTTokenAddressWorks(),
                getInfoForNFTByFungibleTokenAddressFails(),
                getInfoForTokenByAccountAddressFails());
    }

    List<HapiSpec> positiveSpecs() {
        return List.of(
                happyPathGetTokenInfo(),
                happyPathGetFungibleTokenInfo(),
                happyPathGetNonFungibleTokenInfo(),
                happyPathGetTokenCustomFees(),
                happyPathGetNonFungibleTokenCustomFees());
    }

    @HapiTest
    final HapiSpec happyPathGetTokenInfo() {
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return defaultHapiSpec(
                        "HappyPathGetTokenInfo",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(HTS_COLLECTOR),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        newKeyNamed(PAUSE_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .symbol(SYMBOL)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(MAX_SUPPLY)
                                .initialSupply(500L)
                                .adminKey(ADMIN_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .wipeKey(WIPE_KEY)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .pauseKey(PAUSE_KEY)
                                .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                                // Include a fractional fee with no minimum to collect
                                .withCustom(fractionalFee(
                                        NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_TREASURY))
                                .via(CREATE_TXN),
                        getTokenInfo(PRIMARY_TOKEN_NAME).via(GET_TOKEN_INFO_TXN))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME))))
                                .via(TOKEN_INFO_TXN)
                                .gas(1_000_000L),
                        contractCallLocal(
                                TOKEN_INFO_CONTRACT,
                                GET_INFORMATION_FOR_TOKEN,
                                HapiParserUtil.asHeadlongAddress(
                                        asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME)))))))
                .then(exposeTargetLedgerIdTo(targetLedgerId::set), withOpContext((spec, opLog) -> {
                    final var getTokenInfoQuery = getTokenInfo(PRIMARY_TOKEN_NAME);
                    allRunFor(spec, getTokenInfoQuery);
                    final var expirySecond = getTokenInfoQuery
                            .getResponse()
                            .getTokenGetInfo()
                            .getTokenInfo()
                            .getExpiry()
                            .getSeconds();

                    allRunFor(
                            spec,
                            getTxnRecord(TOKEN_INFO_TXN).andAllChildRecords().logged(),
                            childRecordsCheck(
                                    TOKEN_INFO_TXN,
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_GET_TOKEN_INFO)
                                                            .withStatus(SUCCESS)
                                                            .withTokenInfo(getTokenInfoStructForFungibleToken(
                                                                    spec,
                                                                    PRIMARY_TOKEN_NAME,
                                                                    SYMBOL,
                                                                    MEMO,
                                                                    spec.registry()
                                                                            .getAccountID(TOKEN_TREASURY),
                                                                    expirySecond,
                                                                    targetLedgerId.get()))))));
                }));
    }

    @HapiTest
    final HapiSpec happyPathGetFungibleTokenInfo() {
        final int decimals = 1;
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return defaultHapiSpec(
                        "HappyPathGetFungibleTokenInfo",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(HTS_COLLECTOR),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        newKeyNamed(PAUSE_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(FUNGIBLE_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(FUNGIBLE_TOKEN_NAME)
                                .symbol(FUNGIBLE_SYMBOL)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(MAX_SUPPLY)
                                .initialSupply(500)
                                .decimals(decimals)
                                .adminKey(ADMIN_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .wipeKey(WIPE_KEY)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .pauseKey(PAUSE_KEY)
                                .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                                // Also include a fractional fee with no minimum to collect
                                .withCustom(fractionalFee(
                                        NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_TREASURY))
                                .via(CREATE_TXN))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN_NAME))))
                                .via(FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L),
                        contractCallLocal(
                                TOKEN_INFO_CONTRACT,
                                GET_INFORMATION_FOR_FUNGIBLE_TOKEN,
                                HapiParserUtil.asHeadlongAddress(
                                        asAddress(spec.registry().getTokenID(FUNGIBLE_TOKEN_NAME)))))))
                .then(exposeTargetLedgerIdTo(targetLedgerId::set), withOpContext((spec, opLog) -> {
                    final var getTokenInfoQuery = getTokenInfo(FUNGIBLE_TOKEN_NAME);
                    allRunFor(spec, getTokenInfoQuery);
                    final var expirySecond = getTokenInfoQuery
                            .getResponse()
                            .getTokenGetInfo()
                            .getTokenInfo()
                            .getExpiry()
                            .getSeconds();

                    allRunFor(
                            spec,
                            childRecordsCheck(
                                    FUNGIBLE_TOKEN_INFO_TXN,
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO)
                                                            .withStatus(SUCCESS)
                                                            .withDecimals(decimals)
                                                            .withTokenInfo(getTokenInfoStructForFungibleToken(
                                                                    spec,
                                                                    FUNGIBLE_TOKEN_NAME,
                                                                    FUNGIBLE_SYMBOL,
                                                                    MEMO,
                                                                    spec.registry()
                                                                            .getAccountID(TOKEN_TREASURY),
                                                                    expirySecond,
                                                                    targetLedgerId.get()))))));
                }));
    }

    @HapiTest
    final HapiSpec happyPathGetNonFungibleTokenInfo() {
        final int maxSupply = 10;
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        final AtomicReference<ByteString> targetLedgerId = new AtomicReference<>();
        return defaultHapiSpec(
                        "HappyPathGetNonFungibleTokenInfo",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(NFT_OWNER),
                        cryptoCreate(NFT_SPENDER),
                        cryptoCreate(HTS_COLLECTOR),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(FREEZE_KEY),
                        newKeyNamed(KYC_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(FEE_SCHEDULE_KEY),
                        newKeyNamed(PAUSE_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                        tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(NON_FUNGIBLE_TOKEN_NAME)
                                .symbol(NON_FUNGIBLE_SYMBOL)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(maxSupply)
                                .initialSupply(0)
                                .adminKey(ADMIN_KEY)
                                .freezeKey(FREEZE_KEY)
                                .kycKey(KYC_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .wipeKey(WIPE_KEY)
                                .feeScheduleKey(FEE_SCHEDULE_KEY)
                                .pauseKey(PAUSE_KEY)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, FEE_DENOM), HTS_COLLECTOR))
                                .via(CREATE_TXN),
                        mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)),
                        tokenAssociate(NFT_OWNER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                        tokenAssociate(NFT_SPENDER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                        grantTokenKyc(NON_FUNGIBLE_TOKEN_NAME, NFT_OWNER),
                        cryptoTransfer(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN_NAME, 1L)
                                .between(TOKEN_TREASURY, NFT_OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(NFT_OWNER, NON_FUNGIBLE_TOKEN_NAME, NFT_SPENDER, false, List.of(1L))
                                .via(APPROVE_TXN)
                                .logged()
                                .signedBy(DEFAULT_PAYER, NFT_OWNER)
                                .fee(ONE_HBAR))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))),
                                        1L)
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L),
                        contractCallLocal(
                                TOKEN_INFO_CONTRACT,
                                GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                HapiParserUtil.asHeadlongAddress(
                                        asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))),
                                1L))))
                .then(exposeTargetLedgerIdTo(targetLedgerId::set), withOpContext((spec, opLog) -> {
                    final var getTokenInfoQuery = getTokenInfo(NON_FUNGIBLE_TOKEN_NAME);
                    allRunFor(spec, getTokenInfoQuery);
                    final var expirySecond = getTokenInfoQuery
                            .getResponse()
                            .getTokenGetInfo()
                            .getTokenInfo()
                            .getExpiry()
                            .getSeconds();

                    final var nftTokenInfo =
                            getTokenNftInfoForCheck(spec, getTokenInfoQuery, meta, targetLedgerId.get());

                    allRunFor(
                            spec,
                            getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                    .andAllChildRecords()
                                    .logged(),
                            childRecordsCheck(
                                    NON_FUNGIBLE_TOKEN_INFO_TXN,
                                    SUCCESS,
                                    recordWith()
                                            .status(SUCCESS)
                                            .contractCallResult(resultWith()
                                                    .contractCallResult(htsPrecompileResult()
                                                            .forFunction(FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO)
                                                            .withStatus(SUCCESS)
                                                            .withTokenInfo(getTokenInfoStructForNonFungibleToken(
                                                                    spec,
                                                                    NON_FUNGIBLE_TOKEN_NAME,
                                                                    NON_FUNGIBLE_SYMBOL,
                                                                    MEMO,
                                                                    spec.registry()
                                                                            .getAccountID(TOKEN_TREASURY),
                                                                    expirySecond,
                                                                    targetLedgerId.get()))
                                                            .withNftTokenInfo(nftTokenInfo)))));
                }));
    }

    @HapiTest
    final HapiSpec getInfoOnDeletedFungibleTokenWorks() {
        return defaultHapiSpec(
                        "getInfoOnDeletedFungibleTokenWorks",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN),
                        tokenDelete(PRIMARY_TOKEN_NAME))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME))))
                                .via(TOKEN_INFO_TXN + 1)
                                .gas(1_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME))))
                                .via(TOKEN_INFO_TXN + 2)
                                .gas(1_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS))))
                .then(
                        getTxnRecord(TOKEN_INFO_TXN + 1).andAllChildRecords().logged(),
                        getTxnRecord(TOKEN_INFO_TXN + 2).andAllChildRecords().logged());
    }

    @HapiTest
    final HapiSpec getInfoOnInvalidFungibleTokenFails() {
        return defaultHapiSpec(
                        "getInfoOnInvalidFungibleTokenFails",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(new byte[20]))
                                .via(TOKEN_INFO_TXN + 1)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(new byte[20]))
                                .via(TOKEN_INFO_TXN + 2)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))))
                .then(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(TOKEN_INFO_TXN + 1).andAllChildRecords().logged(),
                        getTxnRecord(TOKEN_INFO_TXN + 2).andAllChildRecords().logged()

                        //                        ,childRecordsCheck(
                        //                                TOKEN_INFO_TXN + 2,
                        //                                CONTRACT_REVERT_EXECUTED,
                        //                                recordWith()
                        //                                        .status(INVALID_TOKEN_ID)
                        //                                        .contractCallResult(resultWith()
                        //                                                .contractCallResult(htsPrecompileResult()
                        //
                        // .forFunction(FunctionType.HAPI_GET_FUNGIBLE_TOKEN_INFO)
                        //                                                        .withStatus(INVALID_TOKEN_ID)
                        //
                        // .withTokenInfo(getTokenInfoStructForEmptyFungibleToken(
                        //                                                                "",
                        //                                                                "",
                        //                                                                "",
                        //
                        // AccountID.getDefaultInstance(),
                        //                                                                0,
                        //                                                                ByteString.EMPTY
                        //                                                                )))))
                        //                                                ,childRecordsCheck(
                        //                                                        TOKEN_INFO_TXN + 1,
                        //                                                        CONTRACT_REVERT_EXECUTED,
                        //                                                        recordWith()
                        //                                                                .status(INVALID_TOKEN_ID)
                        //
                        // .contractCallResult(resultWith()
                        //
                        // .contractCallResult(htsPrecompileResult()
                        //
                        //                         .forFunction(FunctionType.HAPI_GET_TOKEN_INFO)
                        //
                        // .withStatus(INVALID_TOKEN_ID)
                        //
                        //                         .withTokenInfo(getTokenInfoStructForEmptyFungibleToken(
                        //                                                                                        "",
                        //                                                                                        "",
                        //                                                                                        "",
                        //
                        //                         AccountID.getDefaultInstance(),
                        //                                                                                        0,
                        //
                        // ByteString.EMPTY
                        //                                                                                        )))))

                        )));
    }

    @HapiTest
    final HapiSpec getInfoOnDeletedNonFungibleTokenWorks() {
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        return defaultHapiSpec(
                        "getInfoOnDeletedNonFungibleTokenFails",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(NON_FUNGIBLE_TOKEN_NAME)
                                .symbol(NON_FUNGIBLE_SYMBOL)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(10)
                                .initialSupply(0)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN),
                        mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)),
                        tokenDelete(NON_FUNGIBLE_TOKEN_NAME))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))),
                                        1L)
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS))))
                .then(getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN)
                        .andAllChildRecords()
                        .logged());
    }

    @HapiTest
    final HapiSpec getInfoOnInvalidNonFungibleTokenFails() {
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        return defaultHapiSpec("getInfoOnInvalidNonFungibleTokenFails", FULLY_NONDETERMINISTIC)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(NON_FUNGIBLE_TOKEN_NAME)
                                .symbol("NFT")
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(10)
                                .initialSupply(0)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN),
                        mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(new byte[20]),
                                        1L)
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN + 1)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))),
                                        2L)
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN + 2)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))))
                .then(
                        getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN + 1)
                                .andAllChildRecords()
                                .logged(),
                        //                                                ,childRecordsCheck(
                        //                                NON_FUNGIBLE_TOKEN_INFO_TXN + 1,
                        //                                                        CONTRACT_REVERT_EXECUTED,
                        //                                                        recordWith()
                        //                                                                .status(INVALID_TOKEN_ID)
                        //
                        // .contractCallResult(resultWith()
                        //
                        // .contractCallResult(htsPrecompileResult()
                        //
                        //                         .forFunction(FunctionType.HAPI_GET_NON_FUNGIBLE_TOKEN_INFO)
                        //
                        // .withStatus(INVALID_TOKEN_ID)
                        //
                        //                         .withTokenInfo(getTokenInfoStructForEmptyFungibleToken(
                        //                                                                                        "",
                        //                                                                                        "",
                        //                                                                                        "",
                        //
                        //                         AccountID.getDefaultInstance(),
                        //                                                                                        0,
                        //
                        // ByteString.EMPTY
                        //                                                                                        ))
                        //
                        // .withNftTokenInfo(getEmptyNft())))),
                        getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN + 2)
                                .andAllChildRecords()
                                .logged());
    }

    @HapiTest
    final HapiSpec getInfoForTokenByAccountAddressFails() {
        return defaultHapiSpec("getInfoForTokenByAccountAddressFails")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getAccountID(TOKEN_TREASURY))))
                                .via(TOKEN_INFO_TXN)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))))
                .then(
                        getTxnRecord(TOKEN_INFO_TXN).andAllChildRecords().logged(),
                        childRecordsCheck(
                                TOKEN_INFO_TXN,
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_TOKEN_ID)));
    }

    @HapiTest
    final HapiSpec getInfoForNFTByFungibleTokenAddressFails() {
        return defaultHapiSpec("getInfoForNFTByFungibleTokenAddressFails")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(1000)
                                .initialSupply(500)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_NON_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME))),
                                        1L)
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L)
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED))))
                .then(
                        getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .andAllChildRecords()
                                .logged(),
                        childRecordsCheck(
                                NON_FUNGIBLE_TOKEN_INFO_TXN,
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_TOKEN_NFT_SERIAL_NUMBER)));
    }

    @HapiTest
    // FUTURE: This test ensures matching mono === mod behavior. We should consider revising the behavior of allowing
    // NonFungibleToken to be passed to getInfoForFungibleToken and resulting SUCCESS status.
    final HapiSpec getInfoForFungibleTokenByNFTTokenAddressWorks() {
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        return defaultHapiSpec("getInfoForFungibleTokenByNFTTokenAddressWorks")
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(NON_FUNGIBLE_TOKEN_NAME)
                                .symbol(NON_FUNGIBLE_SYMBOL)
                                .treasury(TOKEN_TREASURY)
                                .autoRenewAccount(AUTO_RENEW_ACCOUNT)
                                .autoRenewPeriod(THREE_MONTHS_IN_SECONDS)
                                .maxSupply(10)
                                .initialSupply(0)
                                .adminKey(ADMIN_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .via(CREATE_TXN),
                        mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_INFORMATION_FOR_FUNGIBLE_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))))
                                .via(FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L))))
                .then(
                        getTxnRecord(FUNGIBLE_TOKEN_INFO_TXN)
                                .andAllChildRecords()
                                .logged(),
                        childRecordsCheck(
                                FUNGIBLE_TOKEN_INFO_TXN, SUCCESS, recordWith().status(SUCCESS)));
    }

    @HapiTest
    final HapiSpec happyPathGetTokenCustomFees() {
        return defaultHapiSpec(
                        "HappyPathGetTokenCustomFees",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(AUTO_RENEW_ACCOUNT).balance(0L),
                        cryptoCreate(HTS_COLLECTOR),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(PRIMARY_TOKEN_NAME)
                                .supplyType(TokenSupplyType.FINITE)
                                .name(PRIMARY_TOKEN_NAME)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(MAX_SUPPLY)
                                .initialSupply(500L)
                                .withCustom(fixedHbarFee(500L, HTS_COLLECTOR))
                                // Include a fractional fee with no minimum to collect
                                .withCustom(fractionalFee(
                                        NUMERATOR, DENOMINATOR * 2L, 0, OptionalLong.empty(), TOKEN_TREASURY))
                                .withCustom(fractionalFee(
                                        NUMERATOR,
                                        DENOMINATOR,
                                        MINIMUM_TO_COLLECT,
                                        OptionalLong.of(MAXIMUM_TO_COLLECT),
                                        TOKEN_TREASURY))
                                .via(CREATE_TXN),
                        getTokenInfo(PRIMARY_TOKEN_NAME).via(GET_TOKEN_INFO_TXN).logged())
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_CUSTOM_FEES_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME))))
                                .via(TOKEN_INFO_TXN)
                                .gas(1_000_000L),
                        //                        , contractCall(
                        //                                TOKEN_INFO_CONTRACT,
                        //                                GET_CUSTOM_FEES_FOR_TOKEN,
                        //                                HapiParserUtil.asHeadlongAddress(new byte[20]))
                        //                                .via("fakeAddressTokenInfo")
                        //                                .gas(1_000_000L)
                        //                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED),
                        contractCallLocal(
                                TOKEN_INFO_CONTRACT,
                                GET_CUSTOM_FEES_FOR_TOKEN,
                                HapiParserUtil.asHeadlongAddress(
                                        asAddress(spec.registry().getTokenID(PRIMARY_TOKEN_NAME)))))))
                .then(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(TOKEN_INFO_TXN).andAllChildRecords().logged(),
                        childRecordsCheck(
                                TOKEN_INFO_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.HAPI_GET_TOKEN_CUSTOM_FEES)
                                                        .withStatus(SUCCESS)
                                                        .withCustomFees(getExpectedCustomFees(spec)))))
                        //                        ,childRecordsCheck(
                        //                                "fakeAddressTokenInfo",
                        //                                CONTRACT_REVERT_EXECUTED,
                        //                                recordWith()
                        //                                        .status(INVALID_TOKEN_ID)
                        //                                        .contractCallResult(resultWith()
                        //                                                .contractCallResult(htsPrecompileResult()
                        //
                        // .forFunction(FunctionType.HAPI_GET_TOKEN_CUSTOM_FEES)
                        //                                                        .withStatus(INVALID_TOKEN_ID)
                        //                                                        .withCustomFees(new ArrayList<>()))))
                        )));
    }

    @HapiTest
    final HapiSpec happyPathGetNonFungibleTokenCustomFees() {
        final int maxSupply = 10;
        final ByteString meta = ByteString.copyFrom(META.getBytes(StandardCharsets.UTF_8));
        return defaultHapiSpec(
                        "HappyPathGetNonFungibleTokenCustomFees",
                        HIGHLY_NON_DETERMINISTIC_FEES,
                        NONDETERMINISTIC_FUNCTION_PARAMETERS,
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS)
                .given(
                        cryptoCreate(TOKEN_TREASURY).balance(0L),
                        cryptoCreate(NFT_OWNER),
                        cryptoCreate(NFT_SPENDER),
                        cryptoCreate(HTS_COLLECTOR),
                        newKeyNamed(SUPPLY_KEY),
                        uploadInitCode(TOKEN_INFO_CONTRACT),
                        contractCreate(TOKEN_INFO_CONTRACT).gas(1_000_000L),
                        tokenCreate(FEE_DENOM).treasury(HTS_COLLECTOR),
                        tokenCreate(NON_FUNGIBLE_TOKEN_NAME)
                                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                                .supplyType(TokenSupplyType.FINITE)
                                .entityMemo(MEMO)
                                .name(NON_FUNGIBLE_TOKEN_NAME)
                                .symbol(NON_FUNGIBLE_SYMBOL)
                                .treasury(TOKEN_TREASURY)
                                .maxSupply(maxSupply)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .withCustom(royaltyFeeWithFallback(
                                        1, 2, fixedHtsFeeInheritingRoyaltyCollector(100, FEE_DENOM), HTS_COLLECTOR))
                                .via(CREATE_TXN),
                        mintToken(NON_FUNGIBLE_TOKEN_NAME, List.of(meta)),
                        tokenAssociate(NFT_OWNER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                        tokenAssociate(NFT_SPENDER, List.of(NON_FUNGIBLE_TOKEN_NAME)),
                        cryptoTransfer(TokenMovement.movingUnique(NON_FUNGIBLE_TOKEN_NAME, 1L)
                                .between(TOKEN_TREASURY, NFT_OWNER)),
                        cryptoApproveAllowance()
                                .payingWith(DEFAULT_PAYER)
                                .addNftAllowance(NFT_OWNER, NON_FUNGIBLE_TOKEN_NAME, NFT_SPENDER, false, List.of(1L))
                                .via(APPROVE_TXN)
                                .logged()
                                .signedBy(DEFAULT_PAYER, NFT_OWNER)
                                .fee(ONE_HBAR))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_INFO_CONTRACT,
                                        GET_CUSTOM_FEES_FOR_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(
                                                asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME))))
                                .via(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .gas(1_000_000L),
                        contractCallLocal(
                                TOKEN_INFO_CONTRACT,
                                GET_CUSTOM_FEES_FOR_TOKEN,
                                HapiParserUtil.asHeadlongAddress(
                                        asAddress(spec.registry().getTokenID(NON_FUNGIBLE_TOKEN_NAME)))))))
                .then(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        getTxnRecord(NON_FUNGIBLE_TOKEN_INFO_TXN)
                                .andAllChildRecords()
                                .logged(),
                        childRecordsCheck(
                                NON_FUNGIBLE_TOKEN_INFO_TXN,
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(FunctionType.HAPI_GET_TOKEN_CUSTOM_FEES)
                                                        .withStatus(SUCCESS)
                                                        .withCustomFees(getCustomFeeForNFT(spec))))))));
    }

    private TokenNftInfo getTokenNftInfoForCheck(
            final HapiSpec spec, final HapiGetTokenInfo getTokenInfoQuery, final ByteString meta, ByteString ledgerId) {
        final var tokenId =
                getTokenInfoQuery.getResponse().getTokenGetInfo().getTokenInfo().getTokenId();

        final var getNftTokenInfoQuery = getTokenNftInfo(NON_FUNGIBLE_TOKEN_NAME, 1L);
        allRunFor(spec, getNftTokenInfoQuery);
        final var creationTime =
                getNftTokenInfoQuery.getResponse().getTokenGetNftInfo().getNft().getCreationTime();

        final var ownerId = spec.registry().getAccountID(NFT_OWNER);
        final var spenderId = spec.registry().getAccountID(NFT_SPENDER);

        return TokenNftInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setNftID(NftID.newBuilder()
                        .setTokenID(tokenId)
                        .setSerialNumber(1L)
                        .build())
                .setAccountID(ownerId)
                .setCreationTime(creationTime)
                .setMetadata(meta)
                .setSpenderId(spenderId)
                .build();
    }

    private TokenNftInfo getEmptyNft() {
        return TokenNftInfo.newBuilder()
                .setLedgerId(ByteString.empty())
                .setNftID(NftID.getDefaultInstance())
                .setAccountID(AccountID.getDefaultInstance())
                .setCreationTime(Timestamp.newBuilder().build())
                .setMetadata(ByteString.empty())
                .setSpenderId(AccountID.getDefaultInstance())
                .build();
    }

    private TokenInfo getTokenInfoStructForFungibleToken(
            final HapiSpec spec,
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final long expirySecond,
            ByteString ledgerId) {
        final var autoRenewAccount = spec.registry().getAccountID(AUTO_RENEW_ACCOUNT);

        final ArrayList<CustomFee> customFees = getExpectedCustomFees(spec);

        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setSupplyTypeValue(TokenSupplyType.FINITE_VALUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                .setAutoRenewAccount(autoRenewAccount)
                .setAutoRenewPeriod(Duration.newBuilder()
                        .setSeconds(THREE_MONTHS_IN_SECONDS)
                        .build())
                .setSymbol(symbol)
                .setName(tokenName)
                .setMemo(memo)
                .setTreasury(treasury)
                .setTotalSupply(500L)
                .setMaxSupply(MAX_SUPPLY)
                .addAllCustomFees(customFees)
                .setAdminKey(getTokenKeyFromSpec(spec, TokenKeyType.ADMIN_KEY))
                .setKycKey(getTokenKeyFromSpec(spec, TokenKeyType.KYC_KEY))
                .setFreezeKey(getTokenKeyFromSpec(spec, TokenKeyType.FREEZE_KEY))
                .setWipeKey(getTokenKeyFromSpec(spec, TokenKeyType.WIPE_KEY))
                .setSupplyKey(getTokenKeyFromSpec(spec, TokenKeyType.SUPPLY_KEY))
                .setFeeScheduleKey(getTokenKeyFromSpec(spec, TokenKeyType.FEE_SCHEDULE_KEY))
                .setPauseKey(getTokenKeyFromSpec(spec, TokenKeyType.PAUSE_KEY))
                .build();
    }

    private TokenInfo getTokenInfoStructForEmptyFungibleToken(
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final long expirySecond,
            ByteString ledgerId) {

        final ArrayList<CustomFee> customFees = new ArrayList<>();

        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setSupplyTypeValue(0)
                .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                .setAutoRenewAccount(AccountID.getDefaultInstance())
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(0).build())
                .setSymbol(symbol)
                .setName(tokenName)
                .setMemo(memo)
                .setTreasury(treasury)
                .setTotalSupply(0)
                .setMaxSupply(0)
                .addAllCustomFees(customFees)
                .setAdminKey(Key.newBuilder().build())
                .setKycKey(Key.newBuilder().build())
                .setFreezeKey(Key.newBuilder().build())
                .setWipeKey(Key.newBuilder().build())
                .setSupplyKey(Key.newBuilder().build())
                .setFeeScheduleKey(Key.newBuilder().build())
                .setPauseKey(Key.newBuilder().build())
                .build();
    }

    @NonNull
    private ArrayList<CustomFee> getExpectedCustomFees(final HapiSpec spec) {
        final var fixedFee = FixedFee.newBuilder().setAmount(500L).build();
        final var customFixedFee = CustomFee.newBuilder()
                .setFixedFee(fixedFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(HTS_COLLECTOR))
                .build();

        final var firstFraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR * 2L)
                .build();
        final var firstFractionalFee =
                FractionalFee.newBuilder().setFractionalAmount(firstFraction).build();
        final var firstCustomFractionalFee = CustomFee.newBuilder()
                .setFractionalFee(firstFractionalFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                .build();

        final var fraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR)
                .build();
        final var fractionalFee = FractionalFee.newBuilder()
                .setFractionalAmount(fraction)
                .setMinimumAmount(MINIMUM_TO_COLLECT)
                .setMaximumAmount(MAXIMUM_TO_COLLECT)
                .build();
        final var customFractionalFee = CustomFee.newBuilder()
                .setFractionalFee(fractionalFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(TOKEN_TREASURY))
                .build();

        final var customFees = new ArrayList<CustomFee>();
        customFees.add(customFixedFee);
        customFees.add(firstCustomFractionalFee);
        customFees.add(customFractionalFee);
        return customFees;
    }

    private TokenInfo getTokenInfoStructForNonFungibleToken(
            final HapiSpec spec,
            final String tokenName,
            final String symbol,
            final String memo,
            final AccountID treasury,
            final long expirySecond,
            final ByteString ledgerId) {
        final var autoRenewAccount = spec.registry().getAccountID(AUTO_RENEW_ACCOUNT);

        return TokenInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setSupplyTypeValue(TokenSupplyType.FINITE_VALUE)
                .setExpiry(Timestamp.newBuilder().setSeconds(expirySecond))
                .setAutoRenewAccount(autoRenewAccount)
                .setAutoRenewPeriod(Duration.newBuilder()
                        .setSeconds(THREE_MONTHS_IN_SECONDS)
                        .build())
                .setSymbol(symbol)
                .setName(tokenName)
                .setMemo(memo)
                .setTreasury(treasury)
                .setTotalSupply(1L)
                .setMaxSupply(10L)
                .addAllCustomFees(getCustomFeeForNFT(spec))
                .setAdminKey(getTokenKeyFromSpec(spec, TokenKeyType.ADMIN_KEY))
                .setKycKey(getTokenKeyFromSpec(spec, TokenKeyType.KYC_KEY))
                .setFreezeKey(getTokenKeyFromSpec(spec, TokenKeyType.FREEZE_KEY))
                .setWipeKey(getTokenKeyFromSpec(spec, TokenKeyType.WIPE_KEY))
                .setSupplyKey(getTokenKeyFromSpec(spec, TokenKeyType.SUPPLY_KEY))
                .setFeeScheduleKey(getTokenKeyFromSpec(spec, TokenKeyType.FEE_SCHEDULE_KEY))
                .setPauseKey(getTokenKeyFromSpec(spec, TokenKeyType.PAUSE_KEY))
                .build();
    }

    @NonNull
    private ArrayList<CustomFee> getCustomFeeForNFT(final HapiSpec spec) {
        final var fraction = Fraction.newBuilder()
                .setNumerator(NUMERATOR)
                .setDenominator(DENOMINATOR)
                .build();
        final var fallbackFee = FixedFee.newBuilder()
                .setAmount(100L)
                .setDenominatingTokenId(spec.registry().getTokenID(FEE_DENOM))
                .build();
        final var royaltyFee = RoyaltyFee.newBuilder()
                .setExchangeValueFraction(fraction)
                .setFallbackFee(fallbackFee)
                .build();

        final var customRoyaltyFee = CustomFee.newBuilder()
                .setRoyaltyFee(royaltyFee)
                .setFeeCollectorAccountId(spec.registry().getAccountID(HTS_COLLECTOR))
                .build();

        final var customFees = new ArrayList<CustomFee>();
        customFees.add(customRoyaltyFee);

        return customFees;
    }

    private Key getTokenKeyFromSpec(final HapiSpec spec, final TokenKeyType type) {
        final var key = spec.registry().getKey(type.name());

        final var keyBuilder = Key.newBuilder();

        if (key.getContractID().getContractNum() > 0) {
            keyBuilder.setContractID(key.getContractID());
        }
        if (key.getEd25519().toByteArray().length > 0) {
            keyBuilder.setEd25519(key.getEd25519());
        }
        if (key.getECDSASecp256K1().toByteArray().length > 0) {
            keyBuilder.setECDSASecp256K1(key.getECDSASecp256K1());
        }
        if (key.getDelegatableContractId().getContractNum() > 0) {
            keyBuilder.setDelegatableContractId(key.getDelegatableContractId());
        }

        return keyBuilder.build();
    }

    private ByteString fromString(final String value) {
        return ByteString.copyFrom(Bytes.fromHexString(value).toArray());
    }

    @Override
    protected Logger getResultsLogger() {
        return LOG;
    }
}
