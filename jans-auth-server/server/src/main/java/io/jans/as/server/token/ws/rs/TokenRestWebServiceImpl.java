/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.token.ws.rs;

import com.google.common.base.Strings;
import com.nimbusds.jose.jwk.JWKException;
import io.jans.as.common.model.common.User;
import io.jans.as.common.model.registration.Client;
import io.jans.as.common.service.AttributeService;
import io.jans.as.model.authorize.CodeVerifier;
import io.jans.as.model.common.*;
import io.jans.as.model.configuration.AppConfiguration;
import io.jans.as.model.crypto.binding.TokenBindingMessage;
import io.jans.as.model.error.ErrorResponseFactory;
import io.jans.as.model.exception.InvalidJwtException;
import io.jans.as.model.jwk.JSONWebKey;
import io.jans.as.model.jwt.Jwt;
import io.jans.as.model.token.JsonWebResponse;
import io.jans.as.model.token.TokenErrorResponseType;
import io.jans.as.model.token.TokenRequestParam;
import io.jans.as.server.audit.ApplicationAuditLogger;
import io.jans.as.server.model.audit.Action;
import io.jans.as.server.model.audit.OAuth2AuditLog;
import io.jans.as.server.model.common.*;
import io.jans.as.server.model.config.Constants;
import io.jans.as.server.model.session.SessionClient;
import io.jans.as.server.model.token.JwrService;
import io.jans.as.server.security.Identity;
import io.jans.as.server.service.*;
import io.jans.as.server.service.ciba.CibaRequestService;
import io.jans.as.server.service.external.ExternalResourceOwnerPasswordCredentialsService;
import io.jans.as.server.service.external.ExternalUpdateTokenService;
import io.jans.as.server.service.external.context.ExternalResourceOwnerPasswordCredentialsContext;
import io.jans.as.server.service.external.context.ExternalUpdateTokenContext;
import io.jans.as.server.uma.service.UmaTokenService;
import io.jans.as.server.util.ServerUtil;
import io.jans.orm.exception.AuthenticationException;
import io.jans.util.OxConstants;
import io.jans.util.StringHelper;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import jakarta.ws.rs.core.SecurityContext;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.Arrays;
import java.util.Date;
import java.util.function.Function;

import static io.jans.as.model.config.Constants.*;
import static org.apache.commons.lang.BooleanUtils.isFalse;
import static org.apache.commons.lang.BooleanUtils.isTrue;

/**
 * Provides interface for token REST web services
 *
 * @author Yuriy Zabrovarnyy
 * @author Javier Rojas Blum
 * @version October 5, 2021
 */
@Path("/")
public class TokenRestWebServiceImpl implements TokenRestWebService {

    @Inject
    private Logger log;

    @Inject
    private Identity identity;

    @Inject
    private ApplicationAuditLogger applicationAuditLogger;

    @Inject
    private ErrorResponseFactory errorResponseFactory;

    @Inject
    private AuthorizationGrantList authorizationGrantList;

    @Inject
    private UserService userService;

    @Inject
    private GrantService grantService;

    @Inject
    private AuthenticationFilterService authenticationFilterService;

    @Inject
    private AuthenticationService authenticationService;

    @Inject
    private AppConfiguration appConfiguration;

    @Inject
    private UmaTokenService umaTokenService;

    @Inject
    private ExternalResourceOwnerPasswordCredentialsService externalResourceOwnerPasswordCredentialsService;

    @Inject
    private AttributeService attributeService;

    @Inject
    private SessionIdService sessionIdService;

    @Inject
    private CibaRequestService cibaRequestService;

    @Inject
    private DeviceAuthorizationService deviceAuthorizationService;

    @Inject
    private ExternalUpdateTokenService externalUpdateTokenService;

    @Inject
    private TokenRestWebServiceValidator tokenRestWebServiceValidator;

