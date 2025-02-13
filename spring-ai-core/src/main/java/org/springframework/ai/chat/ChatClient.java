/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.chat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.Media;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import org.springframework.util.StringUtils;

// todo support plugging in a outputConverter at runtime
// todo figure out stream and list methods

/**
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Josh Long
 * @author Arjen Poutsma
 */
public interface ChatClient {

	static ChatClient create(ChatModel chatModel) {
		return builder(chatModel).build();
	}

	static ChatClientBuilder builder(ChatModel chatModel) {
		return new ChatClientBuilder(chatModel);
	}

	ChatClientRequest prompt();

	ChatClientPromptRequest prompt(Prompt prompt);

	interface PromptSpec<T> {

		T text(String text);

		T text(Resource text, Charset charset);

		T text(Resource text);

		T params(Map<String, Object> p);

		T param(String k, String v);

	}

	abstract class AbstractPromptSpec<T extends AbstractPromptSpec<T>> implements PromptSpec<T> {

		private String text = "";

		private final Map<String, Object> params = new HashMap<>();

		@Override
		public T text(String text) {
			this.text = text;
			return self();
		}

		@Override
		public T text(Resource text, Charset charset) {
			try {
				this.text(text.getContentAsString(charset));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return self();
		}

		@Override
		public T text(Resource text) {
			this.text(text, Charset.defaultCharset());
			return self();
		}

		@Override
		public T param(String k, String v) {
			this.params.put(k, v);
			return self();
		}

		@Override
		public T params(Map<String, Object> p) {
			this.params.putAll(p);
			return self();
		}

		protected abstract T self();

		protected String text() {
			return this.text;
		}

		protected Map<String, Object> params() {
			return this.params;
		}

	}

	class UserSpec extends AbstractPromptSpec<UserSpec> implements PromptSpec<UserSpec> {

		private final List<Media> media = new ArrayList<>();

		public UserSpec media(Media... media) {
			this.media.addAll(Arrays.asList(media));
			return self();
		}

		public UserSpec media(MimeType mimeType, URL url) {
			this.media.add(new Media(mimeType, url));
			return self();
		}

		public UserSpec media(MimeType mimeType, Resource resource) {
			this.media.add(new Media(mimeType, resource));
			return self();
		}

		protected List<Media> media() {
			return this.media;
		}

		@Override
		protected UserSpec self() {
			return this;
		}

	}

	class SystemSpec extends AbstractPromptSpec<SystemSpec> implements PromptSpec<SystemSpec> {

		@Override
		protected SystemSpec self() {
			return this;
		}

	}

	class ChatClientPromptRequest {

		private final ChatModel chatModel;

		private final Prompt prompt;

		public ChatClientPromptRequest(ChatModel chatModel, Prompt prompt) {
			this.chatModel = chatModel;
			this.prompt = prompt;
		}

		public ChatClientRequest.CallPromptResponseSpec call() {
			return new ChatClientRequest.CallPromptResponseSpec(this.chatModel, this.prompt);
		}

		public ChatClientRequest.StreamPromptResponseSpec stream() {
			return new ChatClientRequest.StreamPromptResponseSpec((StreamingChatModel) this.chatModel, this.prompt);
		}

	}

	class ChatClientRequest {

		private final ChatModel chatModel;

		private String userText = "";

		private String systemText = "";

		private ChatOptions chatOptions;

		private final List<Media> media = new ArrayList<>();

		private final List<String> functionNames = new ArrayList<>();

		private final List<FunctionCallback> functionCallbacks = new ArrayList<>();

		private final List<Message> messages = new ArrayList<>();

		private final Map<String, Object> userParams = new HashMap<>();

		private final Map<String, Object> systemParams = new HashMap<>();

		/* copy constructor */
		ChatClientRequest(ChatClientRequest ccr) {
			this(ccr.chatModel, ccr.userText, ccr.userParams, ccr.systemText, ccr.systemParams, ccr.functionCallbacks,
					ccr.functionNames, ccr.media, ccr.chatOptions);
		}

		public ChatClientRequest(ChatModel chatModel, String userText, Map<String, Object> userParams,
				String systemText, Map<String, Object> systemParams, List<FunctionCallback> functionCallbacks,
				List<String> functionNames, List<Media> media, ChatOptions chatOptions) {

			this.chatModel = chatModel;
			this.chatOptions = chatOptions != null ? chatOptions : chatModel.getDefaultOptions();

			this.userText = userText;
			this.userParams.putAll(userParams);
			this.systemText = systemText;
			this.systemParams.putAll(systemParams);

			this.functionNames.addAll(functionNames);
			this.functionCallbacks.addAll(functionCallbacks);
			this.media.addAll(media);
		}

