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

package com.swirlds.platform.crypto;

import static com.swirlds.common.utility.CommonUtils.nameToAlias;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.crypto.CryptoConstants.PUBLIC_KEYS_FILE;
import static com.swirlds.platform.crypto.CryptoStatic.copyPublicKeys;
import static com.swirlds.platform.crypto.CryptoStatic.createEmptyTrustStore;
import static com.swirlds.platform.crypto.CryptoStatic.loadKeys;
import static com.swirlds.platform.state.address.AddressBookNetworkUtils.isLocal;

import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.platform.NodeId;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.operator.jcajce.JceInputDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.util.encoders.DecoderException;

/**
 * This class is responsible for loading the key stores for all nodes in the address book.
 *
 * <p>
 * The {@link EnhancedKeyStoreLoader} class is a replacement for the now deprecated
 * {@link CryptoStatic#loadKeysAndCerts(AddressBook, Path, char[])} method. This new implementation adds support
 * for loading industry standard PEM formatted PKCS #8 private keys and X.509 certificates. The legacy key stores are
 * still supported, but are no longer the preferred format.
 *
 * <p>
 * This implementation will attempt to load the private key stores in the following order:
 *     <ol>
 *         <li>Enhanced private key store ({@code [type]-private-[alias].pem})</li>
 *         <li>Legacy private key store ({@code private-[alias].pfx})</li>
 *     </ol>
 * <p>
 *     Public key stores are loaded in the following order:
 *     <ol>
 *          <li>Enhanced certificate store ({@code [type]-public-[alias].pem})</li>
 *          <li>Legacy certificate store ({@code public.pfx})</li>
 *     </ol>
 */
public class EnhancedKeyStoreLoader {
    /**
     * The constant message to use when the {@code nodeId} required parameter is {@code null}.
     */
    private static final String MSG_NODE_ID_NON_NULL = "nodeId must not be null";

    /**
     * The constant message to use when the {@code nodeAlias} required parameter is {@code null}.
     */
    private static final String MSG_NODE_ALIAS_NON_NULL = "nodeAlias must not be null";

    /**
     * The constant message to use when the {@code purpose} required parameter is {@code null}.
     */
    private static final String MSG_PURPOSE_NON_NULL = "purpose must not be null";

    /**
     * The constant message to use when the {@code legacyPublicStore} required parameter is {@code null}.
     */
    private static final String MSG_LEGACY_PUBLIC_STORE_NON_NULL = "legacyPublicStore must not be null";

    /**
     * The constant message to use when the {@code location} required parameter is {@code null}.
     */
    private static final String MSG_LOCATION_NON_NULL = "location must not be null";

    /**
     * The constant message to use when the {@code entryName} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_NAME_NON_NULL = "entryName must not be null";

    /**
     * The constant message to use when the {@code entryType} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_TYPE_NON_NULL = "entryType must not be null";

    /**
     * The constant message to use when the {@code entry} required parameter is {@code null}.
     */
    private static final String MSG_ENTRY_NON_NULL = "entry must not be null";

    /**
     * The constant message to use when the {@code addressBook} required parameter is {@code null}.
     */
    private static final String MSG_ADDRESS_BOOK_NON_NULL = "addressBook must not be null";

    /**
     * The constant message to use when the {@code function} required parameter is {@code null}.
     */
    private static final String MSG_FUNCTION_NON_NULL = "function must not be null";

    /**
     * The constant message to use when the {@code keyStoreDirectory} required parameter is {@code null}.
     */
    private static final String MSG_KEY_STORE_DIRECTORY_NON_NULL = "keyStoreDirectory must not be null";

    /**
     * The constant message to use when the {@code keyStorePassphrase} required parameter is {@code null}.
     */
    private static final String MSG_KEY_STORE_PASSPHRASE_NON_NULL = "keyStorePassphrase must not be null";

    /**
     * The Log4j2 logger instance to use for all logging.
     */
    private static final Logger logger = LogManager.getLogger(EnhancedKeyStoreLoader.class);

    /**
     * The address book to use for loading the key stores.
     */
    private final AddressBook addressBook;

    /**
     * The absolute path to the key store directory.
     */
    private final Path keyStoreDirectory;

    /**
     * The passphrase used to protect the key stores.
     */
    private final char[] keyStorePassphrase;

    /**
     * The private keys loaded from the key stores.
     */
    private final Map<NodeId, PrivateKey> sigPrivateKeys;

    /**
     * The X.509 Certificates loaded from the key stores.
     */
    private final Map<NodeId, Certificate> sigCertificates;

    /**
     * The private keys loaded from the key stores.
     */
    private final Map<NodeId, PrivateKey> agrPrivateKeys;

    /**
     * The X.509 Certificates loaded from the key stores.
     */
    private final Map<NodeId, Certificate> agrCertificates;

