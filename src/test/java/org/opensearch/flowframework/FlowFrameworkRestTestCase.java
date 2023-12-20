/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.flowframework;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.rest.SecureRestClientBuilder;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.MediaType;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.flowframework.common.CommonValue;
import org.opensearch.flowframework.model.ProvisioningProgress;
import org.opensearch.flowframework.model.ResourceCreated;
import org.opensearch.flowframework.model.State;
import org.opensearch.flowframework.model.Template;
import org.opensearch.flowframework.model.WorkflowState;
import org.opensearch.test.rest.OpenSearchRestTestCase;
import org.junit.AfterClass;
import org.junit.Before;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_SSL_HTTP_ENABLED;
import static org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_FILEPATH;
import static org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_KEYPASSWORD;
import static org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_PASSWORD;
import static org.opensearch.commons.ConfigConstants.OPENSEARCH_SECURITY_SSL_HTTP_PEMCERT_FILEPATH;
import static org.opensearch.core.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.flowframework.common.CommonValue.WORKFLOW_URI;

/**
 * Base rest integration test class, supports security enabled/disabled cluster
 */
public abstract class FlowFrameworkRestTestCase extends OpenSearchRestTestCase {

    @Before
    public void setUpSettings() throws Exception {

        if (!indexExistsWithAdminClient(".plugins-ml-config")) {

            // Initial cluster set up

            // Enable Flow Framework Plugin Rest APIs
            Response response = TestHelpers.makeRequest(
                client(),
                "PUT",
                "_cluster/settings",
                null,
                "{\"transient\":{\"plugins.flow_framework.enabled\":true}}",
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, ""))
            );
            assertEquals(200, response.getStatusLine().getStatusCode());

