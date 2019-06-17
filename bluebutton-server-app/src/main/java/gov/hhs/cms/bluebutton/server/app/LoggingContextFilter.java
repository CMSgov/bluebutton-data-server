package gov.hhs.cms.bluebutton.server.app;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.x500.X500Principal;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.ClassicConstants;
import ch.qos.logback.classic.helpers.MDCInsertingServletFilter;

/**
 * Adds some common {@link HttpServletRequest} properties to the logging
 * {@link MDC} and also ensures that the {@link MDC} is completely cleared after
 * every request. This {@link Filter} must be declared before all others in the
 * {@code web.xml} or whatever.
 *
 * (Note: We don't use or extend {@link MDCInsertingServletFilter}, as it
 * includes more properties than we really need. It also doesn't fully clear the
 * {@link MDC} after each request, only partially.)
 */
public final class LoggingContextFilter implements Filter {
	private static final Logger LOGGER = LoggerFactory.getLogger(LoggingContextFilter.class);

	/**
	 * @see javax.servlet.Filter#doFilter(javax.servlet.ServletRequest,
	 *      javax.servlet.ServletResponse, javax.servlet.FilterChain)
	 */
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		recordStandardRequestEntries(request);
		request = wrap(request);
		try {
			chain.doFilter(request, response);
		} finally {
			clearMdc();
		}
	}

	/**
	 * @param request
	 * @return
	 */
	private static ServletRequest wrap(ServletRequest request) {
		if (request instanceof HttpServletRequest) {
			HttpServletRequest httpServletRequest = (HttpServletRequest) request;

			request = new HttpServletRequestWrapper(httpServletRequest) {

			};
			return request;
		} else
			return request;
	}

	/**
	 * @param request
	 *            the {@link ServletRequest} to record the standard {@link MDC}
	 *            entries for
	 */
	private void recordStandardRequestEntries(ServletRequest request) {
		if (request instanceof HttpServletRequest) {
			HttpServletRequest httpServletRequest = (HttpServletRequest) request;

			MDC.put(ClassicConstants.REQUEST_METHOD, httpServletRequest.getMethod());
			MDC.put(ClassicConstants.REQUEST_REQUEST_URI, httpServletRequest.getRequestURI());
			StringBuffer requestURL = httpServletRequest.getRequestURL();
			if (requestURL != null)
				MDC.put(ClassicConstants.REQUEST_REQUEST_URL, requestURL.toString());
			else
				MDC.put(ClassicConstants.REQUEST_REQUEST_URL, null);
			MDC.put(ClassicConstants.REQUEST_QUERY_STRING, httpServletRequest.getQueryString());
			
			/*
			 * In addition to sticking this in the MDC, we also push it back to the request
			 * attributes, where the access log can pick it up.
			 */
			String clientSslPrincipalDistinguishedName = getClientSslPrincipalDistinguishedName(httpServletRequest);
			MDC.put("req.clientSSL.DN", clientSslPrincipalDistinguishedName);
			httpServletRequest.setAttribute("req-clientSSL-DN", clientSslPrincipalDistinguishedName);
			httpServletRequest.setAttribute("foo", "bar");
			httpServletRequest.getSession(true).setAttribute("foo", "bar");
		}
	}

	/**
	 * @param request
	 *            the {@link HttpServletRequest} to get the client principal DN (if
	 *            any) for
	 * @return the {@link X500Principal#getName()} for the client certificate, or
	 *         <code>null</code> if that's not availableg
	 */
	private static String getClientSslPrincipalDistinguishedName(HttpServletRequest request) {
		X509Certificate clientCert = getClientCertificate(request);
		if (clientCert == null || clientCert.getSubjectX500Principal() == null) {
			LOGGER.debug("No client SSL principal available: {}", clientCert);
			return null;
		}

		return clientCert.getSubjectX500Principal().getName();
	}

	/**
	 * @param request
	 *            the {@link HttpServletRequest} to get the client SSL certificate
	 *            for
	 * @return the {@link X509Certificate} for the {@link HttpServletRequest}'s
	 *         client SSL certificate, or <code>null</code> if that's not available
	 */
	private static X509Certificate getClientCertificate(HttpServletRequest request) {
		X509Certificate[] certs = (X509Certificate[]) request.getAttribute("javax.servlet.request.X509Certificate");
		if (certs == null || certs.length <= 0) {
			LOGGER.debug("No client certificate found for request.");
			return null;
		}
		return certs[certs.length - 1];
	}

	/**
	 * Completely clears the MDC after each request.
	 */
	private void clearMdc() {
		MDC.clear();
	}

	/**
	 * @see javax.servlet.Filter#init(javax.servlet.FilterConfig)
	 */
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// Nothing to do here.
	}

	/**
	 * @see javax.servlet.Filter#destroy()
	 */
	@Override
	public void destroy() {
		// Nothing to do here.
	}

	private static final class WrappedHttpServletRequest extends HttpServletRequestWrapper {
		private static final String CLIENT_SSL_HEADER = "BlueButton-ClientSSLName";

		private final String clientSslPrincipalDistinguishedName;

		/**
		 * @param request
		 */
		public WrappedHttpServletRequest(HttpServletRequest request) {
			super(request);
			this.clientSslPrincipalDistinguishedName = getClientSslPrincipalDistinguishedName(request);
		}

		/**
		 * @return a {@link Map} of the headers that the wrapped request should use
		 */
		private Map<String, List<String>> getHeaders() {
			Map<String, List<String>> headers = new HashMap<>();
			
			// Build a Map of just the super headers.
			Enumeration<String> headerNames = super.getHeaderNames();
			while(headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				List<String> headerValues = Collections.list(super.getHeaders(headerName));
				
				headers.put(headerName, headerValues);
			}
			
			/*
			 * Add the client SSL principal to the headers. (This one line of code is the
			 * whole point of this little class).
			 */
			headers.put(CLIENT_SSL_HEADER, Arrays.asList(clientSslPrincipalDistinguishedName));

			return headers;
		}

		/**
		 * @see javax.servlet.http.HttpServletRequestWrapper#getHeaderNames()
		 */
		@Override
		public Enumeration<String> getHeaderNames() {
			return Collections.enumeration(getHeaders().keySet());
		}

		/**
		 * @see javax.servlet.http.HttpServletRequestWrapper#getHeaders(java.lang.String)
		 */
		@Override
		public Enumeration<String> getHeaders(String name) {
			// Find header in case-insensitive way.
			List<String> headerValues = null;
			Map<String, List<String>> headersMap = getHeaders();
			for (String headerName : headersMap.keySet())
				if (headerName.equalsIgnoreCase(name))
					headerValues = headersMap.get(headerName);

			if (headerValues == null)
				headerValues = Collections.emptyList();
			return Collections.enumeration(headerValues);
		}

		/**
		 * @see javax.servlet.http.HttpServletRequestWrapper#getHeader(java.lang.String)
		 */
		@Override
		public String getHeader(String name) {
			Enumeration<String> headerValues = getHeaders(name);
			if (headerValues == null || !headerValues.hasMoreElements())
				return null;

			return headerValues.nextElement();
		}

		/**
		 * @see javax.servlet.http.HttpServletRequestWrapper#getIntHeader(java.lang.String)
		 */
		@Override
		public int getIntHeader(String name) {
			/*
			 * Rather than reimplementing the parsing logic here, we can cheat a bit, since
			 * no one should ever be calling _this_ method for the header we're adding.
			 */

			if (name.equalsIgnoreCase(CLIENT_SSL_HEADER))
				throw new NumberFormatException("Unable to parse " + CLIENT_SSL_HEADER);
			return super.getIntHeader(name);
		}

		/**
		 * @see javax.servlet.http.HttpServletRequestWrapper#getDateHeader(java.lang.String)
		 */
		@Override
		public long getDateHeader(String name) {
			/*
			 * Rather than reimplementing the parsing logic here, we can cheat a bit, since
			 * no one should ever be calling _this_ method for the header we're adding.
			 */

			if (name.equalsIgnoreCase(CLIENT_SSL_HEADER))
				throw new IllegalArgumentException("Unable to parse " + CLIENT_SSL_HEADER);
			return super.getIntHeader(name);
		}
	}
}