    /**
     * The list of {@link NodeId}s which must have a private key loaded.
     */
    private final Set<NodeId> localNodes;

    /*
     * Static initializer to ensure the Bouncy Castle security provider is registered.
     */
    static {
        if (Arrays.stream(Security.getProviders()).noneMatch(p -> p instanceof BouncyCastleProvider)) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * Constructs a new {@link EnhancedKeyStoreLoader} instance. Intentionally private to prevent direct instantiation.
     * Use the {@link #using(AddressBook, Configuration)} method to create a new instance.
     *
     * @param addressBook        the address book to use for loading the key stores.
     * @param keyStoreDirectory  the absolute path to the key store directory.
     * @param keyStorePassphrase the passphrase used to protect the key stores.
     * @throws NullPointerException if {@code addressBook} or {@code configuration} is {@code null}.
     */
    private EnhancedKeyStoreLoader(
            @NonNull final AddressBook addressBook,
            @NonNull final Path keyStoreDirectory,
            @NonNull final char[] keyStorePassphrase) {
        this.addressBook = Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        this.keyStoreDirectory = Objects.requireNonNull(keyStoreDirectory, MSG_KEY_STORE_DIRECTORY_NON_NULL);
        this.keyStorePassphrase = Objects.requireNonNull(keyStorePassphrase, MSG_KEY_STORE_PASSPHRASE_NON_NULL);
        this.sigPrivateKeys = HashMap.newHashMap(addressBook.getSize());
        this.sigCertificates = HashMap.newHashMap(addressBook.getSize());
        this.agrPrivateKeys = HashMap.newHashMap(addressBook.getSize());
        this.agrCertificates = HashMap.newHashMap(addressBook.getSize());
        this.localNodes = HashSet.newHashSet(addressBook.getSize());
    }

    /**
     * Creates a new {@link EnhancedKeyStoreLoader} instance using the provided {@code addressBook} and {@code configuration}.
     *
     * @param addressBook   the address book to use for loading the key stores.
     * @param configuration the configuration to use for loading the key stores.
     * @return a new {@link EnhancedKeyStoreLoader} instance.
     * @throws NullPointerException     if {@code addressBook} or {@code configuration} is {@code null}.
     * @throws IllegalArgumentException if the value from the configuration element {@code crypto.keystorePassword} is {@code null} or blank.
     */
    @NonNull
    public static EnhancedKeyStoreLoader using(
            @NonNull final AddressBook addressBook, @NonNull final Configuration configuration) {
        Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        Objects.requireNonNull(configuration, "configuration must not be null");

        final String keyStorePassphrase =
                configuration.getConfigData(CryptoConfig.class).keystorePassword();
        final Path keyStoreDirectory =
                configuration.getConfigData(PathsConfig.class).getKeysDirPath();

        if (keyStorePassphrase == null || keyStorePassphrase.isBlank()) {
            throw new IllegalArgumentException("keyStorePassphrase must not be null or blank");
        }

        return new EnhancedKeyStoreLoader(addressBook, keyStoreDirectory, keyStorePassphrase.toCharArray());
    }

    /**
     * Scan the directory specified by {@code paths.keyDirPath} configuration element for key stores. This method will
     * process and load keys found in both the legacy or enhanced formats.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     */
    @NonNull
    public EnhancedKeyStoreLoader scan() throws KeyLoadingException, KeyStoreException {
        logger.debug(STARTUP.getMarker(), "Starting key store enumeration");
        final KeyStore legacyPublicStore = resolveLegacyPublicStore();

        iterateAddressBook(addressBook, (i, nodeId, address, nodeAlias) -> {
            logger.debug(
                    STARTUP.getMarker(),
                    "Attempting to locate key stores for node {} [ id = {}, alias = {} ]",
                    i,
                    nodeId,
                    nodeAlias);

            if (isLocal(address)) {
                localNodes.add(nodeId);
                sigPrivateKeys.compute(
                        nodeId, (k, v) -> resolveNodePrivateKey(nodeId, nodeAlias, KeyCertPurpose.SIGNING));
                agrPrivateKeys.compute(
                        nodeId, (k, v) -> resolveNodePrivateKey(nodeId, nodeAlias, KeyCertPurpose.AGREEMENT));
            }

            sigCertificates.compute(
                    nodeId,
                    (k, v) -> resolveNodeCertificate(nodeId, nodeAlias, KeyCertPurpose.SIGNING, legacyPublicStore));
            agrCertificates.compute(
                    nodeId,
                    (k, v) -> resolveNodeCertificate(nodeId, nodeAlias, KeyCertPurpose.AGREEMENT, legacyPublicStore));
        });

        logger.trace(STARTUP.getMarker(), "Completed key store enumeration");
        return this;
    }

    /**
     * Verifies the presence of all required keys based on the address book provided during initialization.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyLoadingException if one or more of the required keys were not loaded.
     */
    @NonNull
    public EnhancedKeyStoreLoader verify() throws KeyLoadingException, KeyStoreException {
        return verify(addressBook);
    }

    /**
     * Verifies the presence of all required keys based on the supplied address book.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not initialized.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public EnhancedKeyStoreLoader verify(@NonNull final AddressBook validatingBook)
            throws KeyLoadingException, KeyStoreException {
        Objects.requireNonNull(validatingBook, "validatingBook must not be null");

        if (addressBook.getSize() != validatingBook.getSize()) {
            throw new KeyLoadingException(
                    "The validating address book size differs from the address book used during initialization [ validatingSize = %d, initializedSize = %d ]"
                            .formatted(validatingBook.getSize(), addressBook.getSize()));
        }

        iterateAddressBook(validatingBook, (i, nodeId, address, nodeAlias) -> {
            if (isLocal(address)) {
                if (!localNodes.contains(nodeId)) {
                    throw new KeyLoadingException(
                            "No private key found for local node %s [ alias = %s ]".formatted(nodeId, nodeAlias));
                }

                if (!sigPrivateKeys.containsKey(nodeId)) {
                    throw new KeyLoadingException("No private key found for node %s [ alias = %s, purpose = %s ]"
                            .formatted(nodeId, nodeAlias, KeyCertPurpose.SIGNING));
                }

                if (!agrPrivateKeys.containsKey(nodeId)) {
                    throw new KeyLoadingException("No private key found for node %s [ alias = %s, purpose = %s ]"
                            .formatted(nodeId, nodeAlias, KeyCertPurpose.AGREEMENT));
                }
            }

            if (!sigCertificates.containsKey(nodeId)) {
                throw new KeyLoadingException("No certificate found for node %s [ alias = %s, purpose = %s ]"
                        .formatted(nodeId, nodeAlias, KeyCertPurpose.SIGNING));
            }

            if (!agrCertificates.containsKey(nodeId)) {
                throw new KeyLoadingException("No certificate found for node %s [ alias = %s, purpose = %s ]"
                        .formatted(nodeId, nodeAlias, KeyCertPurpose.AGREEMENT));
            }
        });

        return this;
    }

    /**
     * Creates a map containing the private keys for all local nodes and the public keys for all nodes using the
     * supplied address book.
     *
     * @return the map of all keys and certificates per {@link NodeId}.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException if one or more of the required keys were not loaded or are not of the correct type.
     */
    @NonNull
    public Map<NodeId, KeysAndCerts> keysAndCerts() throws KeyStoreException, KeyLoadingException {
        return keysAndCerts(addressBook);
    }

    /**
     * Creates a map containing the private keys for all local nodes and the public keys for all nodes using the
     * supplied address book.
     *
     * @return the map of all keys and certificates per {@link NodeId}.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public Map<NodeId, KeysAndCerts> keysAndCerts(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        Objects.requireNonNull(validatingBook, "validatingBook must not be null");

        final Map<NodeId, KeysAndCerts> keysAndCerts = HashMap.newHashMap(validatingBook.getSize());
        final PublicStores publicStores = publicStores(validatingBook);

        iterateAddressBook(validatingBook, (i, nodeId, address, nodeAlias) -> {
            final X509Certificate sigCert = publicStores.getCertificate(KeyCertPurpose.SIGNING, nodeAlias);
            final X509Certificate agrCert = publicStores.getCertificate(KeyCertPurpose.AGREEMENT, nodeAlias);

            if (sigCert == null) {
                throw new KeyLoadingException(
                        "No signing certificate found for node %s [ alias = %s ]".formatted(nodeId, nodeAlias));
            }

            if (agrCert == null) {
                throw new KeyLoadingException(
                        "No agreement certificate found for node %s [ alias = %s ]".formatted(nodeId, nodeAlias));
            }

            if (localNodes.contains(nodeId)) {
                final PrivateKey sigPrivateKey = sigPrivateKeys.get(nodeId);
                final PrivateKey agrPrivateKey = agrPrivateKeys.get(nodeId);

                if (sigPrivateKey == null) {
                    throw new KeyLoadingException(
                            "No signing private key found for node %s [ alias = %s ]".formatted(nodeId, nodeAlias));
                }

                if (agrPrivateKey == null) {
                    throw new KeyLoadingException(
                            "No agreement private key found for node %s [ alias = %s ]".formatted(nodeId, nodeAlias));
                }

                final KeyPair sigKeyPair = new KeyPair(sigCert.getPublicKey(), sigPrivateKey);
                final KeyPair agrKeyPair = new KeyPair(agrCert.getPublicKey(), agrPrivateKey);

                final KeysAndCerts kc = new KeysAndCerts(sigKeyPair, agrKeyPair, sigCert, agrCert, publicStores);

                keysAndCerts.put(nodeId, kc);
            }
        });

        return keysAndCerts;
    }

    /**
     * Injects the public keys for all nodes into the address book provided during initialization.
     *
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException if one or more of the required keys were not loaded or are not of the correct type.
     */
    @NonNull
    public EnhancedKeyStoreLoader injectInAddressBook() throws KeyLoadingException, KeyStoreException {
        return injectInAddressBook(addressBook);
    }

    /**
     * Injects the public keys for all nodes into the supplied address book.
     *
     * @param validatingBook the address book into which the public keys should be injected.
     * @return this {@link EnhancedKeyStoreLoader} instance.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public EnhancedKeyStoreLoader injectInAddressBook(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        final PublicStores publicStores = publicStores(validatingBook);
        copyPublicKeys(publicStores, validatingBook);
        return this;
    }

    /**
     * Creates a new {@link PublicStores} instance containing the public keys for all nodes using the address book
     * provided during initialization.
     *
     * @return the {@link PublicStores} instance containing the public keys for all nodes.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException if one or more of the required keys were not loaded or are not of the correct type.
     */
    @NonNull
    public PublicStores publicStores() throws KeyStoreException, KeyLoadingException {
        return publicStores(addressBook);
    }

    /**
     * Creates a new {@link PublicStores} instance containing the public keys for all nodes using the supplied address
     * book.
     *
     * @param validatingBook the address book to use for loading the public keys.
     * @return the {@link PublicStores} instance containing the public keys for all nodes in the supplied address book.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code validatingBook} is {@code null}.
     */
    @NonNull
    public PublicStores publicStores(@NonNull final AddressBook validatingBook)
            throws KeyStoreException, KeyLoadingException {
        final PublicStores publicStores = new PublicStores();

        iterateAddressBook(validatingBook, (i, nodeId, address, nodeAlias) -> {
            final Certificate sigCert = sigCertificates.get(nodeId);
            final Certificate agrCert = agrCertificates.get(nodeId);

            if (!(sigCert instanceof X509Certificate)) {
                throw new KeyLoadingException(
                        "Illegal signing certificate type for node %s [ alias = %s, purpose = %s ]"
                                .formatted(nodeId, nodeAlias, KeyCertPurpose.SIGNING));
            }

            if (!(agrCert instanceof X509Certificate)) {
                throw new KeyLoadingException(
                        "Illegal agreement certificate type for node %s [ alias = %s, purpose = %s ]"
                                .formatted(nodeId, nodeAlias, KeyCertPurpose.AGREEMENT));
            }

            publicStores.setCertificate(KeyCertPurpose.SIGNING, (X509Certificate) sigCert, nodeAlias);
            publicStores.setCertificate(KeyCertPurpose.AGREEMENT, (X509Certificate) agrCert, nodeAlias);
        });

        return publicStores;
    }

    /**
     * Attempts to locate the legacy (combined) public key store and load it.
     *
     * @return the legacy public key store fully loaded; otherwise, an empty key store.
     * @throws KeyLoadingException if the legacy public key store cannot be loaded or is empty.
     * @throws KeyStoreException   if an error occurred while parsing the key store or the key store is not initialized.
     */
    @NonNull
    private KeyStore resolveLegacyPublicStore() throws KeyLoadingException, KeyStoreException {
        final Path legacyStorePath = legacyCertificateStore();

        logger.trace(STARTUP.getMarker(), "Searching for the legacy public key store [ path = {} ]", legacyStorePath);

        if (Files.exists(legacyStorePath)) {
            logger.debug(STARTUP.getMarker(), "Loading the legacy public key store [ path = {} ]", legacyStorePath);
            return loadKeys(legacyStorePath, keyStorePassphrase);
        }

        logger.debug(STARTUP.getMarker(), "No Legacy public key store found");
        return createEmptyTrustStore();
    }

    /**
     * Attempts to locate a private key for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     *
     * <p>
     * This method will attempt to load the private key stores in the following order:
     * <ol>
     *     <li>Enhanced private key store ({@code [type]-private-[alias].pem})</li>
     *     <li>Legacy private key store ({@code private-[alias].pfx})</li>
     * </ol>
     *
     * @param nodeId    the {@link NodeId} for which the private key should be loaded.
     * @param nodeAlias the alias of the node for which the private key should be loaded.
     * @param purpose   the {@link KeyCertPurpose} for which the private key should be loaded.
     * @return the private key for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}; otherwise,
     * {@code null} if no key was found.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, or {@code purpose} is {@code null}.
     */
    @Nullable
    private PrivateKey resolveNodePrivateKey(
            @NonNull final NodeId nodeId, @NonNull final String nodeAlias, @NonNull final KeyCertPurpose purpose) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);

        // Check for the enhanced private key store. The enhance key store is preferred over the legacy key store.
        Path ksLocation = privateKeyStore(nodeAlias, purpose);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found enhanced private key store for node {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readPrivateKey(nodeId, ksLocation);
        }

        // Check for the legacy private key store.
        ksLocation = legacyPrivateKeyStore(nodeAlias);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found legacy private key store for node {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readLegacyPrivateKey(nodeId, ksLocation, purpose.storeName(nodeAlias));
        }

        // No keys were found so return null. Missing keys will be detected during a call to
        // EnhancedKeyStoreLoader::verify() or EnhancedKeyStoreLoader::keysAndCerts().
        logger.warn(
                STARTUP.getMarker(),
                "No private key store found for node {} [ alias = {}, purpose = {} ]",
                nodeId,
                nodeAlias,
                purpose);
        return null;
    }

