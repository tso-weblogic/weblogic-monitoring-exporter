package io.prometheus.wls.rest;
/*
 * Copyright (c) 2017 Oracle and/or its affiliates
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.
 */
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static io.prometheus.wls.rest.StatusCodes.*;

/**
 * @author Russell Gold
 */
public class WebClientImpl implements WebClient {
    private static final char QUOTE = '"';

    private String url;
    private String username;
    private String password;
    private List<BasicHeader> addedHeaders = new ArrayList<>();
    private boolean authorizationHeaderDefined;
    private String setCookieHeader = null;

    WebClientImpl(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public void putHeader(String key, String value) {
        addedHeaders.add(new BasicHeader(key, value));
        if (key.equals("Authorization")) authorizationHeaderDefined = true;
    }

    @Override
    public String doQuery(String jsonQuery) throws IOException {
        try (CloseableHttpClient httpClient = createHttpClient()) {
            HttpPost query = new HttpPost(url);
            query.setEntity(new StringEntity(jsonQuery, ContentType.APPLICATION_JSON));
            CloseableHttpResponse response = httpClient.execute(query);
            processStatusCode(response);
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                try (InputStream inputStream = responseEntity.getContent()) {
                    byte[] buffer = new byte[4096];
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int numBytes = 0;
                    while (numBytes >= 0) {
                        baos.write(buffer, 0, numBytes);
                        numBytes = inputStream.read(buffer);
                    }
                    return baos.toString("UTF-8");
                }
            }
        }
        return null;
    }

    @Override
    public String getSetCookieHeader() {
        return setCookieHeader;
    }

    private void processStatusCode(CloseableHttpResponse response) throws RestQueryException {
        switch (response.getStatusLine().getStatusCode()) {
            case BAD_REQUEST:
                throw new RestQueryException();
            case AUTHENTICATION_REQUIRED:
                throw createAuthenticationChallengeException(response);
            case NOT_AUTHORIZED:
                throw new NotAuthorizedException();
            case SUCCESS:
                setCookieHeader = extractSetCookieHeader(response);
        }
    }

    private BasicAuthenticationChallengeException createAuthenticationChallengeException(CloseableHttpResponse response) {
        return new BasicAuthenticationChallengeException(getRealm(response));
    }

    private String getRealm(CloseableHttpResponse response) {
        Header header = response.getFirstHeader("WWW-Authenticate");
        return extractRealm(header == null ? "" : header.getValue());
    }

    // the value should be of the form <Basic realm="<realm-name>" and we want to extract the realm name
    private String extractRealm(String authenticationHeaderValue) {
        int start = authenticationHeaderValue.indexOf(QUOTE);
        int end = authenticationHeaderValue.indexOf(QUOTE, start+1);
        return start > 0 ? authenticationHeaderValue.substring(start+1, end) : "none";
    }

    private String extractSetCookieHeader(CloseableHttpResponse response) {
        Header header = response.getFirstHeader("Set-Cookie");
        return header == null ? null : header.getValue();

    }

    private CloseableHttpClient createHttpClient() {
        return HttpClientBuilder.create()
                .setDefaultCredentialsProvider(getCredentialsProvider())
                .setDefaultHeaders(getDefaultHeaders())
                .build();
    }

    private CredentialsProvider getCredentialsProvider() {
        if (useUsernamePassword()) {
            return createCredentialsProvider(username, password);
        } else {
            return null;
        }
    }

    private boolean useUsernamePassword() {
        return !isAuthorizationHeaderDefined() && username != null && password != null;
    }

    private boolean isAuthorizationHeaderDefined() {
        return authorizationHeaderDefined;
    }

    private static CredentialsProvider createCredentialsProvider(String username, String password) {
        CredentialsProvider provider = new BasicCredentialsProvider();
        UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(username, password);
        provider.setCredentials(AuthScope.ANY, credentials);
        return provider;
    }

    private Collection<? extends Header> getDefaultHeaders() {
        List<Header> headers = new ArrayList<>(Collections.singleton(new BasicHeader("X-Requested-By", "rest-exporter")));
        headers.addAll(addedHeaders);
        return headers;
    }
}
