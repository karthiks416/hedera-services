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

package contract;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static contract.AssociationsXTestConstants.A_TOKEN_ADDRESS;
import static contract.AssociationsXTestConstants.A_TOKEN_ID;
import static contract.AssociationsXTestConstants.B_TOKEN_ADDRESS;
import static contract.AssociationsXTestConstants.B_TOKEN_ID;
import static contract.HtsErc721TransferXTestConstants.APPROVED_ID;
import static contract.HtsErc721TransferXTestConstants.UNAUTHORIZED_SPENDER_ID;
import static contract.MiscClassicTransfersXTestConstants.INITIAL_RECEIVER_AUTO_ASSOCIATIONS;
import static contract.MiscClassicTransfersXTestConstants.NEXT_ENTITY_NUM;
import static contract.XTestConstants.ERC20_TOKEN_ADDRESS;
import static contract.XTestConstants.ERC721_TOKEN_ADDRESS;
import static contract.XTestConstants.ERC721_TOKEN_ID;
import static contract.XTestConstants.OWNER_ADDRESS;
import static contract.XTestConstants.OWNER_HEADLONG_ADDRESS;
import static contract.XTestConstants.OWNER_ID;
import static contract.XTestConstants.RECEIVER_HEADLONG_ADDRESS;
import static contract.XTestConstants.RECEIVER_ID;
import static contract.XTestConstants.SENDER_ADDRESS;
import static contract.XTestConstants.SENDER_BESU_ADDRESS;
import static contract.XTestConstants.SENDER_CONTRACT_ID_KEY;
import static contract.XTestConstants.SENDER_ID;
import static contract.XTestConstants.SN_1234;
import static contract.XTestConstants.SN_1234_METADATA;
import static contract.XTestConstants.SN_2345;
import static contract.XTestConstants.SN_2345_METADATA;
import static contract.XTestConstants.addErc721Relation;
import static contract.XTestConstants.assertSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantrevokekyc.GrantRevokeKycTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator;
import com.hedera.node.app.spi.fixtures.Scenarios;
import java.util.HashMap;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;

/**
 * Exercises grantKyc and revokeKyc on a token via the following steps relative to an {@code OWNER} account:
 * <ol>
 *     <li>Transfer {@code ERC721_TOKEN} serial 1234 from SENDER to RECEIVER.</li>*
 *     <li>Revoke KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#REVOKE_KYC}.</li>
 *     <li>Revoke KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#REVOKE_KYC}. This should fail with status INVALID_ACCOUNT_ID.</li>
 *     <li>Revoke KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#REVOKE_KYC}. This should fail with status INVALID_TOKEN_ID</li>
 *     <li>Transfer {@code ERC721_TOKEN} serial 2345 from SENDER to RECEIVER.  This should fail with code ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN</li>*
 *     <li>Grant KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#GRANT_KYC}.</li>
 *     <li>Grant KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#GRANT_KYC}. This should fail with status INVALID_ACCOUNT_ID.</li>
 *     <li>Grant KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#GRANT_KYC}.This should fail with status INVALID_TOKEN_ID.</li>
 *     <li>Transfer {@code ERC721_TOKEN} serial 2345 from SENDER to RECEIVER.  This should now succeed</li>*
 *     <li>Grant KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#GRANT_KYC}.This should fail with status INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE.</li>
 *     <li>Revoke KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#REVOKE_KYC}.This should fail with status INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE.</li>
 *     <li>Grant KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#GRANT_KYC}.This should fail with status TOKEN_HAS_NO_KYC_KEY.</li>
 *     <li>Revoke KYC {@code ERC721_TOKEN} via {@link GrantRevokeKycTranslator#REVOKE_KYC}.This should fail with status TOKEN_HAS_NO_KYC_KEY.</li>
 * </ol>
 */
public class GrantRevokeKycXTest extends AbstractContractXTest {

