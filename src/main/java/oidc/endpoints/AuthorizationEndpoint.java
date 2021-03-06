package oidc.endpoints;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.ResponseMode;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.OIDCResponseTypeValue;
import com.nimbusds.openid.connect.sdk.Prompt;
import oidc.exceptions.InvalidGrantException;
import oidc.exceptions.InvalidScopeException;
import oidc.exceptions.RedirectMismatchException;
import oidc.exceptions.UnsupportedPromptValueException;
import oidc.model.AccessToken;
import oidc.model.AuthorizationCode;
import oidc.model.EncryptedTokenValue;
import oidc.model.OpenIDClient;
import oidc.model.ProvidedRedirectURI;
import oidc.model.User;
import oidc.repository.AccessTokenRepository;
import oidc.repository.AuthorizationCodeRepository;
import oidc.repository.OpenIDClientRepository;
import oidc.repository.RefreshTokenRepository;
import oidc.repository.UserRepository;
import oidc.secure.JWTRequest;
import oidc.secure.TokenGenerator;
import oidc.user.OidcSamlAuthentication;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class AuthorizationEndpoint implements OidcEndpoint {

    private static final Log LOG = LogFactory.getLog(AuthorizationEndpoint.class);
    private static List<String> forFreeOpenIDScopes = Arrays.asList("profile", "email", "address", "phone");

    private TokenGenerator tokenGenerator;
    private AuthorizationCodeRepository authorizationCodeRepository;
    private AccessTokenRepository accessTokenRepository;
    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private OpenIDClientRepository openIDClientRepository;

    @Autowired
    public AuthorizationEndpoint(AuthorizationCodeRepository authorizationCodeRepository,
                                 AccessTokenRepository accessTokenRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 UserRepository userRepository,
                                 OpenIDClientRepository openIDClientRepository,
                                 TokenGenerator tokenGenerator) {
        this.authorizationCodeRepository = authorizationCodeRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.openIDClientRepository = openIDClientRepository;
        this.tokenGenerator = tokenGenerator;
    }

    @GetMapping("/oidc/authorize")
    public ModelAndView authorize(@RequestParam MultiValueMap<String, String> parameters,
                                  Authentication authentication) throws ParseException, JOSEException, IOException, NoSuchProviderException, NoSuchAlgorithmException, CertificateException, BadJOSEException, java.text.ParseException, URISyntaxException {
        LOG.info(String.format("/oidc/authorize %s %s", authentication.getDetails(), parameters));
        //We do not provide SSO as does EB not - up to the identity provider
        logout();

        OidcSamlAuthentication samlAuthentication = (OidcSamlAuthentication) authentication;
        AuthorizationRequest authenticationRequest = AuthorizationRequest.parse(parameters);

        Scope scope = authenticationRequest.getScope();
        boolean isOpenIdClient = scope != null && isOpenIDRequest(scope.toStringList());

        OpenIDClient client = openIDClientRepository.findByClientId(authenticationRequest.getClientID().getValue());

        if (isOpenIdClient) {
            AuthenticationRequest oidcAuthenticationRequest = AuthenticationRequest.parse(parameters);
            if (oidcAuthenticationRequest.specifiesRequestObject()) {
                oidcAuthenticationRequest = JWTRequest.parse(oidcAuthenticationRequest, client);
                LOG.debug("/oidc/authorize with JWT 'request'");
            }
            //swap reference
            authenticationRequest = oidcAuthenticationRequest;
        }
        State state = authenticationRequest.getState();
        String redirectURI = validateRedirectionURI(authenticationRequest, client).getRedirectURI();


        List<String> scopes = validateScopes(authenticationRequest, client);
        ResponseType responseType = validateGrantType(authenticationRequest, client);

        User user = samlAuthentication.getUser();
        ResponseMode responseMode = authenticationRequest.impliedResponseMode();

        if (responseType.impliesCodeFlow()) {
            AuthorizationCode authorizationCode = createAndSaveAuthorizationCode(authenticationRequest, client, user);
            LOG.info(String.format("Returning authorizationCode flow %s %s", ResponseMode.FORM_POST, redirectURI));
            if (responseMode.equals(ResponseMode.FORM_POST)) {
                Map<String, String> body = new HashMap<>();
                body.put("redirect_uri", redirectURI);
                body.put("code", authorizationCode.getCode());
                if (state != null && StringUtils.hasText(state.getValue())) {
                    body.put("state", state.getValue());
                }
                return new ModelAndView("form_post", body);
            }
            return new ModelAndView(new RedirectView(authorizationRedirect(redirectURI, state,
                    authorizationCode.getCode(), responseMode.equals(ResponseMode.FRAGMENT))));
        } else if (responseType.impliesImplicitFlow() || responseType.impliesHybridFlow()) {
            if (responseType.impliesImplicitFlow()) {
                //User information is encrypted in access token
                userRepository.delete(user);
            }
            Map<String, Object> body = authorizationEndpointResponse(user, client, authenticationRequest, scopes, responseType, state);

            LOG.info(String.format("Returning implicit flow %s %s", ResponseMode.FORM_POST, redirectURI));
            if (responseMode.equals(ResponseMode.FORM_POST)) {
                body.put("redirect_uri", redirectURI);
                return new ModelAndView("form_post", body);
            }
            if (responseMode.equals(ResponseMode.QUERY)) {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectURI);
                body.forEach(builder::queryParam);
                return new ModelAndView(new RedirectView(builder.toUriString()));
            }
            if (responseMode.equals(ResponseMode.FRAGMENT)) {
                UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectURI);
                String fragment = body.entrySet().stream().map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue())).collect(Collectors.joining("&"));
                builder.fragment(fragment);
                return new ModelAndView(new RedirectView(builder.toUriString()));
            }
            throw new IllegalArgumentException("Response mode " + responseMode + " not supported");
        }
        throw new IllegalArgumentException("Not yet implemented response_type: " + responseType.toString());
    }

    public static ResponseType validateGrantType(AuthorizationRequest authorizationRequest, OpenIDClient client) {
        ResponseType responseType = authorizationRequest.getResponseType();
        List<String> grants = client.getGrants();
        if ((responseType.impliesImplicitFlow() || responseType.impliesHybridFlow()) && !grants.contains(GrantType.IMPLICIT.getValue())) {
            throw new InvalidGrantException(String.format("Grant types %s does not allow for implicit / hybrid flow", grants));
        }
        if (responseType.impliesCodeFlow() && !grants.contains(GrantType.AUTHORIZATION_CODE.getValue())) {
            throw new InvalidGrantException(String.format("Grant types %s does not allow for authorization code flow", grants));
        }
        return responseType;
    }

    private Map<String, Object> authorizationEndpointResponse(User user, OpenIDClient client, AuthorizationRequest authorizationRequest,
                                                              List<String> scopes, ResponseType responseType, State state) throws JOSEException, NoSuchProviderException, NoSuchAlgorithmException {
        Map<String, Object> result = new LinkedHashMap<>();
        EncryptedTokenValue encryptedAccessToken = tokenGenerator.generateAccessTokenWithEmbeddedUserInfo(user, client);
        String accessTokenValue = encryptedAccessToken.getValue();
        if (responseType.contains(ResponseType.Value.TOKEN.getValue()) || !isOpenIDRequest(authorizationRequest)) {
            getAccessTokenRepository().insert(new AccessToken(accessTokenValue, user.getSub(), client.getClientId(), scopes,
                    encryptedAccessToken.getKeyId(), accessTokenValidity(client), false, null));
            result.put("access_token", accessTokenValue);
            result.put("token_type", "Bearer");
        }
        if (responseType.contains(ResponseType.Value.CODE.getValue())) {
            AuthorizationCode authorizationCode = createAndSaveAuthorizationCode(authorizationRequest, client, user);
            result.put("code", authorizationCode.getCode());
        }
        if (responseType.contains(OIDCResponseTypeValue.ID_TOKEN.getValue()) && isOpenIDRequest(scopes) && isOpenIDRequest(authorizationRequest)) {
            AuthenticationRequest authenticationRequest = (AuthenticationRequest) authorizationRequest;
            List<String> claims = getClaims(authorizationRequest);
            String idToken = getTokenGenerator().generateIDTokenForAuthorizationEndpoint(
                    user, client, authenticationRequest.getNonce(), responseType, accessTokenValue, claims,
                    Optional.ofNullable((String) result.get("code")), state);
            result.put("id_token", idToken);
        }
        result.put("expires_in", client.getAccessTokenValidity());
        if (state != null) {
            result.put("state", state.getValue());
        }
        return result;
    }

    private AuthorizationCode createAndSaveAuthorizationCode(AuthorizationRequest authenticationRequest, OpenIDClient client, User user) {
        AuthorizationCode authorizationCode = constructAuthorizationCode(authenticationRequest, client, user);
        authorizationCodeRepository.insert(authorizationCode);
        return authorizationCode;
    }


    public static ProvidedRedirectURI validateRedirectionURI(AuthorizationRequest authenticationRequest, OpenIDClient client) throws UnsupportedEncodingException {
        URI redirectionURI = authenticationRequest.getRedirectionURI();
        if (redirectionURI == null) {
            return client.getRedirectUrls().stream().findFirst().map(s -> new ProvidedRedirectURI(s, false))
                    .orElseThrow(() ->
                            new IllegalArgumentException(String.format("Client %s must have at least one redirectURI configured to use the Authorization flow",
                                    client.getClientId())));
        }

        String redirectURI = URLDecoder.decode(redirectionURI.toString(), "UTF-8");

        List<String> redirectUrls = client.getRedirectUrls();
        if (CollectionUtils.isEmpty(redirectUrls) || !redirectUrls.contains(redirectURI)) {
            throw new RedirectMismatchException(
                    String.format("Client %s with registered redirect URI's %s requested authorization with redirectURI %s",
                            client.getClientId(), redirectUrls, redirectURI));
        }
        return new ProvidedRedirectURI(redirectURI, true);
    }

    private String authorizationRedirect(String redirectionURI, State state, String code, boolean isFragment) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(redirectionURI);
        boolean hasState = state != null && StringUtils.hasText(state.getValue());
        if (isFragment) {
            String fragment = "code=" + code;
            if (hasState) {
                fragment = fragment.concat("&state=" + state.getValue());
            }
            builder.fragment(fragment);
        } else {
            builder.queryParam("code", code);
            if (hasState) {
                builder.queryParam("state", state.getValue());
            }
        }
        return builder.toUriString();
    }

    private static final String unsupportedPromptMessage = "Unsupported Prompt value";

    public static String validatePrompt(HttpServletRequest request) {
        String prompt = request.getParameter("prompt");
        //We trigger an error is prompt is present and not equals 'login'
        if (StringUtils.hasText(prompt) && !prompt.equals("login")) {
            throw new UnsupportedPromptValueException(unsupportedPromptValue(prompt), unsupportedPromptMessage);
        }
        return prompt;
    }

    public static String validatePrompt(Prompt prompt) {
        //We trigger an error is prompt is present and not equals 'login'
        if (prompt != null && !prompt.toString().contains("login")) {
            throw new UnsupportedPromptValueException(unsupportedPromptValue(prompt.toString()), unsupportedPromptMessage);
        }
        return prompt != null ? prompt.toString() : null;
    }

    private static String unsupportedPromptValue(String prompt) {
        switch (prompt) {
            case "none":
                return "interaction_required";
            case "consent":
                return "consent_required";
            case "select_account":
                return "account_selection_required";
            default:
                return "invalid_request";
        }
    }

    public static List<String> validateScopes(AuthorizationRequest authorizationRequest, OpenIDClient client) {
        Scope scope = authorizationRequest.getScope();
        List<String> requestedScopes = scope != null ? scope.toStringList() : Collections.emptyList();
        List<String> scopes = new ArrayList<>(client.getScopes());
        scopes.addAll(forFreeOpenIDScopes);
        if (!scopes.containsAll(requestedScopes)) {
            List<String> missingScopes = requestedScopes.stream().filter(s -> !scopes.contains(s)).collect(Collectors.toList());
            throw new InvalidScopeException(
                    String.format("Scope(s) %s are not allowed for %s. Allowed scopes: %s",
                            missingScopes, client.getClientId(), client.getScopes()));
        }
        return requestedScopes;
    }

    @Override
    public TokenGenerator getTokenGenerator() {
        return tokenGenerator;
    }

    @Override
    public AccessTokenRepository getAccessTokenRepository() {
        return accessTokenRepository;
    }

    @Override
    public RefreshTokenRepository getRefreshTokenRepository() {
        return refreshTokenRepository;
    }

}
