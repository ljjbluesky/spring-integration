/*
 * Copyright 2002-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.message;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.springframework.core.GenericTypeResolver;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.integration.annotation.Header;
import org.springframework.integration.annotation.Headers;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Prepares arguments for handler methods. The method parameters are matched
 * against the Message payload as well as its headers. If a method parameter
 * is annotated with {@link Header @Header}, the annotation's value will be
 * used as a header name. If such an annotation contains no value, then the
 * parameter name will be used as long as the information is available in the
 * class file (requires compilation with debug settings for parameter names).
 * If the {@link Header @Header} annotation is not present, then the parameter
 * will typically match the Message payload. However, if a Map or Properties
 * object is expected, and the paylaod is not itself assignable to that type,
 * then the MessageHeaders' values will be passed in the case of a Map-typed
 * parameter, or the MessageHeaders' String-based values will be passed in the
 * case of a Properties-typed parameter.
 * 
 * @author Mark Fisher
 */
public class MethodParameterMessageMapper implements MessageMapper<Object[]> {

	private final Method method;

	private volatile MethodParameterMetadata[] parameterMetadata;

	private final ParameterNameDiscoverer parameterNameDiscoverer = new LocalVariableTableParameterNameDiscoverer();


	public MethodParameterMessageMapper(Method method) {
		Assert.notNull(method, "method must not be null");
		this.method = method;
		this.initializeParameterMetadata();
	}


	public Message<?> toMessage(Object[] parameters) {
		Assert.isTrue(!ObjectUtils.isEmpty(parameters), "parameter array is required");
		Assert.isTrue(parameters.length == this.parameterMetadata.length,
				"wrong number of parameters: expected " + this.parameterMetadata.length
				+ ", received " + parameters.length);
		Message<?> message = null;
		Object payload = null;
		Map<String, Object> headers = new HashMap<String, Object>();
		for (int i = 0; i < parameters.length; i++) {
			Object value = parameters[i];
			MethodParameterMetadata metadata = this.parameterMetadata[i];
			Class<?> expectedType = metadata.type;
			if (expectedType.equals(Header.class)) {
				if (value != null) {
					headers.put(metadata.key, value);
				}
				else {
					Assert.isTrue(!metadata.required, "header '" + metadata.key + "' is required");
				}
			}
			else if (expectedType.equals(Headers.class)) {
				if (value != null) {
					this.addHeadersAnnotatedParameterToMap(value, headers);
				}
			}
			else if (expectedType.equals(Message.class)) {
				message = (Message<?>) value;
			}
			else {
				Assert.notNull(value, "payload object must not be null");
				payload = value;
			}
		}
		if (message != null) {
			if (headers.isEmpty()) {
				return message;
			}
			return MessageBuilder.fromMessage(message).copyHeadersIfAbsent(headers).build();
		}
		Assert.notNull(payload, "no parameter available for Message or payload");
		return MessageBuilder.withPayload(payload).copyHeaders(headers).build();
	}

	public Object[] fromMessage(Message<?> message) {
		if (message == null) {
			return null;
		}
		Assert.notNull(message.getPayload(), "Message payload must not be null.");
		Object[] args = new Object[this.parameterMetadata.length];
		for (int i = 0; i < this.parameterMetadata.length; i++) {
			MethodParameterMetadata metadata = this.parameterMetadata[i];
			Class<?> expectedType = metadata.type;
			if (expectedType.equals(Header.class)) {
				Object value = message.getHeaders().get(metadata.key);
				if (value == null && metadata.required) {
					throw new MessageHandlingException(message,
							"required header '" + metadata.key + "' not available");
				}
				args[i] = value;
			}
			else if (expectedType.isAssignableFrom(message.getClass())) {
				args[i] = message;
			}
			else if (expectedType.isAssignableFrom(message.getPayload().getClass())) {
				args[i] = message.getPayload();
			}
			else if (expectedType.equals(Map.class)) {
				args[i] = message.getHeaders();
			}
			else if (expectedType.equals(Properties.class)) {
				args[i] = this.getStringTypedHeaders(message);
			}
			else {
				args[i] = message.getPayload();
			}
		}
		return args;
	}

	private void initializeParameterMetadata() {
		boolean foundMessageOrPayload = false;
		Class<?>[] paramTypes = this.method.getParameterTypes();			
		this.parameterMetadata = new MethodParameterMetadata[paramTypes.length];
		for (int i = 0; i < parameterMetadata.length; i++) {
			MethodParameter methodParam = new MethodParameter(this.method, i);
			methodParam.initParameterNameDiscovery(this.parameterNameDiscoverer);
			GenericTypeResolver.resolveParameterType(methodParam, this.method.getDeclaringClass());
			Object[] paramAnnotations = methodParam.getParameterAnnotations();
			for (int j = 0; j < paramAnnotations.length; j++) {
				if (Header.class.isInstance(paramAnnotations[j])) {
					Header headerAnnotation = (Header) paramAnnotations[j];
					String headerName = this.resolveHeaderName(headerAnnotation, methodParam);
					parameterMetadata[i] = new MethodParameterMetadata(Header.class, headerName, headerAnnotation.required());
				}
				else if (Headers.class.isInstance(paramAnnotations[j])) {
					Assert.isAssignable(Map.class, methodParam.getParameterType(),
							"parameter with the @Headers annotation must be assignable to java.util.Map");
					parameterMetadata[i] = new MethodParameterMetadata(Headers.class, null, false);
				}
			}
			if (parameterMetadata[i] == null) {
				// this is either a Message or the Object to be used as a Message payload
				Assert.isTrue(!foundMessageOrPayload, "only one Message or payload parameter is allowed");
				foundMessageOrPayload = true;
				parameterMetadata[i] = new MethodParameterMetadata(methodParam.getParameterType(), null, false);
			}
		}
	}

	private Properties getStringTypedHeaders(Message<?> message) {
		Properties properties = new Properties();
		MessageHeaders headers = message.getHeaders();
		for (String key : headers.keySet()) {
			Object value = headers.get(key);
			if (value instanceof String) {
				properties.setProperty(key, (String) value);
			}
		}
		return properties;
	}

	private String resolveHeaderName(Header headerAnnotation, MethodParameter methodParam) {
		String paramName = headerAnnotation.value();
		if (!StringUtils.hasText(paramName)) {
			paramName = methodParam.getParameterName();
			Assert.state(paramName != null, "No parameter name specified and not available in class file.");
		}
		return paramName;
	}

	@SuppressWarnings("unchecked")
	private void addHeadersAnnotatedParameterToMap(Object value, Map<String, Object> headers) {
		Map map = (Map) value;
		for (Iterator iter = map.entrySet().iterator(); iter.hasNext();) {
			Map.Entry entry = (Map.Entry) iter.next();
			Assert.isTrue(entry.getKey() instanceof String,
					"Map annotated with @Headers must have String-typed keys");
			headers.put((String) entry.getKey(), entry.getValue());
		}
	}


	private static class MethodParameterMetadata {

		private final Class<?> type;

		private final String key;

		private final boolean required;


		MethodParameterMetadata(Class<?> type, String key, boolean required) {
			this.type = type;
			this.key = key;
			this.required = required;
		}

	}

}
