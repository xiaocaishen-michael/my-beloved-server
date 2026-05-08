package com.mbw.account.infrastructure.security;

import com.mbw.account.application.port.CipherService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Placeholder for the M3 Aliyun KMS envelope-encryption implementation
 * of {@link CipherService} (realname-verification spec T8).
 *
 * <p>Activated by {@code mbw.realname.cipher=aliyun-kms}. Throws on every
 * call so that selecting this mode in M1 fails-fast at the first encrypt
 * attempt rather than silently no-op'ing the PII protection. The class
 * exists in M1 only to keep the {@code @ConditionalOnProperty} routing
 * surface complete (and to make the M3 swap mechanical — implement the
 * two methods, leave config and wiring untouched).
 */
@Service
@ConditionalOnProperty(name = "mbw.realname.cipher", havingValue = "aliyun-kms")
public class AliyunKmsCipherService implements CipherService {

    @Override
    public byte[] encrypt(byte[] plaintext) {
        throw new UnsupportedOperationException(
                "AliyunKmsCipherService is an M3 placeholder — switch mbw.realname.cipher to env-dek");
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        throw new UnsupportedOperationException(
                "AliyunKmsCipherService is an M3 placeholder — switch mbw.realname.cipher to env-dek");
    }
}
