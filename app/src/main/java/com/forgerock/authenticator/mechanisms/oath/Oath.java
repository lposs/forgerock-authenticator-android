/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015-2016 ForgeRock AS.
 *
 * Portions Copyright 2013 Nathaniel McCallum, Red Hat
 */

package com.forgerock.authenticator.mechanisms.oath;

import com.forgerock.authenticator.identity.Identity;
import com.forgerock.authenticator.mechanisms.base.Mechanism;
import com.forgerock.authenticator.mechanisms.base.MechanismInfo;
import com.forgerock.authenticator.mechanisms.MechanismCreationException;
import com.forgerock.authenticator.storage.IdentityModel;
import com.google.android.apps.authenticator.Base32String;
import com.google.android.apps.authenticator.Base32String.DecodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Responsible for representing a Token in the authenticator application. Combines a
 * number of responsibilities into the same class.
 *
 * - Generate new OTP codes
 * - Value object for display purposes
 */
public class Oath extends Mechanism {
    public enum TokenType {
        HOTP, TOTP
    }

    private static final String TOKEN_TYPE = "tokenType";
    private static final String ALGO = "algo";
    private static final String SECRET = "SECRET";
    private static final String DIGITS = "digits";
    private static final String COUNTER = "counter";
    private static final String PERIOD = "period";
    private static final int VERSION = 1;
    private static final OathInfo oathInfo = new OathInfo();

    private TokenType type;
    private String algo;
    private byte[] secret;
    private int digits;
    private long counter;
    private int period;

    private Logger logger = LoggerFactory.getLogger(Oath.class);

    private Oath(Identity owner, long id, int mechanismUID, TokenType type, String algo, byte[] secret, int digits,
                 long counter, int period) {
        super(owner, id, mechanismUID);
        this.type = type;
        this.algo = algo;
        this.secret = secret;
        this.digits = digits;
        this.counter = counter;
        this.period = period;
    }

    /**
     * Returns a builder for creating a Token.
     * @return The Token builder.
     */
    public static OathBuilder getBuilder() {
        return new OathBuilder();
    }

