/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@code requestHandler}/{@code requestHandlers} builder methods on
 * the {@link McpServer} specifications. The tests use
 * {@link MockMcpServerTransportProvider} and {@link RecordingStatelessTransport} to drive
 * the dispatchers directly without a real network stack, exercising the same code path
 * that the Stdio, Servlet SSE, Streamable HTTP and Stateless transports use to invoke
 * request handlers.
 */
class CustomRequestHandlerTests {

	private static final Implementation SERVER_INFO = Implementation.builder("test-server", "1.0.0").build();

	private static final Implementation CLIENT_INFO = Implementation.builder("test-client", "1.0.0").build();

	private static final String CUSTOM_METHOD = "myapp/echo";

	private static McpSchema.JSONRPCRequest initRequest() {
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, UUID.randomUUID().toString(),
				InitializeRequest
					.builder(ProtocolVersions.MCP_2025_11_25, ClientCapabilities.builder().build(), CLIENT_INFO)
					.build());
	}

	private static McpSchema.JSONRPCNotification initializedNotification() {
		return new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED);
	}

	private static McpSchema.JSONRPCRequest toolsListRequest() {
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, UUID.randomUUID().toString(), Map.of());
	}

	private static McpSchema.JSONRPCRequest customMethodRequest(String payload) {
		return new McpSchema.JSONRPCRequest(CUSTOM_METHOD, UUID.randomUUID().toString(), Map.of("payload", payload));
	}

	private static McpAsyncServer buildServer(MockMcpServerTransportProvider transportProvider) {
		return McpServer.async(transportProvider).serverInfo(SERVER_INFO).build();
	}

	// ---------------------------------------
	// Override defaults
	// ---------------------------------------

	@Test
	void overrideToolsListReturnsCustomResult() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		Tool visibleTool = Tool.builder("visible-tool", EMPTY_JSON_SCHEMA).title("visible").build();
		Tool hiddenTool = Tool.builder("hidden-tool", EMPTY_JSON_SCHEMA).title("hidden").build();

		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(visibleTool)
				.callHandler((exchange,
						request) -> Mono.just(McpSchema.CallToolResult.builder()
							.content(List.of(new TextContent("visible result")))
							.isError(false)
							.build()))
				.build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(hiddenTool)
				.callHandler((exchange,
						request) -> Mono.just(McpSchema.CallToolResult.builder()
							.content(List.of(new TextContent("hidden result")))
							.isError(false)
							.build()))
				.build())
			.requestHandler(McpSchema.METHOD_TOOLS_LIST,
					(exchange, params) -> Mono.just(ListToolsResult.builder(List.of(visibleTool)).build()))
			.build();

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(toolsListRequest());

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNull();
		ListToolsResult result = McpJsonDefaults.getMapper().convertValue(response.result(), ListToolsResult.class);
		assertThat(result.tools()).extracting(Tool::name).containsExactly("visible-tool");

		server.closeGracefully().block();
	}

	// ---------------------------------------
	// Non-standard methods
	// ---------------------------------------

	@Test
	void customNonStandardMethodIsInvoked() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.requestHandler(CUSTOM_METHOD, (exchange, params) -> Mono.just(Map.of("echo", params)))
			.build();

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(customMethodRequest("hello"));

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) response.result();
		assertThat(result).containsKey("echo");

		server.closeGracefully().block();
	}

	@Test
	void unknownMethodReturnsMethodNotFound() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);
		McpAsyncServer server = buildServer(transportProvider);

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(
				new McpSchema.JSONRPCRequest("not/a/real/method", UUID.randomUUID().toString(), Map.of()));

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);

		server.closeGracefully().block();
	}

	// ---------------------------------------
	// Map overload
	// ---------------------------------------

	@Test
	void requestHandlersMapRegistersMultipleHandlers() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		String firstMethod = "myapp/first";
		String secondMethod = "myapp/second";

		Map<String, McpRequestHandler<?>> handlers = Map.of(firstMethod,
				(exchange, params) -> Mono.just(Map.of("which", "first")), secondMethod,
				(exchange, params) -> Mono.just(Map.of("which", "second")));

		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.requestHandlers(handlers)
			.build();

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider
			.simulateIncomingMessage(new McpSchema.JSONRPCRequest(firstMethod, UUID.randomUUID().toString(), Map.of()));
		transportProvider.simulateIncomingMessage(
				new McpSchema.JSONRPCRequest(secondMethod, UUID.randomUUID().toString(), Map.of()));

		List<McpSchema.JSONRPCMessage> sent = transport.getAllSentMessages();
		assertThat(sent).hasSizeGreaterThanOrEqualTo(2);
		McpSchema.JSONRPCResponse firstResp = (McpSchema.JSONRPCResponse) sent.get(sent.size() - 2);
		McpSchema.JSONRPCResponse secondResp = (McpSchema.JSONRPCResponse) sent.get(sent.size() - 1);

		@SuppressWarnings("unchecked")
		Map<String, Object> firstResult = (Map<String, Object>) firstResp.result();
		@SuppressWarnings("unchecked")
		Map<String, Object> secondResult = (Map<String, Object>) secondResp.result();
		assertThat(firstResult).containsEntry("which", "first");
		assertThat(secondResult).containsEntry("which", "second");

		server.closeGracefully().block();
	}

	@Test
	void requestHandlersMapNullThrows() {
		assertThatThrownBy(() -> McpServer.async(new MockMcpServerTransportProvider(new MockMcpServerTransport()))
			.requestHandlers(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Handlers map must not be null");
	}

	// ---------------------------------------
	// Validation of single handler
	// ---------------------------------------

	@Test
	void requestHandlerRejectsBlankMethod() {
		assertThatThrownBy(() -> McpServer.async(new MockMcpServerTransportProvider(new MockMcpServerTransport()))
			.requestHandler("   ", (exchange, params) -> Mono.just(Map.of())))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestHandlerRejectsNullHandler() {
		assertThatThrownBy(() -> McpServer.async(new MockMcpServerTransportProvider(new MockMcpServerTransport()))
			.requestHandler(McpSchema.METHOD_TOOLS_LIST, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Handler must not be null");
	}

	// ---------------------------------------
	// Defaults preserved when no override
	// ---------------------------------------

	@Test
	void defaultToolsListReturnsRegisteredTools() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		Tool tool = Tool.builder("default-tool", EMPTY_JSON_SCHEMA).title("default").build();
		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange,
						request) -> Mono.just(McpSchema.CallToolResult.builder()
							.content(List.of(new TextContent("ok")))
							.isError(false)
							.build()))
				.build())
			.build();

		transportProvider.simulateIncomingMessage(initRequest());
		transportProvider.simulateIncomingMessage(initializedNotification());
		transportProvider.simulateIncomingMessage(toolsListRequest());

		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNull();
		ListToolsResult result = McpJsonDefaults.getMapper().convertValue(response.result(), ListToolsResult.class);
		assertThat(result.tools()).extracting(Tool::name).containsExactly("default-tool");

		server.closeGracefully().block();
	}

	// ---------------------------------------
	// Sync specification parity
	// ---------------------------------------

	@Test
	void syncSpecificationSupportsRequestHandler() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		Tool tool = Tool.builder("sync-tool", EMPTY_JSON_SCHEMA).title("sync").build();
		Tool customTool = Tool.builder("custom-tool", EMPTY_JSON_SCHEMA).title("custom").build();

		McpSyncServer server = McpServer.sync(transportProvider)
			.serverInfo(SERVER_INFO)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.toolCall(tool,
					(exchange, request) -> McpSchema.CallToolResult.builder()
						.content(List.of(new TextContent("ok")))
						.isError(false)
						.build())
			.requestHandler(McpSchema.METHOD_TOOLS_LIST,
					(exchange, params) -> Mono.just(ListToolsResult.builder(List.of(customTool)).build()))
			.build();

		try {
			transportProvider.simulateIncomingMessage(initRequest());
			transportProvider.simulateIncomingMessage(initializedNotification());
			transportProvider.simulateIncomingMessage(toolsListRequest());

			McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
			assertThat(response.error()).isNull();
			ListToolsResult result = McpJsonDefaults.getMapper().convertValue(response.result(), ListToolsResult.class);
			assertThat(result.tools()).extracting(Tool::name).containsExactly("custom-tool");
		}
		finally {
			server.close();
		}
	}

	// ---------------------------------------
	// Server close behaviour
	// ---------------------------------------

	@Test
	void serverCanBeClosedAfterCustomHandlerRequest() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		MockMcpServerTransportProvider transportProvider = new MockMcpServerTransportProvider(transport);

		McpAsyncServer server = McpServer.async(transportProvider)
			.serverInfo(SERVER_INFO)
			.requestHandler(CUSTOM_METHOD, (exchange, params) -> Mono.just(Map.of("ok", true)))
			.build();

		StepVerifier.create(server.closeGracefully()).verifyComplete();
	}

	// ---------------------------------------
	// Stateless server
	// ---------------------------------------

	@Test
	void statelessServerOverridesToolsList() {
		RecordingStatelessTransport transport = new RecordingStatelessTransport();
		Tool customTool = Tool.builder("stateless-tool", EMPTY_JSON_SCHEMA).title("stateless").build();

		McpStatelessAsyncServer server = McpServer.async(transport)
			.serverInfo(SERVER_INFO)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.requestHandler(McpSchema.METHOD_TOOLS_LIST,
					(ctx, params) -> Mono.just(ListToolsResult.builder(List.of(customTool)).build()))
			.build();

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST,
				UUID.randomUUID().toString(), Map.of());
		McpSchema.JSONRPCResponse response = transport.handler()
			.handleRequest(McpTransportContext.EMPTY, request)
			.block();

		assertThat(response).isNotNull();
		assertThat(response.error()).isNull();
		ListToolsResult result = McpJsonDefaults.getMapper().convertValue(response.result(), ListToolsResult.class);
		assertThat(result.tools()).extracting(Tool::name).containsExactly("stateless-tool");

		server.closeGracefully().block();
	}

	@Test
	void statelessServerSupportsNonStandardMethod() {
		RecordingStatelessTransport transport = new RecordingStatelessTransport();

		McpStatelessAsyncServer server = McpServer.async(transport)
			.serverInfo(SERVER_INFO)
			.requestHandler(CUSTOM_METHOD, (ctx, params) -> Mono.just(Map.of("echo", params)))
			.build();

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(CUSTOM_METHOD, UUID.randomUUID().toString(),
				Map.of("payload", "hi"));
		McpSchema.JSONRPCResponse response = transport.handler()
			.handleRequest(McpTransportContext.EMPTY, request)
			.block();

		assertThat(response).isNotNull();
		assertThat(response.error()).isNull();
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) response.result();
		assertThat(result).containsKey("echo");

		server.closeGracefully().block();
	}

	@Test
	void statelessSyncSpecificationSupportsRequestHandler() {
		RecordingStatelessTransport transport = new RecordingStatelessTransport();
		Tool customTool = Tool.builder("stateless-sync-tool", EMPTY_JSON_SCHEMA).title("stateless-sync").build();

		McpStatelessSyncServer server = McpServer.sync(transport)
			.serverInfo(SERVER_INFO)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.requestHandler(McpSchema.METHOD_TOOLS_LIST,
					(ctx, params) -> Mono.just(ListToolsResult.builder(List.of(customTool)).build()))
			.build();

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST,
				UUID.randomUUID().toString(), Map.of());
		McpSchema.JSONRPCResponse response = transport.handler()
			.handleRequest(McpTransportContext.EMPTY, request)
			.block();

		assertThat(response).isNotNull();
		assertThat(response.error()).isNull();
		ListToolsResult result = McpJsonDefaults.getMapper().convertValue(response.result(), ListToolsResult.class);
		assertThat(result.tools()).extracting(Tool::name).containsExactly("stateless-sync-tool");

		server.close();
	}

	/**
	 * Minimal in-memory {@link McpStatelessServerTransport} that records the handler
	 * installed by the server, allowing direct invocation of
	 * {@link McpStatelessServerHandler#handleRequest} in unit tests without a real HTTP
	 * transport.
	 */
	private static final class RecordingStatelessTransport implements McpStatelessServerTransport {

		private McpStatelessServerHandler handler;

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			this.handler = mcpHandler;
		}

		McpStatelessServerHandler handler() {
			return this.handler;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
