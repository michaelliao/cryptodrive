package org.puppylab.cryptodrive.core.web.pkce;

public class GoogleAuthenticator extends OAuthAuthenticator {

    @Override
    protected String getScope() {
        return "openid email profile";
    }

    @Override
    protected String getAuthUrl() {
        return "https://accounts.google.com/o/oauth2/v2/auth";
    }

    @Override
    protected String getTokenUrl() {
        return "https://oauth2.googleapis.com/token";
    }
}