    @Override
    public Map<String, String> asMap() {
        Map<String, String> result = new HashMap<>();
        result.put(TOKEN_TYPE, type.toString());
        result.put(ALGO, algo);
        result.put(SECRET, Base32String.encode(secret));
        result.put(DIGITS, Integer.toString(digits));
        result.put(COUNTER, Long.toString(counter));
        result.put(PERIOD, Integer.toString(period));
        return result;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public MechanismInfo getInfo() {
        return oathInfo;
    }

    /**
     * Returns the number of digits that are in OTPs generated by this Token.
     * @return The OTP length.
     */
    public int getDigits() {
        return digits;
    }

    /**
     * Returns the token type (HOTP, TOTP)
     * @return The token type.
     */
    public TokenType getType() {
        return type;
    }

    /**
     * Generates a new set of codes for this Token.
     */
    public TokenCode generateNextCode() {
        long cur = System.currentTimeMillis();

        switch (type) {
        case HOTP:
            counter++;
            save();
            return new TokenCode(getHOTP(counter), cur, cur + (period * 1000));

        case TOTP:
            long counter = cur / 1000 / period;
            return new TokenCode(getHOTP(counter + 0),
                                 (counter + 0) * period * 1000,
                                 (counter + 1) * period * 1000);
        }

        return null;
    }

    private String getHOTP(long counter) {
        // Encode counter in network byte order
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putLong(counter);

        // Create digits divisor
        int div = 1;
        for (int i = digits; i > 0; i--)
            div *= 10;

        // Create the HMAC
        try {
            Mac mac = Mac.getInstance("Hmac" + algo);
            mac.init(new SecretKeySpec(secret, "Hmac" + algo));

            // Do the hashing
            byte[] digest = mac.doFinal(bb.array());

            // Truncate
            int binary;
            int off = digest[digest.length - 1] & 0xf;
            binary = (digest[off] & 0x7f) << 0x18;
            binary |= (digest[off + 1] & 0xff) << 0x10;
            binary |= (digest[off + 2] & 0xff) << 0x08;
            binary |= (digest[off + 3] & 0xff);
            binary = binary % div;

            // Zero pad
            String hotp = Integer.toString(binary);
            while (hotp.length() != digits)
                hotp = "0" + hotp;

            return hotp;
        } catch (InvalidKeyException e) {
            logger.error("Invalid key used", e);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Invalid algorithm used", e);
        }

        return "";
    }

    /**
     * Builder class responsible for producing a Token.
     */
    public static class OathBuilder extends PartialMechanismBuilder<OathBuilder> {
        private TokenType type;
        private String algo;
        private byte[] secret;
        private int digits;
        private long counter;
        private int period;

        /**
         * Sets the type of OTP that will be used.
         * @param type Type must be 'totp' or 'hotp'.
         * @return The current builder.
         * @throws MechanismCreationException If the value was not permitted.
         */
        public OathBuilder setType(String type) throws MechanismCreationException {
            try {
                this.type = TokenType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new MechanismCreationException("Invalid type: " + type);
            }
            return this;
        }

        /**
         * Sets the algorithm used for generating the OTP.
         * Assumption: algorithm name is valid if a corresponding algorithm can be loaded.
         *
         * @param algorithm Non null algorithm to assign.
         * @return The current builder.
         * @throws MechanismCreationException If the value was not a supported algorithm.
         */
        public OathBuilder setAlgorithm(String algorithm) throws MechanismCreationException {
            String algoUpperCase = algorithm.toUpperCase(Locale.US);
            try {
                Mac.getInstance("Hmac" + algoUpperCase);
                algo = algoUpperCase;
            } catch (NoSuchAlgorithmException e1) {
                throw new MechanismCreationException("Invalid algorithm: " + algorithm);
            }
            return this;
        }

        /**
         * Sets the length of the OTP to generate.
         * @param digitStr Non null digits string, either 6 or 8.
         * @return The current builder.
         * @throws MechanismCreationException If the value did not match allowed values.
         */
        public OathBuilder setDigits(String digitStr) throws MechanismCreationException {
            try {
                int digits = Integer.parseInt(digitStr);
                if (digits != 6 && digits != 8) {
                    throw new MechanismCreationException("Digits must be 6 or 8: " + digitStr);
                }
                this.digits = digits;
            } catch (NumberFormatException e) {
                throw new MechanismCreationException("Digits was not a number: " + digitStr);
            }
            return this;
        }

        /**
         * Sets the frequency with which the OTP changes.
         * @param periodStr Non null period in seconds.
         * @return The current builder.
         * @throws MechanismCreationException If the value was not a number.
         */
        public OathBuilder setPeriod(String periodStr) throws MechanismCreationException {
            try {
                this.period = Integer.parseInt(periodStr);
            } catch (NumberFormatException e) {
                throw new MechanismCreationException("Period was not a number: " + periodStr);
            }
            return this;
        }

        /**
         * Sets the secret used for generating the OTP.
         * Base32 encodeding based on: http://tools.ietf.org/html/rfc4648#page-8
         *
         * @param secretStr A non null Base32 encoded secret key.
         * @return The current builder.
         * @throws MechanismCreationException If the value was not Base32 encoded.
         */
        public OathBuilder setSecret(String secretStr) throws MechanismCreationException {
            try {
                secret = Base32String.decode(secretStr);
            } catch (DecodingException e) {
                throw new MechanismCreationException("Could not decode secret: " + secretStr, e);
            } catch (NullPointerException e) {
                throw new MechanismCreationException("Unexpected null whilst parsing secret: " + secretStr, e);
            }
            return this;
        }

        /**
         * Sets the counter for the OTP. Only useful for HOTP.
         * @param counterStr Non null counter as an integer.
         * @return The current builder.
         * @throws MechanismCreationException If the counter string was not a number.
         */
        public OathBuilder setCounter(String counterStr) throws MechanismCreationException {
            try {
                counter = Long.parseLong(counterStr);
            } catch (NumberFormatException e) {
                throw new MechanismCreationException("Failed to parse counter: " + counterStr, e);
            }
            return this;
        }

        /**
         * Sets all of the options for the Token being built. Takes a Map that was generated by an
         * existing Token.
         * @param options The map of options that was generated.
         * @return The current builder.
         * @throws MechanismCreationException If any of the options were invalid.
         */
        public OathBuilder setOptions(Map<String, String> options) throws MechanismCreationException {
            return setType(options.get(TOKEN_TYPE))
                    .setAlgorithm(options.get(ALGO))
                    .setSecret(options.get(SECRET))
                    .setDigits(options.get(DIGITS))
                    .setCounter(options.get(COUNTER))
                    .setPeriod(options.get(PERIOD));
        }

        @Override
        protected OathBuilder getThis() {
            return this;
        }

        /**
         * Produce the described Token.
         * @return The built Token.
         * @throws MechanismCreationException If an owner was not provided.
         */
        protected Oath buildImpl(Identity owner) throws MechanismCreationException {
            return new Oath(owner, id, mechanismUID, type, algo, secret, digits, counter, period);
        }
    }

}