		public ChatClientRequest messages(Message... messages) {
			this.messages.addAll(List.of(messages));
			return this;
		}

		public ChatClientRequest messages(List<Message> messages) {
			this.messages.addAll(messages);
			return this;
		}

		public <T extends ChatOptions> ChatClientRequest options(T options) {
			this.chatOptions = options;
			return this;
		}

		public <I, O> ChatClientRequest function(String name, String description,
				java.util.function.Function<I, O> function) {
			var fcw = FunctionCallbackWrapper.builder(function)
				.withDescription(description)
				.withName(name)
				.withResponseConverter(Object::toString)
				.build();
			this.functionCallbacks.add(fcw);
			return this;
		}

		public ChatClientRequest functions(String... functionBeanNames) {
			this.functionNames.addAll(List.of(functionBeanNames));
			return this;
		}

		public ChatClientRequest system(String text) {
			this.systemText = text;
			return this;
		}

		public ChatClientRequest system(Resource text, Charset charset) {
			try {
				this.systemText = text.getContentAsString(charset);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientRequest system(Resource text) {
			return this.system(text, Charset.defaultCharset());
		}

		public ChatClientRequest system(Consumer<SystemSpec> consumer) {
			var ss = new SystemSpec();
			consumer.accept(ss);
			this.systemText = StringUtils.hasText(ss.text()) ? ss.text() : this.systemText;
			this.systemParams.putAll(ss.params());

			return this;
		}

		public ChatClientRequest user(String text) {
			this.userText = text;
			return this;
		}

		public ChatClientRequest user(Resource text, Charset charset) {
			try {
				this.userText = text.getContentAsString(charset);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientRequest user(Resource text) {
			return this.user(text, Charset.defaultCharset());
		}

		public ChatClientRequest user(Consumer<UserSpec> consumer) {
			var us = new UserSpec();
			consumer.accept(us);
			this.userText = StringUtils.hasText(us.text()) ? us.text() : this.userText;
			this.userParams.putAll(us.params());
			this.media.addAll(us.media());
			return this;
		}

		public static class StreamPromptResponseSpec {

			private final Prompt prompt;

			private final StreamingChatModel chatModel;

			public StreamPromptResponseSpec(StreamingChatModel streamingChatModel, Prompt prompt) {
				this.chatModel = streamingChatModel;
				this.prompt = prompt;
			}

			public Flux<ChatResponse> chatResponse() {
				return doGetFluxChatResponse(this.prompt);
			}

			private Flux<ChatResponse> doGetFluxChatResponse(Prompt prompt) {
				return this.chatModel.stream(prompt);
			}

			public Flux<String> content() {
				return doGetFluxChatResponse(this.prompt).map(r -> {
					if (r.getResult() == null || r.getResult().getOutput() == null
							|| r.getResult().getOutput().getContent() == null) {
						return "";
					}
					return r.getResult().getOutput().getContent();
				}).filter(v -> StringUtils.hasText(v));
			}

		}

		public static class CallPromptResponseSpec {

			private final ChatModel chatModel;

			private final Prompt prompt;

			public CallPromptResponseSpec(ChatModel chatModel, Prompt prompt) {
				this.chatModel = chatModel;
				this.prompt = prompt;
			}

			public String content() {
				return doGetChatResponse(this.prompt).getResult().getOutput().getContent();
			}

			public List<String> contents() {
				return doGetChatResponse(this.prompt).getResults()
					.stream()
					.map(r -> r.getOutput().getContent())
					.toList();
			}

			public ChatResponse chatResponse() {
				return doGetChatResponse(this.prompt);
			}

			private ChatResponse doGetChatResponse(Prompt prompt) {
				return chatModel.call(prompt);
			}

		}

		public static class CallResponseSpec {

			private final ChatClientRequest request;

			private final ChatModel chatModel;

			public CallResponseSpec(ChatModel chatModel, ChatClientRequest request) {
				this.chatModel = chatModel;
				this.request = request;
			}

			public <T> T entity(ParameterizedTypeReference<T> type) {
				return doSingleWithBeanOutputConverter(new BeanOutputConverter<T>(type));
			}

			private <T> T doSingleWithBeanOutputConverter(BeanOutputConverter<T> boc) {
				var processedUserText = this.request.userText + System.lineSeparator() + System.lineSeparator()
						+ "{format}";
				var chatResponse = doGetChatResponse(processedUserText, boc.getFormat());
				var stringResponse = chatResponse.getResult().getOutput().getContent();
				return boc.convert(stringResponse);
			}

			public <T> T entity(Class<T> type) {
				Assert.notNull(type, "the class must be non-null");
				var boc = new BeanOutputConverter<T>(type);
				return doSingleWithBeanOutputConverter(boc);
			}

			private ChatResponse doGetChatResponse(String processedUserText) {
				return this.doGetChatResponse(processedUserText, "");
			}

			private ChatResponse doGetChatResponse(String processedUserText, String formatParam) {
				Map<String, Object> userParams = new HashMap<>(this.request.userParams);
				if (StringUtils.hasText(formatParam)) {
					userParams.put("format", formatParam);
				}

				var messages = new ArrayList<Message>();
				var textsAreValid = (StringUtils.hasText(processedUserText)
						|| StringUtils.hasText(this.request.systemText));
				var messagesAreValid = !this.request.messages.isEmpty();
				Assert.state(!(messagesAreValid && textsAreValid), "you must specify either " + Message.class.getName()
						+ " instances or user/system texts, but not both");
				if (textsAreValid) {
					UserMessage userMessage = null;
					if (!CollectionUtils.isEmpty(userParams)) {
						userMessage = new UserMessage(new PromptTemplate(processedUserText, userParams).render(),
								this.request.media);
					}
					else {
						userMessage = new UserMessage(processedUserText, this.request.media);
					}
					if (StringUtils.hasText(this.request.systemText) || !this.request.systemParams.isEmpty()) {
						var systemMessage = new SystemMessage(
								new PromptTemplate(this.request.systemText, this.request.systemParams).render());
						messages.add(systemMessage);
					}
					messages.add(userMessage);
				}
				else {
					messages.addAll(this.request.messages);
				}
				if (this.request.chatOptions instanceof FunctionCallingOptions functionCallingOptions) {
					// if (this.request.chatOptions instanceof
					// FunctionCallingOptionsBuilder.PortableFunctionCallingOptions
					// functionCallingOptions) {
					if (!this.request.functionNames.isEmpty()) {
						functionCallingOptions.setFunctions(new HashSet<>(this.request.functionNames));
					}
					if (!this.request.functionCallbacks.isEmpty()) {
						functionCallingOptions.setFunctionCallbacks(this.request.functionCallbacks);
					}
				}
				var prompt = new Prompt(messages, this.request.chatOptions);
				return this.chatModel.call(prompt);
			}

			public ChatResponse chatResponse() {
				return doGetChatResponse(this.request.userText);
			}

			public String content() {
				return doGetChatResponse(this.request.userText).getResult().getOutput().getContent();
			}

		}

		public static class StreamResponseSpec {

			private final ChatClientRequest request;

			private final StreamingChatModel chatModel;

			public StreamResponseSpec(StreamingChatModel streamingChatModel, ChatClientRequest request) {
				this.chatModel = streamingChatModel;
				this.request = request;
			}

			private Flux<ChatResponse> doGetFluxChatResponse(String processedUserText) {
				Map<String, Object> userParams = new HashMap<>(this.request.userParams);

				var messages = new ArrayList<Message>();
				var textsAreValid = (StringUtils.hasText(processedUserText)
						|| StringUtils.hasText(this.request.systemText));
				var messagesAreValid = !this.request.messages.isEmpty();
				Assert.state(!(messagesAreValid && textsAreValid), "you must specify either " + Message.class.getName()
						+ " instances or user/system texts, but not both");
				if (textsAreValid) {
					UserMessage userMessage = null;
					if (!CollectionUtils.isEmpty(userParams)) {
						userMessage = new UserMessage(new PromptTemplate(processedUserText, userParams).render(),
								this.request.media);
					}
					else {
						userMessage = new UserMessage(processedUserText, this.request.media);
					}
					if (StringUtils.hasText(this.request.systemText) || !this.request.systemParams.isEmpty()) {
						var systemMessage = new SystemMessage(
								new PromptTemplate(this.request.systemText, this.request.systemParams).render());
						messages.add(systemMessage);
					}
					messages.add(userMessage);
				}
				else {
					messages.addAll(this.request.messages);
				}
				if (this.request.chatOptions instanceof FunctionCallingOptions functionCallingOptions) {
					// if (this.request.chatOptions instanceof
					// FunctionCallingOptionsBuilder.PortableFunctionCallingOptions
					// functionCallingOptions) {
					if (!this.request.functionNames.isEmpty()) {
						functionCallingOptions.setFunctions(new HashSet<>(this.request.functionNames));
					}
					if (!this.request.functionCallbacks.isEmpty()) {
						functionCallingOptions.setFunctionCallbacks(this.request.functionCallbacks);
					}
				}
				var prompt = new Prompt(messages, this.request.chatOptions);
				return this.chatModel.stream(prompt);
			}

			public Flux<ChatResponse> chatResponse() {
				return doGetFluxChatResponse(this.request.userText);
			}

			public Flux<String> content() {
				return doGetFluxChatResponse(this.request.userText).map(r -> {
					if (r.getResult() == null || r.getResult().getOutput() == null
							|| r.getResult().getOutput().getContent() == null) {
						return "";
					}
					return r.getResult().getOutput().getContent();
				}).filter(v -> StringUtils.hasText(v));
			}

		}

		public CallResponseSpec call() {
			return new CallResponseSpec(this.chatModel, this);
		}

		public StreamResponseSpec stream() {
			return new StreamResponseSpec((StreamingChatModel) this.chatModel, this);
		}

	}

	class ChatClientBuilder {

		private final ChatClientRequest defaultRequest;

		private final ChatModel chatModel;

		ChatClientBuilder(ChatModel chatModel) {
			Assert.notNull(chatModel, "the " + ChatModel.class.getName() + " must be non-null");
			this.chatModel = chatModel;
			this.defaultRequest = new ChatClientRequest(chatModel, "", Map.of(), "", Map.of(), List.of(), List.of(),
					List.of(), null);
		}

		public ChatClient build() {
			return new DefaultChatClient(this.chatModel, this.defaultRequest);
		}

		public ChatClientBuilder defaultOptions(ChatOptions chatOptions) {
			this.defaultRequest.options(chatOptions);
			return this;
		}

		public ChatClientBuilder defaultUser(String text) {
			this.defaultRequest.user(text);
			return this;
		}

		public ChatClientBuilder defaultUser(Resource text, Charset charset) {
			try {
				this.defaultRequest.user(text.getContentAsString(charset));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientBuilder defaultUser(Resource text) {
			return this.defaultUser(text, Charset.defaultCharset());
		}

		public ChatClientBuilder defaultUser(Consumer<UserSpec> userSpecConsumer) {
			this.defaultRequest.user(userSpecConsumer);
			return this;
		}

		public ChatClientBuilder defaultSystem(String text) {
			this.defaultRequest.system(text);
			return this;
		}

		public ChatClientBuilder defaultSystem(Resource text, Charset charset) {
			try {
				this.defaultRequest.system(text.getContentAsString(charset));
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
			return this;
		}

		public ChatClientBuilder defaultSystem(Resource text) {
			return this.defaultSystem(text, Charset.defaultCharset());
		}

		public ChatClientBuilder defaultSystem(Consumer<SystemSpec> systemSpecConsumer) {
			this.defaultRequest.system(systemSpecConsumer);
			return this;
		}

		public <I, O> ChatClientBuilder defaultFunction(String name, String description,
				java.util.function.Function<I, O> function) {
			this.defaultRequest.function(name, description, function);
			return this;
		}

		public ChatClientBuilder defaultFunctions(String... functionNames) {
			this.defaultRequest.functions(functionNames);
			return this;
		}

	}

	/**
	 * Calls the underlying chat model with a prompt message and returns the output
	 * content of the first generation.
	 * @param message The message to be used as a prompt for the chat model.
	 * @return The output content of the first generation.
	 * @deprecated This method is deprecated as of version 1.0.0 M1 and will be removed in
	 * a future release. Use the method
	 * builder(chatModel).build().prompt().user(message).call().content() instead
	 *
	 */
	@Deprecated(since = "1.0.0 M1", forRemoval = true)
	default String call(String message) {
		var prompt = new Prompt(new UserMessage(message));
		var generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getContent() : "";
	}

	/**
	 * Calls the underlying chat model with a prompt message and returns the output
	 * content of the first generation.
	 * @param messages The messages to be used as a prompt for the chat model.
	 * @return The output content of the first generation.
	 * @deprecated This method is deprecated as of version 1.0.0 M1 and will be removed in
	 * a future release. Use the method
	 * builder(chatModel).build().prompt().messages(messages).call().content() instead.
	 */
	@Deprecated(since = "1.0.0 M1", forRemoval = true)
	default String call(Message... messages) {
		var prompt = new Prompt(Arrays.asList(messages));
		var generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getContent() : "";
	}

	/**
	 * Calls the underlying chat model with a prompt and returns the corresponding chat
	 * response.
	 * @param prompt The prompt to be used for the chat model.
	 * @return The chat response containing the generated messages.
	 * @deprecated This method is deprecated as of version 1.0.0 M1 and will be removed in
	 * a future release. Use the method builder(chatModel).build().prompt(prompt).call()
	 * instead.
	 */
	@Deprecated(since = "1.0.0 M1", forRemoval = true)
	ChatResponse call(Prompt prompt);

}