    /**
     * Attempts to locate a certificate for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     * <p>
     * This method will attempt to load the certificate stores in the following order:
     * <ol>
     *     <li>Enhanced certificate store ({@code [type]-public-[alias].pem})</li>
     *     <li>Legacy certificate store ({@code public.pfx})</li>
     * </ol>
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @param nodeAlias         the alias of the node for which the certificate should be loaded.
     * @param purpose           the {@link KeyCertPurpose} for which the certificate should be loaded.
     * @param legacyPublicStore the preloaded legacy public key store to fallback on if the enhanced certificate store
     *                          is not found.
     * @return the certificate for the specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}; otherwise,
     * {@code null} if no certificate was found.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, {@code purpose}, or {@code legacyPublicStore}
     *                              is {@code null}.
     */
    @Nullable
    private Certificate resolveNodeCertificate(
            @NonNull final NodeId nodeId,
            @NonNull final String nodeAlias,
            @NonNull final KeyCertPurpose purpose,
            @NonNull final KeyStore legacyPublicStore) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        Objects.requireNonNull(legacyPublicStore, MSG_LEGACY_PUBLIC_STORE_NON_NULL);

        // Check for the enhanced certificate store. The enhanced certificate store is preferred over the legacy
        // certificate store.
        Path ksLocation = certificateStore(nodeAlias, purpose);
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found enhanced certificate store for node {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readCertificate(nodeId, ksLocation);
        }

