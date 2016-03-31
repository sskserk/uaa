/*******************************************************************************
 * Cloud Foundry
 * Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 * <p>
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 * <p>
 * This product includes a number of subcomponents with
 * separate copyright notices and license terms. Your use of these
 * subcomponents is subject to the terms and conditions of the
 * subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.oauth.TokenRevokedException;
import org.cloudfoundry.identity.uaa.oauth.jwt.Jwt;
import org.cloudfoundry.identity.uaa.oauth.jwt.JwtHelper;
import org.cloudfoundry.identity.uaa.user.UaaUser;
import org.cloudfoundry.identity.uaa.user.UaaUserDatabase;
import org.flywaydb.core.internal.util.StringUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.jwt.crypto.sign.InvalidSignatureException;
import org.springframework.security.jwt.crypto.sign.SignatureVerifier;
import org.springframework.security.oauth2.common.exceptions.InsufficientScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.AUD;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.CID;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.EXP;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.ISS;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.REVOCATION_SIGNATURE;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.SCOPE;
import static org.cloudfoundry.identity.uaa.oauth.token.ClaimConstants.USER_ID;

public class TokenValidation {
    private static final Log logger = LogFactory.getLog(TokenValidation.class);
    private final Map<String, Object> claims;
    private final Jwt tokenJwt;
    private final String token;
    private final boolean decoded; // this is used to avoid checking claims on tokens that had errors when decoding

    public static TokenValidation validate(String token) {
        return new TokenValidation(token);
    }

    private TokenValidation(String token) {
        this.token = token;

        Jwt tokenJwt;
        try {
            tokenJwt = JwtHelper.decode(token);
        } catch (Exception ex) {
            tokenJwt = null;
            validationErrors.add(new InvalidTokenException("Invalid token (could not decode): " + token, ex));
        }
        this.tokenJwt = tokenJwt;

        String tokenJwtClaims;
        if(tokenJwt != null && StringUtils.hasText(tokenJwtClaims = tokenJwt.getClaims())) {
            Map<String, Object> claims;
            try {
                claims = JsonUtils.readValue(tokenJwtClaims, new TypeReference<Map<String, Object>>() {});
            }
            catch (JsonUtils.JsonUtilException ex) {
                claims = null;
                validationErrors.add(new InvalidTokenException("Invalid token (cannot read token claims): " + token, ex));
            }
            this.claims = claims;
        } else {
            this.claims = new HashMap<>();
        }

        this.decoded = isValid();
    }


    public boolean isValid() {
        return validationErrors.size() == 0;
    }

    private List<Exception> validationErrors = new ArrayList<>();

    public List<Exception> getValidationErrors() {
        return validationErrors;
    }

    @SuppressWarnings("CloneDoesntCallSuperClone")
    public TokenValidation clone() {
        return new TokenValidation(this);
    }


    private TokenValidation(TokenValidation source) {
        this.claims = source.claims == null ? null : new HashMap<>(source.claims);
        this.tokenJwt = source.tokenJwt;
        this.token = source.token;
        this.decoded = source.decoded;

        this.scopes = source.scopes;
    }


    public TokenValidation checkSignature(SignatureVerifier verifier) {
        if(!decoded) { return this; }
        try {
            tokenJwt.verifySignature(verifier);
        } catch (InvalidSignatureException ex) {
            logger.debug("Invalid token (could not verify signature)", ex);
            validationErrors.add(new InvalidSignatureException("Invalid token (could not verify signature): " + token));
        }
        return this;
    }

    public TokenValidation checkIssuer(String issuer) {
        if(!decoded || !claims.containsKey(ISS)) {
            addError("Token does not bear an ISS claim.");
            return this;
        }

        if(!equals(issuer, claims.get(ISS))) {
            addError("Invalid issuer for token: " + claims.get(ISS));
        }
        return this;
    }

    public TokenValidation checkExpiry(Instant asOf) {
        if(!decoded || !claims.containsKey(EXP)) {
            addError("Token does not bear an EXP claim.");
            return this;
        }

        Object expClaim = claims.get(EXP);
        long expiry;
        try {
            expiry = (int) expClaim;
            if(asOf.getEpochSecond() > expiry) { addError("Token expired at " + expiry); }
        } catch (ClassCastException ex) {
            addError("Token bears an invalid or unparseable EXP claim.", ex);
        }
        return this;
    }

    public TokenValidation checkUser(UaaUserDatabase userDb) {
        if(!decoded || !claims.containsKey(USER_ID)) {
            addError("Token does not bear a USER_ID claim.");
            return this;
        }

        String userId;
        Object userIdClaim = claims.get(USER_ID);
        try {
            userId = (String) userIdClaim;
        } catch (ClassCastException ex) {
            addError("Token bears an invalid or unparseable USER_ID claim.", ex);
            return this;
        }

        if(userId == null) {
            addError("Token has a null USER_ID claim.");
        }
        else {
            UaaUser user;
            try {
                user = userDb.retrieveUserById(userId);
                Assert.notNull(user);
            } catch (UsernameNotFoundException ex) {
                user = null;
                addError("Token bears a non-existent user ID: " + userId, ex);
            }

            if(user == null) {
                // Unlikely to occur, but since this is dependent on the implementation of an interface...
                addError("Found no data for user ID: " + userId);
            } else {
                List<? extends GrantedAuthority> authorities = user.getAuthorities();
                if (authorities == null) {
                    addError("Invalid token (all scopes have been revoked)");
                } else {
                    List<String> authoritiesValue = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
                    checkScopesWithin(authoritiesValue);
                }
            }
        }
        return this;
    }

    public TokenValidation checkScopesInclude(String... scopes) {
        return checkScopesInclude(Arrays.asList(scopes));
    }

    public TokenValidation checkScopesInclude(Collection<String> scopes) {
        getScopes().ifPresent(tokenScopes -> {
            String missingScopes = scopes.stream().filter(s -> !tokenScopes.contains(s)).collect(Collectors.joining(" "));
            if(StringUtils.hasText(missingScopes)) {
                validationErrors.add(new InsufficientScopeException("Some expected scopes are missing: " + missingScopes));
            }
        });
        return this;
    }

    public TokenValidation checkScopesWithin(String... scopes) {
        return checkScopesWithin(Arrays.asList(scopes));
    }

    public TokenValidation checkScopesWithin(Collection<String> scopes) {
        getScopes().ifPresent(tokenScopes -> {
            List<String> missingScopes = tokenScopes.stream().filter(s -> !scopes.contains(s)).collect(Collectors.toList());
            if(!missingScopes.isEmpty()) {
                addError("Some scopes have been revoked: " + missingScopes.stream().collect(Collectors.joining(" ")));
            }
        });
        return this;
    }

    public TokenValidation checkClient(ClientDetailsService clientDetailsService) {
        if(!decoded || !claims.containsKey(CID)) {
            addError("Token does not bear a CID claim.");
            return this;
        }

        String clientId;
        try {
            clientId = (String) claims.get(CID);
        } catch (ClassCastException ex) {
            addError("Token bears an invalid or unparseable CID claim.", ex);
            return this;
        }

        try {
            ClientDetails client = clientDetailsService.loadClientByClientId(clientId);
            checkScopesWithin(client.getScope());
        } catch(NoSuchClientException ex) {
            addError("The token refers to a non-existent client: " + clientId, ex);
        }

        return this;
    }

    public TokenValidation checkRevocationHash(String currentHash) {
        if(!decoded) {
            addError("Token does not bear a revocation hash.");
            return this;
        }
        if(!claims.containsKey(REVOCATION_SIGNATURE)) {
            // tokens issued before revocation signatures were implemented are still valid
            return this;
        }

        String revocableHashSignature;
        try {
            revocableHashSignature = (String)claims.get(REVOCATION_SIGNATURE);
        } catch (ClassCastException ex) {
            addError("Token bears an invalid or unparseable CID claim.", ex);
            return this;
        }

        if(!StringUtils.hasText(revocableHashSignature) || !revocableHashSignature.equals(currentHash)) {
            validationErrors.add(new TokenRevokedException(token));
        }
        return this;
    }

    public TokenValidation checkAudience(String... clients) {
        return checkAudience(Arrays.asList(clients));
    }

    public TokenValidation checkAudience(Collection<String> clients) {
        if (!decoded || !claims.containsKey(AUD)) {
            addError("The token does not bear an AUD claim.");
            return this;
        }

        Object audClaim = claims.get(AUD);
        List<String> audience;
        if(audClaim instanceof String) {
            audience = Collections.singletonList((String) audClaim);
        }
        else if(audClaim == null) {
            audience = Collections.emptyList();
        }
        else {
            try {
                audience = ((List<?>) audClaim).stream()
                        .map(s -> (String) s)
                        .collect(Collectors.toList());
            } catch (ClassCastException ex) {
                addError("The token's audience claim is invalid or unparseable.", ex);
                return this;
            }
        }

        String notInAudience = clients.stream().filter(c -> !audience.contains(c)).collect(Collectors.joining(", "));
        if(StringUtils.hasText(notInAudience)) {
            addError("Some parties were not in the token audience: " + notInAudience);
        }

        return this;
    }


    private boolean addError(String msg, Exception cause) {
        return validationErrors.add(new InvalidTokenException(msg, cause));
    }

    private boolean addError(String msg) {
        return addError(msg, null);
    }

    private static boolean equals(Object a, Object b) {
        if(a == null) return b == null;
        return a.equals(b);
    }

    private Optional<List<String>> scopes = null;
    private Optional<List<String>> getScopes() {
        if (scopes == null) {
            if (!decoded || !claims.containsKey(SCOPE)) {
                addError("The token does not bear a SCOPE claim.");
                return scopes = Optional.empty();
            }

            Object scopeClaim = claims.get(SCOPE);
            if (scopeClaim == null) {
                // treat null scope claim the same as empty scope claim
                scopeClaim = new ArrayList<>();
            }

            try {
                return scopes = Optional.of(((List<?>) scopeClaim).stream()
                        .map(s -> (String) s)
                        .collect(Collectors.toList()));
            } catch (ClassCastException ex) {
                addError("The token's scope claim is invalid or unparseable.", ex);
                return scopes = Optional.empty();
            }
        }
        return scopes;
    }
}
