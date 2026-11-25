package com.securedoc.platform.infrastructure.casefields;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;

/** Task 0061: stands where there is no key service (local development only) and refuses every key. It keeps nothing. */
public final class NoKeyService implements KeyService {

    private static CryptoException none() {
        return new CryptoException(CryptoError.CRYPTO_SETTINGS_INVALID, "no key service is set up");
    }

    @Override
    public String id() {
        return "none";
    }

    @Override
    public void createKeys(CustomerId customer) {
        throw none();
    }

    @Override
    public KeyVersions wrappingKey(CustomerId customer) {
        throw none();
    }

    @Override
    public KeyVersions fingerprintKey(CustomerId customer) {
        throw none();
    }

    @Override
    public DataKey newDataKey(CustomerId customer) {
        throw none();
    }

    @Override
    public byte[] unwrap(CustomerId customer, String wrapped) {
        throw none();
    }

    @Override
    public String rewrap(CustomerId customer, String wrapped) {
        throw none();
    }

    @Override
    public int versionOf(String wrapped) {
        throw none();
    }

    @Override
    public int rotate(CustomerId customer) {
        throw none();
    }

    @Override
    public byte[] fingerprint(CustomerId customer, int version, byte[] input) {
        throw none();
    }

    @Override
    public Health health() {
        throw none();
    }
}
