package org.keycloak.jaxrs;

import org.keycloak.AbstractOAuthClient;
import org.keycloak.OAuth2Constants;
import org.keycloak.representations.AccessTokenResponse;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Helper code to obtain oauth access tokens via browser redirects
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class JaxrsOAuthClient extends AbstractOAuthClient {
    private final static Logger logger = Logger.getLogger("" + JaxrsOAuthClient.class);
    protected Client client;

    /**
     * closes client
     */
    public void stop() {
        if (client != null) client.close();
    }
    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public String resolveBearerToken(String redirectUri, String code) {
        redirectUri = stripOauthParametersFromRedirect(redirectUri);
        Form codeForm = new Form()
                .param(OAuth2Constants.GRANT_TYPE, "authorization_code")
                .param(OAuth2Constants.CODE, code)
                .param(OAuth2Constants.CLIENT_ID, clientId)
                .param(OAuth2Constants.REDIRECT_URI, redirectUri);
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            codeForm.param(entry.getKey(), entry.getValue());
        }
        Response res = client.target(codeUrl).request().post(Entity.form(codeForm));
        try {
            if (res.getStatus() == 400) {
                throw new BadRequestException();
            } else if (res.getStatus() != 200) {
                throw new InternalServerErrorException(new Exception("Unknown error when getting acess token"));
            }
            AccessTokenResponse tokenResponse = res.readEntity(AccessTokenResponse.class);
            return tokenResponse.getToken();
        } finally {
            res.close();
        }
    }
    public Response redirect(UriInfo uriInfo, String redirectUri) {
        String state = getStateCode();

        UriBuilder uriBuilder = UriBuilder.fromUri(authUrl)
                .queryParam(OAuth2Constants.CLIENT_ID, clientId)
                .queryParam(OAuth2Constants.REDIRECT_URI, redirectUri)
                .queryParam(OAuth2Constants.STATE, state);
        if (scope != null) {
            uriBuilder.queryParam(OAuth2Constants.SCOPE, scope);
        }

        URI url = uriBuilder.build();

        NewCookie cookie = new NewCookie(getStateCookieName(), state, getStateCookiePath(uriInfo), null, null, -1, isSecure, true);
        logger.fine("NewCookie: " + cookie.toString());
        logger.fine("Oauth Redirect to: " + url);
        return Response.status(302)
                .location(url)
                .cookie(cookie).build();
    }

    public String getStateCookiePath(UriInfo uriInfo) {
        if (stateCookiePath != null) return stateCookiePath;
        return uriInfo.getBaseUri().getRawPath();
    }

    public String getBearerToken(UriInfo uriInfo, HttpHeaders headers) throws BadRequestException, InternalServerErrorException {
        String error = getError(uriInfo);
        if (error != null) throw new BadRequestException(new Exception("OAuth error: " + error));
        checkStateCookie(uriInfo, headers);
        String code = getAccessCode(uriInfo);
        if (code == null) throw new BadRequestException(new Exception("code parameter was null"));
        return resolveBearerToken(uriInfo.getRequestUri().toString(), code);
    }

    public String getError(UriInfo uriInfo) {
        return uriInfo.getQueryParameters().getFirst(OAuth2Constants.ERROR);
    }

    public String getAccessCode(UriInfo uriInfo) {
        return uriInfo.getQueryParameters().getFirst(OAuth2Constants.CODE);
    }

    public void checkStateCookie(UriInfo uriInfo, HttpHeaders headers) {
        Cookie stateCookie = headers.getCookies().get(stateCookieName);
        if (stateCookie == null) throw new BadRequestException("state cookie not set");
        String state = uriInfo.getQueryParameters().getFirst(OAuth2Constants.STATE);
        if (state == null) throw new BadRequestException("state parameter was null");
        if (!state.equals(stateCookie.getValue())) {
            throw new BadRequestException("state parameter invalid");
        }
    }
}