        // Check for the legacy certificate store.
        ksLocation = legacyCertificateStore();
        if (Files.exists(ksLocation)) {
            logger.trace(
                    STARTUP.getMarker(),
                    "Found legacy certificate store for node {} [ purpose = {}, fileName = {} ]",
                    nodeId,
                    purpose,
                    ksLocation.getFileName());
            return readLegacyCertificate(nodeId, nodeAlias, purpose, legacyPublicStore);
        }

        // No certificates were found so return null. Missing certificates will be detected during a call to
        // EnhancedKeyStoreLoader::verify() or EnhancedKeyStoreLoader::keysAndCerts().
        logger.warn(
                STARTUP.getMarker(),
                "No certificate store found for node {} [ purpose = {}, alias = {} ]",
                nodeId,
                purpose,
                nodeAlias);
        return null;
    }

    /**
     * Attempts to read a certificate contained in an enhanced store from the specified {@code location} for the
     * specified {@code nodeId}.
     *
     * @param nodeId   the {@link NodeId} for which the certificate should be loaded.
     * @param location the location of the enhanced certificate store.
     * @return the certificate for the specified {@code nodeId}; otherwise, {@code null} if no certificate was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId} or {@code location} is {@code null}.
     */
    @Nullable
    private Certificate readCertificate(@NonNull final NodeId nodeId, @NonNull final Path location) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try {
            return readEnhancedStore(location, Certificate.class);
        } catch (KeyLoadingException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the enhanced certificate store for node {} [ fileName = {} ]",
                    nodeId,
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Attempts to read a certificate contained in the legacy store from the specified {@code legacyPublicStore} for the
     * specified {@code nodeId}, {@code nodeAlias}, and {@code purpose}.
     *
     * @param nodeId            the {@link NodeId} for which the certificate should be loaded.
     * @param nodeAlias         the alias of the node for which the certificate should be loaded.
     * @param purpose           the {@link KeyCertPurpose} for which the certificate should be loaded.
     * @param legacyPublicStore the preloaded legacy public key store from which to load the certificate.
     * @return the certificate for the specified {@code nodeId}; otherwise, {@code null} if no certificate was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId}, {@code nodeAlias}, {@code purpose}, or {@code legacyPublicStore}
     *                              is {@code null}.
     */
    @Nullable
    private Certificate readLegacyCertificate(
            @NonNull final NodeId nodeId,
            @NonNull final String nodeAlias,
            @NonNull final KeyCertPurpose purpose,
            @NonNull final KeyStore legacyPublicStore) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        Objects.requireNonNull(legacyPublicStore, MSG_LEGACY_PUBLIC_STORE_NON_NULL);

        try {
            final Certificate cert = legacyPublicStore.getCertificate(purpose.storeName(nodeAlias));

            // Legacy certificate store was found, but did not contain the certificate requested.
            if (cert == null) {
                logger.warn(
                        STARTUP.getMarker(),
                        "No certificate found for node {} [ alias = {}, entryName = {} ]",
                        nodeId,
                        nodeAlias,
                        purpose.storeName(nodeAlias));
            }

            return cert;
        } catch (KeyStoreException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the legacy certificate store [ fileName = {} ]",
                    PUBLIC_KEYS_FILE,
                    e);
            return null;
        }
    }

    /**
     * Attempts to read a private key contained in an enhanced store from the specified {@code location} for the
     * specified {@code nodeId}.
     *
     * @param nodeId   the {@link NodeId} for which the private key should be loaded.
     * @param location the location of the enhanced private key store.
     * @return the private key for the specified {@code nodeId}; otherwise, {@code null} if no private key was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId} or {@code location} is {@code null}.
     */
    @Nullable
    PrivateKey readPrivateKey(@NonNull final NodeId nodeId, @NonNull final Path location) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);

        try {
            return readEnhancedStore(location, PrivateKey.class);
        } catch (KeyLoadingException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the enhanced private key store for node {} [ fileName = {} ]",
                    nodeId,
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Attempts to read a private key contained in the legacy store from the specified {@code location} for the
     * specified {@code nodeId} and {@code entryName}.
     *
     * @param nodeId    the {@link NodeId} for which the private key should be loaded.
     * @param location  the location of the legacy private key store.
     * @param entryName the name of the entry in the legacy private key store.
     * @return the private key for the specified {@code nodeId}; otherwise, {@code null} if no private key was found or
     * an error occurred while attempting to read the store.
     * @throws NullPointerException if {@code nodeId}, {@code location}, or {@code entryName} is {@code null}.
     */
    @Nullable
    private PrivateKey readLegacyPrivateKey(
            @NonNull final NodeId nodeId, @NonNull final Path location, @NonNull final String entryName) {
        Objects.requireNonNull(nodeId, MSG_NODE_ID_NON_NULL);
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);
        Objects.requireNonNull(entryName, MSG_ENTRY_NAME_NON_NULL);

        try {
            final KeyStore ks = loadKeys(location, keyStorePassphrase);
            final Key k = ks.getKey(entryName, keyStorePassphrase);

            if (!(k instanceof PrivateKey)) {
                logger.warn(
                        STARTUP.getMarker(), "No private key found for node {} [ entryName = {} ]", nodeId, entryName);
            }

            return (k instanceof PrivateKey pk) ? pk : null;
        } catch (KeyLoadingException | KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e) {
            logger.warn(
                    STARTUP.getMarker(),
                    "Unable to load the legacy private key store [ fileName = {} ]",
                    location.getFileName(),
                    e);
            return null;
        }
    }

    /**
     * Utility method for resolving the {@link Path} to the enhanced private key store for the specified
     * {@code nodeAlias} and {@code purpose}.
     *
     * @param nodeAlias the alias of the node for which the private key store should be loaded.
     * @param purpose   the {@link KeyCertPurpose} for which the private key store should be loaded.
     * @return the {@link Path} to the enhanced private key store for the specified {@code nodeAlias} and {@code purpose}.
     * @throws NullPointerException if {@code nodeAlias} or {@code purpose} is {@code null}.
     */
    @NonNull
    private Path privateKeyStore(@NonNull String nodeAlias, @NonNull KeyCertPurpose purpose) {
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        return keyStoreDirectory.resolve(String.format("%s-private-%s.pem", purpose.prefix(), nodeAlias));
    }

    /**
     * Utility method for resolving the {@link Path} to the legacy private key store for the specified {@code nodeAlias}.
     *
     * @param nodeAlias the alias of the node for which the private key store should be loaded.
     * @return the {@link Path} to the legacy private key store for the specified {@code nodeAlias}.
     * @throws NullPointerException if {@code nodeAlias} is {@code null}.
     */
    @NonNull
    private Path legacyPrivateKeyStore(@NonNull String nodeAlias) {
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        return keyStoreDirectory.resolve(String.format("private-%s.pfx", nodeAlias));
    }

    /**
     * Utility method for resolving the {@link Path} to the enhanced certificate store for the specified {@code nodeAlias}
     * and {@code purpose}.
     *
     * @param nodeAlias the alias of the node for which the certificate store should be loaded.
     * @param purpose   the {@link KeyCertPurpose} for which the certificate store should be loaded.
     * @return the {@link Path} to the enhanced certificate store for the specified {@code nodeAlias} and {@code purpose}.
     * @throws NullPointerException if {@code nodeAlias} or {@code purpose} is {@code null}.
     */
    @NonNull
    private Path certificateStore(@NonNull String nodeAlias, @NonNull KeyCertPurpose purpose) {
        Objects.requireNonNull(nodeAlias, MSG_NODE_ALIAS_NON_NULL);
        Objects.requireNonNull(purpose, MSG_PURPOSE_NON_NULL);
        return keyStoreDirectory.resolve(String.format("%s-public-%s.pem", purpose.prefix(), nodeAlias));
    }

    /**
     * Utility method for resolving the {@link Path} to the legacy certificate store.
     *
     * @return the {@link Path} to the legacy certificate store.
     */
    @NonNull
    private Path legacyCertificateStore() {
        return keyStoreDirectory.resolve(PUBLIC_KEYS_FILE);
    }

    /**
     * Utility method for reading a specific {@code entryType} from an enhanced key store at the specified
     * {@code location}.
     *
     * @param location  the {@link Path} to the enhanced key store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return the entry of the specified {@code entryType} from the key store.
     * @throws KeyLoadingException  if an error occurred while attempting to read the key store or the requested entry
     *                              was not found.
     * @throws NullPointerException if {@code location} or {@code entryType} is {@code null}.
     */
    @NonNull
    private <T> T readEnhancedStore(@NonNull final Path location, @NonNull final Class<T> entryType)
            throws KeyLoadingException {
        Objects.requireNonNull(location, MSG_LOCATION_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        try (final PEMParser parser =
                new PEMParser(new InputStreamReader(Files.newInputStream(location), StandardCharsets.UTF_8))) {
            Object entry = null;

            while ((entry = parser.readObject()) != null) {
                if (isCompatibleStoreEntry(entry, entryType)) {
                    break;
                }
            }

            if (entry == null) {
                throw new KeyLoadingException("No entry of the requested type found [ entryType = %s, fileName = %s ]"
                        .formatted(entryType.getName(), location.getFileName()));
            }

            return extractEntityOfType(entry, entryType);
        } catch (IOException | DecoderException e) {
            throw new KeyLoadingException(
                    "Unable to read enhanced store [ fileName = %s ]".formatted(location.getFileName()), e);
        }
    }

    /**
     * Helper method related to {@link #readEnhancedStore(Path, Class)} used to extract the requested {@code entryType}
     * from the specified {@code entry} loaded from the store.
     *
     * @param entry     the entry loaded from the store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return the requested entry of the specified {@code entryType}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the requested entry or entry is
     *                              an unsupported type.
     * @throws NullPointerException if {@code entry} or {@code entryType} is {@code null}.
     */
    @NonNull
    @SuppressWarnings("unchecked")
    private <T> T extractEntityOfType(@NonNull final Object entry, @NonNull final Class<T> entryType)
            throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        if (entryType.isAssignableFrom(PublicKey.class)) {
            return (T) extractPublicKeyEntity(entry);
        } else if (entryType.isAssignableFrom(PrivateKey.class)) {
            return (T) extractPrivateKeyEntity(entry);
        } else if (entryType.isAssignableFrom(Certificate.class)) {
            return (T) extractCertificateEntity(entry);
        } else {
            throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]".formatted(entryType.getName()));
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link PublicKey} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link PublicKey} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link PublicKey} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private PublicKey extractPublicKeyEntity(@NonNull final Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            final PEMDecryptorProvider decrypter = new JcePEMDecryptorProviderBuilder().build(keyStorePassphrase);

            return switch (entry) {
                case SubjectPublicKeyInfo spki -> converter.getPublicKey(spki);
                case PEMKeyPair kp -> converter.getPublicKey(kp.getPublicKeyInfo());
                case PEMEncryptedKeyPair ekp -> converter.getPublicKey(
                        ekp.decryptKeyPair(decrypter).getPublicKeyInfo());
                default -> throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                        .formatted(entry.getClass().getName()));
            };
        } catch (IOException e) {
            throw new KeyLoadingException(
                    "Unable to extract a public key from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link PrivateKey} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link PrivateKey} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link PrivateKey} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private PrivateKey extractPrivateKeyEntity(@NonNull final Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            final PEMDecryptorProvider decrypter = new JcePEMDecryptorProviderBuilder().build(keyStorePassphrase);
            final InputDecryptorProvider inputDecrypter =
                    new JceInputDecryptorProviderBuilder().build(new String(keyStorePassphrase).getBytes());

            return switch (entry) {
                case PrivateKeyInfo pki -> converter.getPrivateKey(pki);
                case PKCS8EncryptedPrivateKeyInfo epki -> converter.getPrivateKey(
                        epki.decryptPrivateKeyInfo(inputDecrypter));
                case PEMKeyPair kp -> converter.getPrivateKey(kp.getPrivateKeyInfo());
                case PEMEncryptedKeyPair ekp -> converter.getPrivateKey(
                        ekp.decryptKeyPair(decrypter).getPrivateKeyInfo());
                default -> throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                        .formatted(entry.getClass().getName()));
            };
        } catch (IOException | PKCSException e) {
            throw new KeyLoadingException(
                    "Unable to extract a private key from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Helper method used by {@link #extractEntityOfType(Object, Class)} for extracting a {@link Certificate} from the
     * specified {@code entry}.
     *
     * @param entry the entry loaded from the store.
     * @return the {@link Certificate} extracted from the specified {@code entry}.
     * @throws KeyLoadingException  if an error occurred while attempting to extract the {@link Certificate} from the
     *                              specified {@code entry}.
     * @throws NullPointerException if {@code entry} is {@code null}.
     */
    @NonNull
    private Certificate extractCertificateEntity(@NonNull Object entry) throws KeyLoadingException {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);

        try {
            if (entry instanceof X509CertificateHolder ch) {
                return new JcaX509CertificateConverter().getCertificate(ch);
            }

            throw new KeyLoadingException("Unsupported entry type [ entryType = %s ]"
                    .formatted(entry.getClass().getName()));
        } catch (CertificateException e) {
            throw new KeyLoadingException(
                    "Unable to extract a certificate from the specified entry [ entryType = %s ]"
                            .formatted(entry.getClass().getName()),
                    e);
        }
    }

    /**
     * Utility method for determining if the specified {@code entry} is compatible with the specified {@code entryType}.
     *
     * @param entry     the entry loaded from the store.
     * @param entryType the {@link Class} instance of the requested entry type.
     * @param <T>       the type of entry to load from the key store.
     * @return {@code true} if the specified {@code entry} is compatible with the specified {@code entryType};
     * otherwise, {@code false}.
     * @throws NullPointerException if {@code entry} or {@code entryType} is {@code null}.
     */
    private static <T> boolean isCompatibleStoreEntry(@NonNull final Object entry, @NonNull final Class<T> entryType) {
        Objects.requireNonNull(entry, MSG_ENTRY_NON_NULL);
        Objects.requireNonNull(entryType, MSG_ENTRY_TYPE_NON_NULL);

        if (entryType.isAssignableFrom(PublicKey.class)
                && (entry instanceof SubjectPublicKeyInfo
                        || entry instanceof PEMKeyPair
                        || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else if (entryType.isAssignableFrom(PrivateKey.class)
                && (entry instanceof PEMKeyPair
                        || entry instanceof PrivateKeyInfo
                        || entry instanceof PKCS8EncryptedPrivateKeyInfo
                        || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else if (entryType.isAssignableFrom(KeyPair.class)
                && (entry instanceof PEMKeyPair || entry instanceof PEMEncryptedKeyPair)) {
            return true;
        } else return entryType.isAssignableFrom(Certificate.class) && entry instanceof X509CertificateHolder;
    }

    /**
     * Helper method for iterating over the address book in index order.
     *
     * @param addressBook the address book to iterate over.
     * @param function    the function to apply to each entry in the address book.
     * @throws KeyStoreException    if an error occurred while parsing the key store or the key store is not initialized.
     * @throws KeyLoadingException  if one or more of the required keys were not loaded or are not of the correct type.
     * @throws NullPointerException if {@code addressBook} or {@code function} is {@code null}.
     */
    private static void iterateAddressBook(
            @NonNull final AddressBook addressBook, @NonNull AddressBookCallback function)
            throws KeyStoreException, KeyLoadingException {
        Objects.requireNonNull(addressBook, MSG_ADDRESS_BOOK_NON_NULL);
        Objects.requireNonNull(function, MSG_FUNCTION_NON_NULL);

        for (int i = 0; i < addressBook.getSize(); i++) {
            final NodeId nodeId = addressBook.getNodeId(i);
            final Address address = addressBook.getAddress(nodeId);
            final String nodeAlias = nameToAlias(address.getSelfName());

            function.apply(i, nodeId, address, nodeAlias);
        }
    }

    /**
     * The contract for the function used by {@link #iterateAddressBook(AddressBook, AddressBookCallback)} to iterate
     * over the address book in index order.
     */
    @FunctionalInterface
    private interface AddressBookCallback {
        void apply(int index, NodeId nodeId, Address address, String nodeAlias)
                throws KeyStoreException, KeyLoadingException;
    }
}