    @Override
    public Response requestAccessToken(String grantType, String code,
                                       String redirectUri, String username, String password, String scope,
                                       String assertion, String refreshToken,
                                       String clientId, String clientSecret, String codeVerifier,
                                       String ticket, String claimToken, String claimTokenFormat, String pctCode,
                                       String rptCode, String authReqId, String deviceCode,
                                       HttpServletRequest request, HttpServletResponse response, SecurityContext sec) {
        log.debug(
                "Attempting to request access token: grantType = {}, code = {}, redirectUri = {}, username = {}, refreshToken = {}, " +
                        "clientId = {}, ExtraParams = {}, isSecure = {}, codeVerifier = {}, ticket = {}",
                grantType, code, redirectUri, username, refreshToken, clientId, request.getParameterMap(),
                sec.isSecure(), codeVerifier, ticket);

        boolean isUma = StringUtils.isNotBlank(ticket);
        if (isUma) {
            return umaTokenService.requestRpt(grantType, ticket, claimToken, claimTokenFormat, pctCode, rptCode, scope, request, response);
        }

        OAuth2AuditLog auditLog = new OAuth2AuditLog(ServerUtil.getIpAddress(request), Action.TOKEN_REQUEST);
        auditLog.setClientId(clientId);
        auditLog.setUsername(username);
        auditLog.setScope(scope);

        String tokenBindingHeader = request.getHeader("Sec-Token-Binding");

        scope = ServerUtil.urlDecode(scope); // it may be encoded in uma case

        String dpopStr = runDPoP(request, auditLog);

        try {
            tokenRestWebServiceValidator.validateParams(grantType, code, redirectUri, refreshToken, auditLog);

            GrantType gt = GrantType.fromString(grantType);
            log.debug("Grant type: '{}'", gt);

            Client client = tokenRestWebServiceValidator.validateClient(getClient(), auditLog);
            tokenRestWebServiceValidator.validateGrantType(gt, client, auditLog);

            final Function<JsonWebResponse, Void> idTokenTokingBindingPreprocessing = TokenBindingMessage.createIdTokenTokingBindingPreprocessing(
                    tokenBindingHeader, client.getIdTokenTokenBindingCnf()); // for all except authorization code grant
            final SessionId sessionIdObj = sessionIdService.getSessionId(request);
            final Function<JsonWebResponse, Void> idTokenPreProcessing = JwrService.wrapWithSidFunction(idTokenTokingBindingPreprocessing, sessionIdObj != null ? sessionIdObj.getOutsideSid() : null);

            final ExecutionContext executionContext = new ExecutionContext(request, response);
            executionContext.setCertAsPem(request.getHeader(X_CLIENTCERT));
            executionContext.setDpop(dpopStr);
            executionContext.setClient(client);
            executionContext.setAppConfiguration(appConfiguration);
            executionContext.setAttributeService(attributeService);
            executionContext.setAuditLog(auditLog);

            if (gt == GrantType.AUTHORIZATION_CODE) {
                return processAuthorizationCode(code, scope, codeVerifier, sessionIdObj, executionContext);
            }

            if (gt == GrantType.REFRESH_TOKEN) {
                AuthorizationGrant authorizationGrant = authorizationGrantList.getAuthorizationGrantByRefreshToken(client.getClientId(), refreshToken);
                tokenRestWebServiceValidator.validateGrant(authorizationGrant, client, refreshToken, auditLog);

                final RefreshToken refreshTokenObject = authorizationGrant.getRefreshToken(refreshToken);
                if (refreshTokenObject == null || !refreshTokenObject.isValid()) {
                    log.trace("Invalid refresh token.");
                    return response(error(400, TokenErrorResponseType.INVALID_GRANT, "Unable to find refresh token or otherwise token type or client does not match."), auditLog);
                }

                executionContext.setGrant(authorizationGrant);

                // The authorization server MAY issue a new refresh token, in which case
                // the client MUST discard the old refresh token and replace it with the new refresh token.
                RefreshToken reToken = null;
                if (isFalse(appConfiguration.getSkipRefreshTokenDuringRefreshing())) {
                    if (isTrue(appConfiguration.getRefreshTokenExtendLifetimeOnRotation())) {
                        reToken = createRefreshToken(request, client, scope, authorizationGrant, dpopStr); // extend lifetime
                    } else {
                        log.trace("Create refresh token with fixed (not extended) lifetime taken from previous refresh token.");

                        reToken = authorizationGrant.createRefreshToken(executionContext, refreshTokenObject.getExpirationDate()); // do not extend lifetime
                    }
                }

                scope = authorizationGrant.checkScopesPolicy(scope);

                AccessToken accToken = authorizationGrant.createAccessToken(executionContext); // create token after scopes are checked

                IdToken idToken = null;
                if (isTrue(appConfiguration.getOpenidScopeBackwardCompatibility()) && authorizationGrant.getScopes().contains(OPENID)) {
                    boolean includeIdTokenClaims = Boolean.TRUE.equals(
                            appConfiguration.getLegacyIdTokenClaims());

                    ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, authorizationGrant, client, appConfiguration, attributeService);
                    context.setExecutionContext(executionContext);

                    executionContext.setIncludeIdTokenClaims(includeIdTokenClaims);
                    executionContext.setPreProcessing(idTokenPreProcessing);
                    executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

                    idToken = authorizationGrant.createIdToken(
                            null, null, accToken, null, null, executionContext);
                }

                if (reToken != null && refreshToken != null) {
                    grantService.removeByCode(refreshToken); // remove refresh token after access token and id_token is created.
                }

                auditLog.updateOAuth2AuditLog(authorizationGrant, true);

                return response(Response.ok().entity(getJSonResponse(accToken,
                        accToken.getTokenType(),
                        accToken.getExpiresIn(),
                        reToken,
                        scope,
                        idToken)), auditLog);
            } else if (gt == GrantType.CLIENT_CREDENTIALS) {
                ClientCredentialsGrant clientCredentialsGrant = authorizationGrantList.createClientCredentialsGrant(new User(), client);

                scope = clientCredentialsGrant.checkScopesPolicy(scope);

                executionContext.setGrant(clientCredentialsGrant);
                AccessToken accessToken = clientCredentialsGrant.createAccessToken(executionContext); // create token after scopes are checked

                IdToken idToken = null;
                if (isTrue(appConfiguration.getOpenidScopeBackwardCompatibility()) && clientCredentialsGrant.getScopes().contains(OPENID)) {
                    boolean includeIdTokenClaims = Boolean.TRUE.equals(
                            appConfiguration.getLegacyIdTokenClaims());

                    ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, clientCredentialsGrant, client, appConfiguration, attributeService);

                    executionContext.setIncludeIdTokenClaims(includeIdTokenClaims);
                    executionContext.setPreProcessing(idTokenPreProcessing);
                    executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

                    idToken = clientCredentialsGrant.createIdToken(
                            null, null, null, null, null, executionContext);
                }

                auditLog.updateOAuth2AuditLog(clientCredentialsGrant, true);

                return response(Response.ok().entity(getJSonResponse(accessToken,
                        accessToken.getTokenType(),
                        accessToken.getExpiresIn(),
                        null,
                        scope,
                        idToken)), auditLog);
            } else if (gt == GrantType.RESOURCE_OWNER_PASSWORD_CREDENTIALS) {
                boolean authenticated = false;
                User user = null;
                if (authenticationFilterService.isEnabled()) {
                    String userDn = authenticationFilterService.processAuthenticationFilters(request.getParameterMap());
                    if (StringHelper.isNotEmpty(userDn)) {
                        user = userService.getUserByDn(userDn);
                        authenticated = true;
                    }
                }


                if (!authenticated) {
                    user = authenticateUser(username, password, executionContext, user);
                }

                if (user != null) {
                    ResourceOwnerPasswordCredentialsGrant resourceOwnerPasswordCredentialsGrant = authorizationGrantList.createResourceOwnerPasswordCredentialsGrant(user, client);
                    SessionId sessionId = identity.getSessionId();
                    if (sessionId != null) {
                        resourceOwnerPasswordCredentialsGrant.setAcrValues(OxConstants.SCRIPT_TYPE_INTERNAL_RESERVED_NAME);
                        resourceOwnerPasswordCredentialsGrant.setSessionDn(sessionId.getDn());
                        resourceOwnerPasswordCredentialsGrant.save(); // call save after object modification!!!

                        sessionId.getSessionAttributes().put(Constants.AUTHORIZED_GRANT, gt.getValue());
                        boolean updateResult = sessionIdService.updateSessionId(sessionId, false, true, true);
                        if (!updateResult) {
                            log.debug("Failed to update session entry: '{}'", sessionId.getId());
                        }
                    }


                    RefreshToken reToken = createRefreshToken(request, client, scope, resourceOwnerPasswordCredentialsGrant, null);

                    scope = resourceOwnerPasswordCredentialsGrant.checkScopesPolicy(scope);

                    executionContext.setGrant(resourceOwnerPasswordCredentialsGrant);
                    AccessToken accessToken = resourceOwnerPasswordCredentialsGrant.createAccessToken(executionContext); // create token after scopes are checked

                    IdToken idToken = null;
                    if (isTrue(appConfiguration.getOpenidScopeBackwardCompatibility()) && resourceOwnerPasswordCredentialsGrant.getScopes().contains("openid")) {
                        boolean includeIdTokenClaims = Boolean.TRUE.equals(
                                appConfiguration.getLegacyIdTokenClaims());

                        ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, resourceOwnerPasswordCredentialsGrant, client, appConfiguration, attributeService);
                        context.setExecutionContext(executionContext);

                        executionContext.setIncludeIdTokenClaims(includeIdTokenClaims);
                        executionContext.setPreProcessing(idTokenPreProcessing);
                        executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

                        idToken = resourceOwnerPasswordCredentialsGrant.createIdToken(
                                null, null, null, null, null, executionContext);
                    }

                    auditLog.updateOAuth2AuditLog(resourceOwnerPasswordCredentialsGrant, true);

                    return response(Response.ok().entity(getJSonResponse(accessToken,
                            accessToken.getTokenType(),
                            accessToken.getExpiresIn(),
                            reToken,
                            scope,
                            idToken)), auditLog);
                } else {
                    log.debug("Invalid user", new RuntimeException("User is empty"));
                    return response(error(401, TokenErrorResponseType.INVALID_CLIENT, "Invalid user."), auditLog);
                }
            } else if (gt == GrantType.CIBA) {
                errorResponseFactory.validateComponentEnabled(ComponentType.CIBA);

                log.debug("Attempting to find authorizationGrant by authReqId: '{}'", authReqId);
                final CIBAGrant cibaGrant = authorizationGrantList.getCIBAGrant(authReqId);
                executionContext.setGrant(cibaGrant);

                log.trace("AuthorizationGrant : '{}'", cibaGrant);

                if (cibaGrant != null) {
                    if (!cibaGrant.getClientId().equals(client.getClientId())) {
                        return response(error(400, TokenErrorResponseType.INVALID_GRANT, REASON_CLIENT_NOT_AUTHORIZED), auditLog);
                    }
                    if (cibaGrant.getClient().getBackchannelTokenDeliveryMode() == BackchannelTokenDeliveryMode.PING ||
                            cibaGrant.getClient().getBackchannelTokenDeliveryMode() == BackchannelTokenDeliveryMode.POLL) {
                        if (!cibaGrant.isTokensDelivered()) {
                            RefreshToken refToken = createRefreshToken(request, client, scope, cibaGrant, null);
                            AccessToken accessToken = cibaGrant.createAccessToken(executionContext);

                            ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, cibaGrant, client, appConfiguration, attributeService);
                            context.setExecutionContext(executionContext);

                            executionContext.setIncludeIdTokenClaims(false);
                            executionContext.setPreProcessing(idTokenPreProcessing);
                            executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

                            IdToken idToken = cibaGrant.createIdToken(
                                    null, null, accessToken, refToken, null, executionContext);

                            cibaGrant.setTokensDelivered(true);
                            cibaGrant.save();

                            RefreshToken reToken = null;
                            if (isRefreshTokenAllowed(client, scope, cibaGrant)) {
                                reToken = refToken;
                            }

                            scope = cibaGrant.checkScopesPolicy(scope);

                            auditLog.updateOAuth2AuditLog(cibaGrant, true);

                            return response(Response.ok().entity(getJSonResponse(accessToken,
                                    accessToken.getTokenType(),
                                    accessToken.getExpiresIn(),
                                    reToken,
                                    scope,
                                    idToken)), auditLog);
                        } else {
                            return response(error(400, TokenErrorResponseType.INVALID_GRANT, "AuthReqId is no longer available."), auditLog);
                        }
                    } else {
                        log.debug("Client is not using Poll flow authReqId: '{}'", authReqId);
                        return response(error(400, TokenErrorResponseType.UNAUTHORIZED_CLIENT, "The client is not authorized as it is configured in Push Mode"), auditLog);
                    }
                } else {
                    final CibaRequestCacheControl cibaRequest = cibaRequestService.getCibaRequest(authReqId);
                    log.trace("Ciba request : '{}'", cibaRequest);
                    if (cibaRequest != null) {
                        if (!cibaRequest.getClient().getClientId().equals(client.getClientId())) {
                            return response(error(400, TokenErrorResponseType.INVALID_GRANT, REASON_CLIENT_NOT_AUTHORIZED), auditLog);
                        }
                        long currentTime = new Date().getTime();
                        Long lastAccess = cibaRequest.getLastAccessControl();
                        if (lastAccess == null) {
                            lastAccess = currentTime;
                        }
                        cibaRequest.setLastAccessControl(currentTime);
                        cibaRequestService.update(cibaRequest);

                        if (cibaRequest.getStatus() == CibaRequestStatus.PENDING) {
                            int intervalSeconds = appConfiguration.getBackchannelAuthenticationResponseInterval();
                            long timeFromLastAccess = currentTime - lastAccess;

                            if (timeFromLastAccess > intervalSeconds * 1000) {
                                log.debug("Access hasn't been granted yet for authReqId: '{}'", authReqId);
                                return response(error(400, TokenErrorResponseType.AUTHORIZATION_PENDING, "User hasn't answered yet"), auditLog);
                            } else {
                                log.debug("Slow down protection authReqId: '{}'", authReqId);
                                return response(error(400, TokenErrorResponseType.SLOW_DOWN, "Client is asking too fast the token."), auditLog);
                            }
                        } else if (cibaRequest.getStatus() == CibaRequestStatus.DENIED) {
                            log.debug("The end-user denied the authorization request for authReqId: '{}'", authReqId);
                            return response(error(400, TokenErrorResponseType.ACCESS_DENIED, "The end-user denied the authorization request."), auditLog);
                        } else if (cibaRequest.getStatus() == CibaRequestStatus.EXPIRED) {
                            log.debug("The authentication request has expired for authReqId: '{}'", authReqId);
                            return response(error(400, TokenErrorResponseType.EXPIRED_TOKEN, "The authentication request has expired"), auditLog);
                        }
                    } else {
                        log.debug("AuthorizationGrant is empty by authReqId: '{}'", authReqId);
                        return response(error(400, TokenErrorResponseType.EXPIRED_TOKEN, "Unable to find grant object for given auth_req_id."), auditLog);
                    }
                }
            } else if (gt == GrantType.DEVICE_CODE) {
                return processDeviceCodeGrantType(client, deviceCode, scope, request, response, auditLog);
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return response(Response.status(500), auditLog);
        }

        throw new WebApplicationException(tokenRestWebServiceValidator.error(400, TokenErrorResponseType.UNSUPPORTED_GRANT_TYPE, "Unsupported Grant Type.").build());
    }

    private Response processAuthorizationCode(String code, String scope, String codeVerifier, SessionId sessionIdObj, ExecutionContext executionContext) {
        Client client = executionContext.getClient();

        log.debug("Attempting to find authorizationCodeGrant by clientId: '{}', code: '{}'", client.getClientId(), code);
        final AuthorizationCodeGrant authorizationCodeGrant = authorizationGrantList.getAuthorizationCodeGrant(code);
        log.trace("AuthorizationCodeGrant : '{}'", authorizationCodeGrant);

        // if authorization code is not found then code was already used or wrong client provided = remove all grants with this auth code
        tokenRestWebServiceValidator.validateGrant(authorizationCodeGrant, client, code, executionContext.getAuditLog(), grant -> grantService.removeAllByAuthorizationCode(code));
        validatePKCE(authorizationCodeGrant, codeVerifier, executionContext.getAuditLog());

        authorizationCodeGrant.setIsCachedWithNoPersistence(false);
        authorizationCodeGrant.save();

        RefreshToken reToken = createRefreshToken(executionContext.getHttpRequest(), client, scope, authorizationCodeGrant, executionContext.getDpop());

        scope = authorizationCodeGrant.checkScopesPolicy(scope);

        executionContext.setGrant(authorizationCodeGrant);
        AccessToken accToken = authorizationCodeGrant.createAccessToken(executionContext); // create token after scopes are checked

        IdToken idToken = null;
        if (authorizationCodeGrant.getScopes().contains(OPENID)) {
            String nonce = authorizationCodeGrant.getNonce();
            boolean includeIdTokenClaims = Boolean.TRUE.equals(
                    appConfiguration.getLegacyIdTokenClaims());
            final String idTokenTokenBindingCnf = client.getIdTokenTokenBindingCnf();
            Function<JsonWebResponse, Void> authorizationCodePreProcessing = jsonWebResponse -> {
                if (StringUtils.isNotBlank(idTokenTokenBindingCnf) && StringUtils.isNotBlank(authorizationCodeGrant.getTokenBindingHash())) {
                    TokenBindingMessage.setCnfClaim(jsonWebResponse, authorizationCodeGrant.getTokenBindingHash(), idTokenTokenBindingCnf);
                }
                return null;
            };

            ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(executionContext.getHttpRequest(), authorizationCodeGrant, client, appConfiguration, attributeService);

            executionContext.setIncludeIdTokenClaims(includeIdTokenClaims);
            executionContext.setPreProcessing(JwrService.wrapWithSidFunction(authorizationCodePreProcessing, sessionIdObj != null ? sessionIdObj.getOutsideSid() : null));
            executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

            idToken = authorizationCodeGrant.createIdToken(
                    nonce, authorizationCodeGrant.getAuthorizationCode(), accToken, null, null, executionContext);
        }

        executionContext.getAuditLog().updateOAuth2AuditLog(authorizationCodeGrant, true);

        grantService.removeAuthorizationCode(authorizationCodeGrant.getAuthorizationCode().getCode());

        final String entity = getJSonResponse(accToken, accToken.getTokenType(), accToken.getExpiresIn(), reToken, scope, idToken);
        return response(Response.ok().entity(entity), executionContext.getAuditLog());
    }

    @Nullable
    private Client getClient() {
        SessionClient sessionClient = identity.getSessionClient();
        Client client = null;
        if (sessionClient != null) {
            client = sessionClient.getClient();
            log.debug("Get sessionClient: '{}'", sessionClient);
        }
        return client;
    }

    private User authenticateUser(String username, String password, ExecutionContext executionContext, User user) {

        if (externalResourceOwnerPasswordCredentialsService.isEnabled()) {
            final ExternalResourceOwnerPasswordCredentialsContext context = new ExternalResourceOwnerPasswordCredentialsContext(executionContext);
            context.setUser(user);
            if (externalResourceOwnerPasswordCredentialsService.executeExternalAuthenticate(context)) {
                log.trace("RO PC - User is authenticated successfully by external script.");
                user = context.getUser();
            }
        } else {
            try {
                boolean authenticated = authenticationService.authenticate(username, password);
                if (authenticated) {
                    user = authenticationService.getAuthenticatedUser();
                }
            } catch (AuthenticationException ex) {
                log.trace("Failed to authenticate user ", new RuntimeException("User name or password is invalid"));
            }
        }
        return user;
    }

    private void checkUser(AuthorizationGrant authorizationGrant) {
        if (BooleanUtils.isFalse(appConfiguration.getCheckUserPresenceOnRefreshToken())) {
            return;
        }

        final User user = authorizationGrant.getUser();
        if (user == null || "inactive".equalsIgnoreCase(user.getStatus())) {
            log.trace("The user associated with this grant is not found or otherwise with status=inactive.");
            throw new WebApplicationException(error(400, TokenErrorResponseType.INVALID_GRANT, "The user associated with this grant is not found or otherwise with status=inactive.").build());
        }
    }

    @Nullable
    private RefreshToken createRefreshToken(@NotNull HttpServletRequest request, @NotNull Client client, @NotNull String scope, @NotNull AuthorizationGrant grant, String dpop) {
        if (!isRefreshTokenAllowed(client, scope, grant)) {
            return null;
        }

        checkUser(grant);

        ExecutionContext executionContext = new ExecutionContext(request, null);
        executionContext.setGrant(grant);
        executionContext.setClient(client);
        executionContext.setAttributeService(attributeService);
        executionContext.setAppConfiguration(appConfiguration);
        executionContext.setDpop(dpop);

        final ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, grant, client, appConfiguration, attributeService);
        final int refreshTokenLifetimeInSeconds = externalUpdateTokenService.getRefreshTokenLifetimeInSeconds(context);
        if (refreshTokenLifetimeInSeconds > 0) {
            return grant.createRefreshToken(executionContext, refreshTokenLifetimeInSeconds);
        }
        return grant.createRefreshToken(executionContext);
    }

    /**
     * Processes token request for device code grant type.
     *
     * @param client         Client in process.
     * @param deviceCode     Device code generated in device authn request.
     * @param scope          Scope registered in device authn request.
     * @param request        HttpServletRequest
     * @param response       HttpServletResponse
     * @param auditLog OAuth2AuditLog
     */
    private Response processDeviceCodeGrantType(final Client client, final String deviceCode,
                                                String scope, final HttpServletRequest request,
                                                final HttpServletResponse response, final OAuth2AuditLog auditLog) {
        log.debug("Attempting to find authorizationGrant by deviceCode: '{}'", deviceCode);
        final DeviceCodeGrant deviceCodeGrant = authorizationGrantList.getDeviceCodeGrant(deviceCode);

        log.trace("DeviceCodeGrant : '{}'", deviceCodeGrant);

        if (deviceCodeGrant != null) {
            if (!deviceCodeGrant.getClientId().equals(client.getClientId())) {
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_GRANT, REASON_CLIENT_NOT_AUTHORIZED), auditLog));
            }
            RefreshToken refToken = createRefreshToken(request, client, scope, deviceCodeGrant, null);

            final ExecutionContext executionContext = new ExecutionContext(request, response);
            executionContext.setGrant(deviceCodeGrant);
            executionContext.setCertAsPem(request.getHeader(X_CLIENTCERT));
            executionContext.setClient(client);
            executionContext.setAppConfiguration(appConfiguration);
            executionContext.setAttributeService(attributeService);

            AccessToken accessToken = deviceCodeGrant.createAccessToken(executionContext);

            ExternalUpdateTokenContext context = new ExternalUpdateTokenContext(request, deviceCodeGrant, client, appConfiguration, attributeService);
            context.setExecutionContext(executionContext);


            executionContext.setIncludeIdTokenClaims(false);
            executionContext.setPreProcessing(null);
            executionContext.setPostProcessor(externalUpdateTokenService.buildModifyIdTokenProcessor(context));

            IdToken idToken = deviceCodeGrant.createIdToken(
                    null, null, accessToken, refToken, null, executionContext);

            deviceCodeGrant.checkScopesPolicy(scope);

            log.info("Device authorization in token endpoint processed and return to the client, device_code: {}", deviceCodeGrant.getDeviceCode());

            auditLog.updateOAuth2AuditLog(deviceCodeGrant, true);

            grantService.removeByCode(deviceCodeGrant.getDeviceCode());

            return Response.ok().entity(getJSonResponse(accessToken, accessToken.getTokenType(),
                    accessToken.getExpiresIn(), refToken, scope, idToken)).build();
        } else {
            final DeviceAuthorizationCacheControl cacheData = deviceAuthorizationService.getDeviceAuthzByDeviceCode(deviceCode);
            log.trace("DeviceAuthorizationCacheControl data : '{}'", cacheData);
            tokenRestWebServiceValidator.validateDeviceAuthorization(client, deviceCode, cacheData, auditLog);

            long currentTime = new Date().getTime();
            Long lastAccess = cacheData.getLastAccessControl();
            if (lastAccess == null) {
                lastAccess = currentTime;
            }
            cacheData.setLastAccessControl(currentTime);
            deviceAuthorizationService.saveInCache(cacheData, true, true);

            if (cacheData.getStatus() == DeviceAuthorizationStatus.PENDING) {
                int intervalSeconds = appConfiguration.getBackchannelAuthenticationResponseInterval();
                long timeFromLastAccess = currentTime - lastAccess;

                if (timeFromLastAccess > intervalSeconds * 1000) {
                    log.debug("Access hasn't been granted yet for deviceCode: '{}'", deviceCode);
                    throw new WebApplicationException(response(error(400, TokenErrorResponseType.AUTHORIZATION_PENDING, "User hasn't answered yet"), auditLog));
                } else {
                    log.debug("Slow down protection deviceCode: '{}'", deviceCode);
                    throw new WebApplicationException(response(error(400, TokenErrorResponseType.SLOW_DOWN, "Client is asking too fast the token."), auditLog));
                }
            }
            if (cacheData.getStatus() == DeviceAuthorizationStatus.DENIED) {
                log.debug("The end-user denied the authorization request for deviceCode: '{}'", deviceCode);
                throw new WebApplicationException(response(error(400, TokenErrorResponseType.ACCESS_DENIED, "The end-user denied the authorization request."), auditLog));
            }
            log.debug("The authentication request has expired for deviceCode: '{}'", deviceCode);
            throw new WebApplicationException(response(error(400, TokenErrorResponseType.EXPIRED_TOKEN, "The authentication request has expired"), auditLog));
        }
    }

    private boolean isRefreshTokenAllowed(Client client, String requestedScope, AbstractAuthorizationGrant grant) {
        if (isTrue(appConfiguration.getForceOfflineAccessScopeToEnableRefreshToken()) && !grant.getScopes().contains(ScopeConstants.OFFLINE_ACCESS) && !Strings.nullToEmpty(requestedScope).contains(ScopeConstants.OFFLINE_ACCESS)) {
            return false;
        }
        return Arrays.asList(client.getGrantTypes()).contains(GrantType.REFRESH_TOKEN);
    }

    private void validatePKCE(AuthorizationCodeGrant grant, String codeVerifier, OAuth2AuditLog oAuth2AuditLog) {
        log.trace("PKCE validation, code_verifier: {}, code_challenge: {}, method: {}",
                codeVerifier, grant.getCodeChallenge(), grant.getCodeChallengeMethod());

        if (isTrue(appConfiguration.getRequirePkce()) && (Strings.isNullOrEmpty(codeVerifier) || Strings.isNullOrEmpty(grant.getCodeChallenge()))) {
            if (log.isErrorEnabled()) {
                log.error("PKCE is required but code_challenge or code verifier is blank, grantId: {}, codeVerifier: {}, codeChallenge: {}", grant.getGrantId(), codeVerifier, grant.getCodeChallenge());
            }
            throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_GRANT, "PKCE check fails. Code challenge does not match to request code verifier."), oAuth2AuditLog));
        }

        if (Strings.isNullOrEmpty(grant.getCodeChallenge()) && Strings.isNullOrEmpty(codeVerifier)) {
            return; // if no code challenge then it's valid, no PKCE check
        }

        if (!CodeVerifier.matched(grant.getCodeChallenge(), grant.getCodeChallengeMethod(), codeVerifier)) {
            log.error("PKCE check fails. Code challenge does not match to request code verifier, grantId: {}, codeVerifier: {}", grant.getGrantId(), codeVerifier);
            throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_GRANT, "PKCE check fails. Code challenge does not match to request code verifier."), oAuth2AuditLog));
        }
    }

    private Response response(ResponseBuilder builder, OAuth2AuditLog oAuth2AuditLog) {
        builder.cacheControl(ServerUtil.cacheControl(true, false));
        builder.header("Pragma", "no-cache");

        applicationAuditLogger.sendMessage(oAuth2AuditLog);

        return builder.build();
    }

    private ResponseBuilder error(int status, TokenErrorResponseType type, String reason) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(errorResponseFactory.errorAsJson(type, reason));
    }

    private String runDPoP(HttpServletRequest httpRequest, OAuth2AuditLog oAuth2AuditLog) {
        try {
            String dpopStr = httpRequest.getHeader(TokenRequestParam.DPOP);
            if (StringUtils.isBlank(dpopStr)) return null;

            Jwt dpop = Jwt.parseOrThrow(dpopStr);

            JSONWebKey jwk = JSONWebKey.fromJSONObject(dpop.getHeader().getJwk());
            String dpopJwkThumbprint = jwk.getJwkThumbprint();

            if (dpopJwkThumbprint == null)
                throw new InvalidJwtException("Invalid DPoP Proof Header. The jwk header is not valid.");

            return dpopJwkThumbprint;
        } catch (InvalidJwtException | JWKException | NoSuchAlgorithmException | NoSuchProviderException e) {
            throw new WebApplicationException(response(error(400, TokenErrorResponseType.INVALID_DPOP_PROOF, e.getMessage()), oAuth2AuditLog));
        }
    }

    /**
     * Builds a JSon String with the structure for token issues.
     */
    public String getJSonResponse(AccessToken accessToken, TokenType tokenType,
                                  Integer expiresIn, RefreshToken refreshToken, String scope,
                                  IdToken idToken) {
        JSONObject jsonObj = new JSONObject();
        try {
            jsonObj.put("access_token", accessToken.getCode()); // Required
            jsonObj.put("token_type", tokenType.toString()); // Required
            if (expiresIn != null) { // Optional
                jsonObj.put("expires_in", expiresIn);
            }
            if (refreshToken != null) { // Optional
                jsonObj.put("refresh_token", refreshToken.getCode());
            }
            if (scope != null) { // Optional
                jsonObj.put("scope", scope);
            }
            if (idToken != null) {
                jsonObj.put("id_token", idToken.getCode());
            }
        } catch (JSONException e) {
            log.error(e.getMessage(), e);
        }

        return jsonObj.toString();
    }
}
