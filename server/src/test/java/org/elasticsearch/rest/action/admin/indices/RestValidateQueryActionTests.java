/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.rest.action.admin.indices;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.indices.validate.query.ValidateQueryAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.search.AbstractSearchTestCase;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.test.rest.FakeRestChannel;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.usage.UsageService;
import org.elasticsearch.xcontent.XContentType;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;

public class RestValidateQueryActionTests extends AbstractSearchTestCase {

    private static ThreadPool threadPool = new TestThreadPool(RestValidateQueryActionTests.class.getName());
    private static NodeClient client = new NodeClient(Settings.EMPTY, threadPool);

    private static UsageService usageService = new UsageService();
    private static RestController controller = new RestController(null, client, new NoneCircuitBreakerService(), usageService);
    private static RestValidateQueryAction action = new RestValidateQueryAction();

    /**
     * Configures {@link NodeClient} to stub {@link ValidateQueryAction} transport action.
     * <p>
     * This lower level of validation is out of the scope of this test.
     */
    @BeforeClass
    public static void stubValidateQueryAction() {
        final TaskManager taskManager = new TaskManager(Settings.EMPTY, threadPool, Collections.emptySet());

        final TransportAction<? extends ActionRequest, ? extends ActionResponse> transportAction = new TransportAction<
            ActionRequest,
            ActionResponse>(
                ValidateQueryAction.NAME,
                new ActionFilters(Collections.emptySet()),
                mock(Transport.Connection.class),
                taskManager
            ) {
            @Override
            protected void doExecute(Task task, ActionRequest request, ActionListener<ActionResponse> listener) {}
        };

        final Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions =
            new HashMap<>();
        actions.put(ValidateQueryAction.INSTANCE, transportAction);

        client.initialize(actions, () -> "local", null, new NamedWriteableRegistry(Collections.emptyList()));
        controller.registerHandler(action);
    }

    @Before
    public void ensureCleanContext() {
        // Make sure we have a clean context for each test
        threadPool.getThreadContext().stashContext();
    }

    @AfterClass
    public static void terminateThreadPool() throws InterruptedException {
        terminate(threadPool);

        threadPool = null;
        client = null;

        usageService = null;
        controller = null;
        action = null;
    }

    public void testRestValidateQueryAction() throws Exception {
        // GIVEN a valid query
        final String content = "{\"query\":{\"bool\":{\"must\":{\"term\":{\"user\":\"kimchy\"}}}}}";

        final RestRequest request = createRestRequest(content);
        final FakeRestChannel channel = new FakeRestChannel(request, true, 0);

        // WHEN
        action.handleRequest(request, channel, client);

        // THEN query is valid (i.e. not marked as invalid)
        assertThat(channel.responses().get(), equalTo(0));
        assertThat(channel.errors().get(), equalTo(0));
        assertNull(channel.capturedResponse());
    }

    public void testRestValidateQueryAction_emptyQuery() throws Exception {
        // GIVEN an empty (i.e. invalid) query wrapped into a valid JSON
        final String content = "{\"query\":{}}";

        final RestRequest request = createRestRequest(content);
        final FakeRestChannel channel = new FakeRestChannel(request, true, 0);

        // WHEN
        action.handleRequest(request, channel, client);

        // THEN query is marked as invalid
        assertThat(channel.responses().get(), equalTo(1));
        assertThat(channel.errors().get(), equalTo(0));
        assertThat(channel.capturedResponse().content().utf8ToString(), containsString("{\"valid\":false}"));
    }

    public void testRestValidateQueryAction_malformedQuery() throws Exception {
        // GIVEN an invalid query due to a malformed JSON
        final String content = "{malformed_json}";

        final RestRequest request = createRestRequest(content);
        final FakeRestChannel channel = new FakeRestChannel(request, true, 0);

        // WHEN
        action.handleRequest(request, channel, client);

        // THEN query is marked as invalid
        assertThat(channel.responses().get(), equalTo(1));
        assertThat(channel.errors().get(), equalTo(0));
        assertThat(channel.capturedResponse().content().utf8ToString(), containsString("{\"valid\":false}"));
    }

    private RestRequest createRestRequest(String content) {
        return new FakeRestRequest.Builder(xContentRegistry()).withPath("index1/type1/_validate/query")
            .withParams(emptyMap())
            .withContent(new BytesArray(content), XContentType.JSON)
            .build();
    }

    public void testTypeInPath() {
        RestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET)
            .withPath("/some_index/some_type/_validate/query")
            .build();

        performRequest(request);
        assertWarnings(RestValidateQueryAction.TYPES_DEPRECATION_MESSAGE);
    }

    public void testTypeParameter() {
        Map<String, String> params = new HashMap<>();
        params.put("type", "some_type");
        RestRequest request = new FakeRestRequest.Builder(xContentRegistry()).withMethod(RestRequest.Method.GET)
            .withPath("_validate/query")
            .withParams(params)
            .build();

        performRequest(request);
        assertWarnings(RestValidateQueryAction.TYPES_DEPRECATION_MESSAGE);
    }

    private void performRequest(RestRequest request) {
        RestChannel channel = new FakeRestChannel(request, false, 1);
        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        controller.dispatchRequest(request, channel, threadContext);
    }
}
