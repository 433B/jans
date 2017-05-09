package org.gluu.oxserver.filters;

import java.io.IOException;
import java.lang.reflect.Constructor;

import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CORS wrapper to support both Tomcat and Jetty
 * 
 * @author Yuriy Movchan
 * @version September 07, 2016
 */
public class AbstractCorsFilter implements Filter {

	@Inject
	private Logger log;

	private static final String CORS_FILTERS[] = { "org.apache.catalina.filters.CorsFilter",
			"org.eclipse.jetty.servlets.CrossOriginFilter" };
	
	protected Filter filter;

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		this.filter = getServerCorsFilter();
		
		if (this.filter != null) {
			filter.init(filterConfig);
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		if (this.filter != null) {
			filter.doFilter(request, response, chain);
		} else {
			// pass the request along the filter chain
			chain.doFilter(request, response);
		}
	}

	@Override
	public void destroy() {
		if (this.filter != null) {
			filter.destroy();
		}
	}
	
	public Filter getServerCorsFilter() {
		Filter resultFilter = null;
		for (String filterName : CORS_FILTERS) {
			try {
		        Class<?> clazz = Class.forName(filterName);
		        Constructor<?> cons = clazz.getDeclaredConstructor();
		        resultFilter = (Filter) cons.newInstance();
				break;
			} catch (Exception ex) {
                log.trace(ex.getMessage(), ex);
			}
		}
		
		if (resultFilter == null) {
			log.error("Failed to prepare CORS filter");
		} else {
			log.debug("Prepared CORS filter: " + resultFilter);
		}

		return resultFilter;
	}

}