    @Override
    protected void doScenarioOperations() {
        // Transfer series 1234 of ERC721_TOKEN to RECEIVER
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(ClassicTransfersTranslator.TRANSFER_NFT
                        .encodeCallWithArgs(
                                ERC721_TOKEN_ADDRESS,
                                OWNER_HEADLONG_ADDRESS,
                                RECEIVER_HEADLONG_ADDRESS,
                                SN_1234.serialNumber())
                        .array()),
                assertSuccess("Should be able to transfer ERC721_TOKEN serial 1234 to RECEIVER"));

        // REVOKE_KYC
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.REVOKE_KYC
                        .encodeCallWithArgs(ERC721_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                assertSuccess("Should be able to revoke KYC"));

        // REVOKE_KYC WITH INVALID ACCOUNT
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.REVOKE_KYC
                        .encodeCallWithArgs(ERC721_TOKEN_ADDRESS, ERC721_TOKEN_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_ACCOUNT_ID).array()),
                        output,
                        "Should not be able to revoke KYC with invalid account"));

        // REVOKE_KYC WITH INVALID TOKEN
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.REVOKE_KYC
                        .encodeCallWithArgs(RECEIVER_HEADLONG_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_TOKEN_ID).array()),
                        output,
                        "Should not be able to revoke KYC with invalid token"));

        // Transfer series 2345 of ERC721_TOKEN to RECEIVER - should fail with ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(ClassicTransfersTranslator.TRANSFER_NFT
                        .encodeCallWithArgs(
                                ERC721_TOKEN_ADDRESS,
                                OWNER_HEADLONG_ADDRESS,
                                RECEIVER_HEADLONG_ADDRESS,
                                SN_2345.serialNumber())
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN)
                                .array()),
                        output,
                        "Transfer w/o KYC granted should fail with ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN"));

        // GRANT_KYC
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.GRANT_KYC
                        .encodeCallWithArgs(ERC721_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                assertSuccess("Should be able to grant KYC"));

        // GRANT_KYC INVALID ACCOUNT
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.GRANT_KYC
                        .encodeCallWithArgs(ERC721_TOKEN_ADDRESS, ERC20_TOKEN_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_ACCOUNT_ID).array()),
                        output,
                        "Should not be able to grant KYC with invalid account"));

        // GRANT_KYC INVALID TOKEN
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.GRANT_KYC
                        .encodeCallWithArgs(RECEIVER_HEADLONG_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_TOKEN_ID).array()),
                        output,
                        "Should not be able to grant KYC with invalid token"));

        // Transfer series 2345 of ERC721_TOKEN to RECEIVER - should succeed now.
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(ClassicTransfersTranslator.TRANSFER_NFT
                        .encodeCallWithArgs(
                                ERC721_TOKEN_ADDRESS,
                                OWNER_HEADLONG_ADDRESS,
                                RECEIVER_HEADLONG_ADDRESS,
                                SN_2345.serialNumber())
                        .array()),
                assertSuccess("Should now be able to transfer ERC721_TOKEN serial 2345 to RECEIVER"));

        // GRANT_KYC with invalid key
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.GRANT_KYC
                        .encodeCallWithArgs(A_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_SIGNATURE).array()),
                        output,
                        "Should not be able to grant KYC with invalid signature"));
        // REVOKE_KYC with invalid key
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.REVOKE_KYC
                        .encodeCallWithArgs(A_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(INVALID_SIGNATURE).array()),
                        output,
                        "Should not be able to revoke KYC with invalid signature"));
        // GRANT_KYC with no kyc key
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.GRANT_KYC
                        .encodeCallWithArgs(B_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(TOKEN_HAS_NO_KYC_KEY).array()),
                        output,
                        "Should not be able to grant KYC with missing signature"));
        // REVOKE_KYC with no kyc key
        runHtsCallAndExpectOnSuccess(
                SENDER_BESU_ADDRESS,
                Bytes.wrap(GrantRevokeKycTranslator.REVOKE_KYC
                        .encodeCallWithArgs(B_TOKEN_ADDRESS, RECEIVER_HEADLONG_ADDRESS)
                        .array()),
                output -> assertEquals(
                        Bytes.wrap(ReturnTypes.encodedRc(TOKEN_HAS_NO_KYC_KEY).array()),
                        output,
                        "Should not be able to revoke KYC with missing signature"));
    }

    @Override
    protected long initialEntityNum() {
        return NEXT_ENTITY_NUM - 1;
    }

    @Override
    protected Map<ProtoBytes, AccountID> initialAliases() {
        final var aliases = withSenderAddress(new HashMap<>());
        aliases.put(ProtoBytes.newBuilder().value(OWNER_ADDRESS).build(), OWNER_ID);
        return aliases;
    }

    @Override
    protected Map<TokenID, Token> initialTokens() {
        final var tokens = new HashMap<TokenID, Token>();
        tokens.put(
                ERC721_TOKEN_ID,
                Token.newBuilder()
                        .tokenId(ERC721_TOKEN_ID)
                        .treasuryAccountId(UNAUTHORIZED_SPENDER_ID)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .kycKey(SENDER_CONTRACT_ID_KEY)
                        .build());
        tokens.put(
                A_TOKEN_ID,
                Token.newBuilder()
                        .tokenId(A_TOKEN_ID)
                        .treasuryAccountId(UNAUTHORIZED_SPENDER_ID)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .kycKey(Scenarios.ALICE.account().key())
                        .build());
        tokens.put(
                B_TOKEN_ID,
                Token.newBuilder()
                        .tokenId(B_TOKEN_ID)
                        .treasuryAccountId(UNAUTHORIZED_SPENDER_ID)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .build());
        return tokens;
    }

    @Override
    protected Map<EntityIDPair, TokenRelation> initialTokenRelationships() {
        final var tokenRelationships = new HashMap<EntityIDPair, TokenRelation>();
        addErc721Relation(tokenRelationships, OWNER_ID, 3L);
        addErc721Relation(tokenRelationships, RECEIVER_ID, 0L);
        return tokenRelationships;
    }

    @Override
    protected Map<NftID, Nft> initialNfts() {
        final var nfts = new HashMap<NftID, Nft>();
        nfts.put(
                SN_1234,
                Nft.newBuilder()
                        .nftId(SN_1234)
                        .ownerId(OWNER_ID)
                        .spenderId(APPROVED_ID)
                        .metadata(SN_1234_METADATA)
                        .build());
        nfts.put(
                SN_2345,
                Nft.newBuilder()
                        .nftId(SN_2345)
                        .ownerId(OWNER_ID)
                        .metadata(SN_2345_METADATA)
                        .build());
        return nfts;
    }

    @Override
    protected Map<AccountID, Account> initialAccounts() {
        final var accounts = new HashMap<AccountID, Account>();
        accounts.put(
                SENDER_ID,
                Account.newBuilder()
                        .accountId(OWNER_ID)
                        .alias(SENDER_ADDRESS)
                        .key(SENDER_CONTRACT_ID_KEY)
                        .smartContract(true)
                        .build());
        accounts.put(
                OWNER_ID,
                Account.newBuilder()
                        .accountId(OWNER_ID)
                        .alias(OWNER_ADDRESS)
                        .key(SENDER_CONTRACT_ID_KEY)
                        .build());
        accounts.put(
                RECEIVER_ID,
                Account.newBuilder()
                        .accountId(RECEIVER_ID)
                        .maxAutoAssociations(INITIAL_RECEIVER_AUTO_ASSOCIATIONS)
                        .build());
        accounts.put(
                UNAUTHORIZED_SPENDER_ID,
                Account.newBuilder().accountId(UNAUTHORIZED_SPENDER_ID).build());
        return accounts;
    }
}