            // Enable ML Commons to run on non-ml nodes
            response = TestHelpers.makeRequest(
                client(),
                "PUT",
                "_cluster/settings",
                null,
                "{\"persistent\":{\"plugins.ml_commons.only_run_on_ml_node\":false}}",
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, ""))
            );
            assertEquals(200, response.getStatusLine().getStatusCode());

            // Enable local model registration via URL
            response = TestHelpers.makeRequest(
                client(),
                "PUT",
                "_cluster/settings",
                null,
                "{\"persistent\":{\"plugins.ml_commons.allow_registering_model_via_url\":true}}",
                ImmutableList.of(new BasicHeader(HttpHeaders.USER_AGENT, ""))
            );
            assertEquals(200, response.getStatusLine().getStatusCode());

            // Need a delay here on 2.x or next line consistently fails tests.
            // TODO: figure out know why we need this and we should pursue a better option that doesn't require HTTP5
            Thread.sleep(10000);
            // Ensure .plugins-ml-config is created before proceeding with integration tests
            assertBusy(() -> { assertTrue(indexExistsWithAdminClient(".plugins-ml-config")); });

        }

    }

    protected boolean isHttps() {
        boolean isHttps = Optional.ofNullable(System.getProperty("https")).map("true"::equalsIgnoreCase).orElse(false);
        if (isHttps) {
            // currently only external cluster is supported for security enabled testing
            if (!Optional.ofNullable(System.getProperty("tests.rest.cluster")).isPresent()) {
                throw new RuntimeException("cluster url should be provided for security enabled testing");
            }
        }

        return isHttps;
    }

    @Override
    protected Settings restClientSettings() {
        return super.restClientSettings();
    }

    @Override
    protected String getProtocol() {
        return isHttps() ? "https" : "http";
    }

    @Override
    protected Settings restAdminSettings() {
        return Settings.builder()
            // disable the warning exception for admin client since it's only used for cleanup.
            .put("strictDeprecationMode", false)
            .put("http.port", 9200)
            .put(OPENSEARCH_SECURITY_SSL_HTTP_ENABLED, isHttps())
            .put(OPENSEARCH_SECURITY_SSL_HTTP_PEMCERT_FILEPATH, "sample.pem")
            .put(OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_FILEPATH, "test-kirk.jks")
            .put(OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_PASSWORD, "changeit")
            .put(OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_KEYPASSWORD, "changeit")
            .build();
    }

    // Utility fn for deleting indices. Should only be used when not allowed in a regular context
    // (e.g., deleting system indices)
    protected static void deleteIndexWithAdminClient(String name) throws IOException {
        Request request = new Request("DELETE", "/" + name);
        adminClient().performRequest(request);
    }

    // Utility fn for checking if an index exists. Should only be used when not allowed in a regular context
    // (e.g., checking existence of system indices)
    protected static boolean indexExistsWithAdminClient(String indexName) throws IOException {
        Request request = new Request("HEAD", "/" + indexName);
        Response response = adminClient().performRequest(request);
        return RestStatus.OK.getStatus() == response.getStatusLine().getStatusCode();
    }

    @Override
    protected RestClient buildClient(Settings settings, HttpHost[] hosts) throws IOException {
        boolean strictDeprecationMode = settings.getAsBoolean("strictDeprecationMode", true);
        RestClientBuilder builder = RestClient.builder(hosts);
        if (isHttps()) {
            String keystore = settings.get(OPENSEARCH_SECURITY_SSL_HTTP_KEYSTORE_FILEPATH);
            if (Objects.nonNull(keystore)) {
                URI uri = null;
                try {
                    uri = this.getClass().getClassLoader().getResource("security/sample.pem").toURI();
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                Path configPath = PathUtils.get(uri).getParent().toAbsolutePath();
                return new SecureRestClientBuilder(settings, configPath).build();
            } else {
                configureHttpsClient(builder, settings);
                builder.setStrictDeprecationMode(strictDeprecationMode);
                return builder.build();
            }

        } else {
            configureClient(builder, settings);
            builder.setStrictDeprecationMode(strictDeprecationMode);
            return builder.build();
        }

    }

    // Cleans up resources after all test execution has been completed
    @SuppressWarnings("unchecked")
    @AfterClass
    protected static void wipeAllSystemIndices() throws IOException {
        Response response = adminClient().performRequest(new Request("GET", "/_cat/indices?format=json&expand_wildcards=all"));
        MediaType xContentType = MediaType.fromMediaType(response.getEntity().getContentType().getValue());
        try (
            XContentParser parser = xContentType.xContent()
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    response.getEntity().getContent()
                )
        ) {
            XContentParser.Token token = parser.nextToken();
            List<Map<String, Object>> parserList = null;
            if (token == XContentParser.Token.START_ARRAY) {
                parserList = parser.listOrderedMap().stream().map(obj -> (Map<String, Object>) obj).collect(Collectors.toList());
            } else {
                parserList = Collections.singletonList(parser.mapOrdered());
            }

            for (Map<String, Object> index : parserList) {
                String indexName = (String) index.get("index");
                if (indexName != null && !".opendistro_security".equals(indexName)) {
                    adminClient().performRequest(new Request("DELETE", "/" + indexName));
                }
            }
        }
    }

    protected static void configureHttpsClient(RestClientBuilder builder, Settings settings) throws IOException {
        Map<String, String> headers = ThreadContext.buildDefaultHeaders(settings);
        Header[] defaultHeaders = new Header[headers.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            defaultHeaders[i++] = new BasicHeader(entry.getKey(), entry.getValue());
        }
        builder.setDefaultHeaders(defaultHeaders);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            String userName = Optional.ofNullable(System.getProperty("user"))
                .orElseThrow(() -> new RuntimeException("user name is missing"));
            String password = Optional.ofNullable(System.getProperty("password"))
                .orElseThrow(() -> new RuntimeException("password is missing"));
            final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, password));
            try {
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                    // disable the certificate since our testing cluster just uses the default security configuration
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .setSSLContext(SSLContextBuilder.create().loadTrustMaterial(null, (chains, authType) -> true).build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        final String socketTimeoutString = settings.get(CLIENT_SOCKET_TIMEOUT);
        final TimeValue socketTimeout = TimeValue.parseTimeValue(
            socketTimeoutString == null ? "60s" : socketTimeoutString,
            CLIENT_SOCKET_TIMEOUT
        );
        builder.setRequestConfigCallback(conf -> {
            int timeout = Math.toIntExact(socketTimeout.getMillis());
            conf.setConnectTimeout(timeout);
            conf.setSocketTimeout(timeout);
            return conf;
        });
        if (settings.hasValue(CLIENT_PATH_PREFIX)) {
            builder.setPathPrefix(settings.get(CLIENT_PATH_PREFIX));
        }
    }

    /**
     * wipeAllIndices won't work since it cannot delete security index. Use wipeAllSystemIndices instead.
     */
    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    /**
     * Required to persist cluster settings between test executions
     */
    @Override
    protected boolean preserveClusterSettings() {
        return true;
    }

    /**
     * Helper method to invoke the Create Workflow Rest Action
     * @param template the template to create
     * @throws Exception if the request fails
     * @return a rest response
     */
    protected Response createWorkflow(Template template) throws Exception {
        return TestHelpers.makeRequest(client(), "POST", WORKFLOW_URI, ImmutableMap.of(), template.toJson(), null);
    }

    /**
     * Helper method to invoke the Create Workflow Rest Action with dry run validation
     * @param template the template to create
     * @throws Exception if the request fails
     * @return a rest response
     */
    protected Response createWorkflowDryRun(Template template) throws Exception {
        return TestHelpers.makeRequest(client(), "POST", WORKFLOW_URI + "?dryrun=true", ImmutableMap.of(), template.toJson(), null);
    }

    /**
     * Helper method to invoke the Update Workflow API
     * @param workflowId the document id
     * @param template the template used to update
     * @throws Exception if the request fails
     * @return a rest response
     */
    protected Response updateWorkflow(String workflowId, Template template) throws Exception {
        return TestHelpers.makeRequest(
            client(),
            "PUT",
            String.format(Locale.ROOT, "%s/%s", WORKFLOW_URI, workflowId),
            ImmutableMap.of(),
            template.toJson(),
            null
        );
    }

    /**
     * Helper method to invoke the Provision Workflow Rest Action
     * @param workflowId the workflow ID to provision
     * @throws Exception if the request fails
     * @return a rest response
     */
    protected Response provisionWorkflow(String workflowId) throws Exception {
        return TestHelpers.makeRequest(
            client(),
            "POST",
            String.format(Locale.ROOT, "%s/%s/%s", WORKFLOW_URI, workflowId, "_provision"),
            ImmutableMap.of(),
            "",
            null
        );
    }

    /**
     * Helper method to invoke the Get Workflow Rest Action
     * @param workflowId the workflow ID to get the status
     * @param all verbose status flag
     * @throws Exception if the request fails
     * @return rest response
     */
    protected Response getWorkflowStatus(String workflowId, boolean all) throws Exception {
        return TestHelpers.makeRequest(
            client(),
            "GET",
            String.format(Locale.ROOT, "%s/%s/%s?all=%s", WORKFLOW_URI, workflowId, "_status", all),
            ImmutableMap.of(),
            "",
            null
        );

    }

    /**
     * Helper method to invoke the Search Workflow Rest Action with the given query
     * @param query the search query
     * @return rest response
     * @throws Exception if the request fails
     */
    protected SearchResponse searchWorkflows(String query) throws Exception {

        // Execute search
        Response restSearchResponse = TestHelpers.makeRequest(
            client(),
            "GET",
            String.format(Locale.ROOT, "%s/_search", WORKFLOW_URI),
            ImmutableMap.of(),
            query,
            null
        );
        assertEquals(RestStatus.OK, TestHelpers.restStatus(restSearchResponse));

        // Parse entity content into SearchResponse
        MediaType mediaType = MediaType.fromMediaType(restSearchResponse.getEntity().getContentType().getValue());
        try (
            XContentParser parser = mediaType.xContent()
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    restSearchResponse.getEntity().getContent()
                )
        ) {
            return SearchResponse.fromXContent(parser);
        }
    }

    /**
     * Helper method to invoke the Get Workflow Rest Action and assert the provisioning and state status
     * @param workflowId the workflow ID to get the status
     * @param stateStatus the state status name
     * @param provisioningStatus the provisioning status name
     * @throws Exception if the request fails
     */
    protected void getAndAssertWorkflowStatus(String workflowId, State stateStatus, ProvisioningProgress provisioningStatus)
        throws Exception {
        Response response = getWorkflowStatus(workflowId, true);
        assertEquals(RestStatus.OK, TestHelpers.restStatus(response));

        Map<String, Object> responseMap = entityAsMap(response);
        assertEquals(stateStatus.name(), (String) responseMap.get(CommonValue.STATE_FIELD));
        assertEquals(provisioningStatus.name(), (String) responseMap.get(CommonValue.PROVISIONING_PROGRESS_FIELD));

    }

    /**
     * Helper method to wait until a workflow provisioning has completed and retrieve any resources created
     * @param workflowId the workflow id to retrieve resources from
     * @param timeout the max wait time in seconds
     * @return a list of created resources
     * @throws Exception if the request fails
     */
    protected List<ResourceCreated> getResourcesCreated(String workflowId, int timeout) throws Exception {

        // wait and ensure state is completed/done
        assertBusy(
            () -> { getAndAssertWorkflowStatus(workflowId, State.COMPLETED, ProvisioningProgress.DONE); },
            timeout,
            TimeUnit.SECONDS
        );

        Response response = getWorkflowStatus(workflowId, true);

        // Parse workflow state from response and retreieve resources created
        MediaType mediaType = MediaType.fromMediaType(response.getEntity().getContentType().getValue());
        try (
            XContentParser parser = mediaType.xContent()
                .createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    response.getEntity().getContent()
                )
        ) {
            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
            WorkflowState workflowState = WorkflowState.parse(parser);
            return workflowState.resourcesCreated();
        }
    }
}
