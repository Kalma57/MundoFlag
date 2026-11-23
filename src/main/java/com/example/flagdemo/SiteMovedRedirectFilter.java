package com.example.flagdemo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * When the site moves to a new address, the OLD server can be left running as a signpost: set
 * mundoflag.redirect-to (env MUNDOFLAG_REDIRECT_TO, e.g. https://mundoflag.duckdns.org) there and every
 * request - pages, old links, apps built with the old address - is sent to the same path on the new
 * server. That keeps every player on one server (vs-friend rooms and the stats live per server).
 *
 * Off (does nothing) unless the setting is filled in, so the real server is never affected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SiteMovedRedirectFilter extends OncePerRequestFilter {

    private final String target;

    public SiteMovedRedirectFilter(@Value("${mundoflag.redirect-to:}") String target) {
        this.target = target.endsWith("/") ? target.substring(0, target.length() - 1) : target;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return target.isBlank();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String query = request.getQueryString();
        String location = target + request.getRequestURI() + (query == null ? "" : "?" + query);
        // 301 for normal page loads; 308 keeps a POST a POST (a guess sent just as the site moves)
        response.setStatus("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) ? 301 : 308);
        response.setHeader("Location", location);
    }
}
