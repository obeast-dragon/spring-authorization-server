/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.web;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.PublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.web.authentication.ClientSecretBasicAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.ClientSecretPostAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.DelegatingAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.JwtClientAssertionAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.PublicClientAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A {@code Filter} that processes an authentication request for an OAuth 2.0 Client.
 * 实现客户端认证的拦截器
 *
 * @author Joe Grandja
 * @author Patryk Kostrzewa
 * @since 0.0.1
 * @see AuthenticationManager
 * @see JwtClientAssertionAuthenticationConverter
 * @see JwtClientAssertionAuthenticationProvider
 * @see ClientSecretBasicAuthenticationConverter
 * @see ClientSecretPostAuthenticationConverter
 * @see ClientSecretAuthenticationProvider
 * @see PublicClientAuthenticationConverter
 * @see PublicClientAuthenticationProvider
 * @see <a target="_blank" href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">Section 2.3 Client Authentication</a>
 * @see <a target="_blank" href="https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1">Section 3.2.1 Token Endpoint Client Authentication</a>
 * 每种客户端认证方式实际就是一个 AuthenticationConverter 搭配一个 AuthenticationProvider 组合而成，
 * 一个负责解析请求参数，一个负责处理请求参数，执行认证逻辑
 */
public final class OAuth2ClientAuthenticationFilter extends OncePerRequestFilter {
	/*
	authenticationManager 的类型实际上是 ProviderManager，它持有一个 AuthenticationProvider 列表（不同的认证方式，其认证逻辑不同，所以会有不同的AuthenticationProvider实现类）。
	ProviderManager 在执行认证时会遍历 AuthenticationProvider 列表，当某个 AuthenticationProvider 认证成功时，立马返回
	 */
	private final AuthenticationManager authenticationManager;
	private final RequestMatcher requestMatcher;
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter = new OAuth2ErrorHttpMessageConverter();
	private final AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource =
			new WebAuthenticationDetailsSource();
	private AuthenticationConverter authenticationConverter;
	private AuthenticationSuccessHandler authenticationSuccessHandler = this::onAuthenticationSuccess;
	private AuthenticationFailureHandler authenticationFailureHandler = this::onAuthenticationFailure;

	/**
	 * Constructs an {@code OAuth2ClientAuthenticationFilter} using the provided parameters.
	 *
	 * @param authenticationManager the {@link AuthenticationManager} used for authenticating the client
	 * @param requestMatcher the {@link RequestMatcher} used for matching against the {@code HttpServletRequest}
	 */
	public OAuth2ClientAuthenticationFilter(AuthenticationManager authenticationManager,
			RequestMatcher requestMatcher) {
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
		Assert.notNull(requestMatcher, "requestMatcher cannot be null");
		this.authenticationManager = authenticationManager;
		this.requestMatcher = requestMatcher;
//		不同的解析方式实际上就代表不同的认证方式（传参不同）。
		this.authenticationConverter = new DelegatingAuthenticationConverter(
//				authenticationConverter 的类型实际上是 DelegatingAuthenticationConverter，
//				它持有一个 AuthenticationConverter 列表（不同的认证请求，其参数不同，所以会有不同的AuthenticationConverter实现类
				Arrays.asList(
						new JwtClientAssertionAuthenticationConverter(),
						new ClientSecretBasicAuthenticationConverter(),
						new ClientSecretPostAuthenticationConverter(),
						new PublicClientAuthenticationConverter())
//				DelegatingAuthenticationConverter 在解析请求时会遍历 AuthenticationConverter 列表，
//				当某个 AuthenticationConverter 解析成功时，立即返回，这也能确定此请求是什么认证方式，
//				后续再执行对应的认证逻辑。
		);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		if (!this.requestMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
//			authenticationConverter 从 request 中解析出客户端认证信息，构建成 Authentication
			Authentication authenticationRequest = this.authenticationConverter.convert(request);
			if (authenticationRequest instanceof AbstractAuthenticationToken) {
				((AbstractAuthenticationToken) authenticationRequest).setDetails(
						this.authenticationDetailsSource.buildDetails(request));
			}
			if (authenticationRequest != null) {
//				再通过 authenticationManager 对 Authentication 进行认证
				Authentication authenticationResult = this.authenticationManager.authenticate(authenticationRequest);
				this.authenticationSuccessHandler.onAuthenticationSuccess(request, response, authenticationResult);
			}
			filterChain.doFilter(request, response);

		} catch (OAuth2AuthenticationException ex) {
			this.authenticationFailureHandler.onAuthenticationFailure(request, response, ex);
		}
	}

	/**
	 * Sets the {@link AuthenticationConverter} used when attempting to extract client credentials from {@link HttpServletRequest}
	 * to an instance of {@link OAuth2ClientAuthenticationToken} used for authenticating the client.
	 *
	 * @param authenticationConverter the {@link AuthenticationConverter} used when attempting to extract client credentials from {@link HttpServletRequest}
	 */
	public void setAuthenticationConverter(AuthenticationConverter authenticationConverter) {
		Assert.notNull(authenticationConverter, "authenticationConverter cannot be null");
		this.authenticationConverter = authenticationConverter;
	}

	/**
	 * Sets the {@link AuthenticationSuccessHandler} used for handling a successful client authentication
	 * and associating the {@link OAuth2ClientAuthenticationToken} to the {@link SecurityContext}.
	 *
	 * @param authenticationSuccessHandler the {@link AuthenticationSuccessHandler} used for handling a successful client authentication
	 */
	public void setAuthenticationSuccessHandler(AuthenticationSuccessHandler authenticationSuccessHandler) {
		Assert.notNull(authenticationSuccessHandler, "authenticationSuccessHandler cannot be null");
		this.authenticationSuccessHandler = authenticationSuccessHandler;
	}

	/**
	 * Sets the {@link AuthenticationFailureHandler} used for handling a failed client authentication
	 * and returning the {@link OAuth2Error Error Response}.
	 *
	 * @param authenticationFailureHandler the {@link AuthenticationFailureHandler} used for handling a failed client authentication
	 */
	public void setAuthenticationFailureHandler(AuthenticationFailureHandler authenticationFailureHandler) {
		Assert.notNull(authenticationFailureHandler, "authenticationFailureHandler cannot be null");
		this.authenticationFailureHandler = authenticationFailureHandler;
	}

	private void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(authentication);
		SecurityContextHolder.setContext(securityContext);
	}

	private void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {

		SecurityContextHolder.clearContext();

		// TODO
		// The authorization server MAY return an HTTP 401 (Unauthorized) status code
		// to indicate which HTTP authentication schemes are supported.
		// If the client attempted to authenticate via the "Authorization" request header field,
		// the authorization server MUST respond with an HTTP 401 (Unauthorized) status code and
		// include the "WWW-Authenticate" response header field
		// matching the authentication scheme used by the client.

		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		if (OAuth2ErrorCodes.INVALID_CLIENT.equals(error.getErrorCode())) {
			httpResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
		} else {
			httpResponse.setStatusCode(HttpStatus.BAD_REQUEST);
		}
		// We don't want to reveal too much information to the caller so just return the error code
		OAuth2Error errorResponse = new OAuth2Error(error.getErrorCode());
		this.errorHttpResponseConverter.write(errorResponse, null, httpResponse);
	}

}
