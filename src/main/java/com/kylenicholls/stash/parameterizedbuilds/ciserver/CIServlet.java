package com.kylenicholls.stash.parameterizedbuilds.ciserver;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.nav.NavBuilder;
import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;
import com.google.common.collect.ImmutableMap;
import com.kylenicholls.stash.parameterizedbuilds.item.Server;
import com.kylenicholls.stash.parameterizedbuilds.item.UserToken;

@SuppressWarnings("serial")
public class CIServlet extends HttpServlet {
	private static final Logger logger = LoggerFactory.getLogger(CIServlet.class);
	private static final String USER_TOKEN_PREFIX = "jenkinsToken-";
	private static final String PROJECT_KEY_PREFIX = "projectKey-";
	private static final String JENKINS_USER_SETTINGS = "jenkins.user.settings";
	private static final String ERRORS = "errors";
	private static final String JENKINS_ADMIN_SETTINGS = "jenkins.admin.settings";
	private static final String JENKINS_PROJECT_SETTINGS = "jenkins.admin.settingsProjectAdmin";
	private static final String JENKINS_REPOSITORTY_SETTINGS = "jenkins.admin.settingsRepoAdmin";
	private static final String SERVER = "server";
	private static final String PROJECT_KEY = "projectKey";
	private static final String REPO_SLUG = "repository";
	private final transient SoyTemplateRenderer soyTemplateRenderer;
	private final transient AuthenticationContext authContext;
	private final transient NavBuilder navBuilder;
	private final transient Jenkins jenkins;
	private final transient ProjectService projectService;

	public CIServlet(SoyTemplateRenderer soyTemplateRenderer, AuthenticationContext authContext,
			NavBuilder navBuilder, Jenkins jenkins, ProjectService projectService) {
		this.soyTemplateRenderer = soyTemplateRenderer;
		this.authContext = authContext;
		this.navBuilder = navBuilder;
		this.jenkins = jenkins;
		this.projectService = projectService;
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse res)
			throws ServletException, IOException {
		try {
			String pathInfo = req.getPathInfo();
			if (authContext.isAuthenticated()) {
				if (pathInfo.contains("/jenkins/account")) {
					renderForAccount(res);
				} else if (pathInfo.contains("/jenkins/project/")) {
					renderForProject(res, pathInfo);
				} else if (pathInfo.contains("/jenkins/projects/") && pathInfo.contains("/repository/")) {
					renderForRepository(res, pathInfo);
				} else {
					renderForGlobal(res);
				}
			} else {
				res.sendRedirect(navBuilder.login().next(req.getServletPath() + pathInfo)
						.buildAbsolute());
			}
		} catch (Exception e) {
			logger.error("Exception in CIServlet.doGet: " + e.getMessage(), e);
		}
	}

	private void renderForAccount(HttpServletResponse resp) throws IOException, ServletException {
		ApplicationUser user = authContext.getCurrentUser();
		List<UserToken> projectTokens = jenkins
				.getAllUserTokens(user, projectService.findAllKeys(), projectService);
		render(resp, JENKINS_USER_SETTINGS, ImmutableMap
				.<String, Object> of("user", user, "projectTokens", projectTokens, ERRORS, ""));
	}

	private void renderForProject(HttpServletResponse resp, String pathInfo)
			throws IOException, ServletException {
		String projectKey = pathInfo.replaceAll(".*/jenkins/project/", "").split("/")[0];
		Server projectServer = jenkins.getJenkinsServer(projectKey);
		render(resp, JENKINS_PROJECT_SETTINGS, ImmutableMap
				.<String, Object> of(SERVER, projectServer != null ? projectServer
						: "", PROJECT_KEY, projectKey, ERRORS, ""));
	}

	private void renderForRepository(HttpServletResponse resp, String pathInfo)
			throws IOException, ServletException {
		String[] relPath = pathInfo.replaceAll(".*/jenkins/projects/", "").split("/");
		if(relPath.length < 3) {
			render(resp, JENKINS_REPOSITORTY_SETTINGS, ImmutableMap
					.<String, Object> of(SERVER, "", PROJECT_KEY,"", REPO_SLUG,"", ERRORS, "Cannot resolve url"));
		}
		String projectKey =relPath[0];
		String repository = relPath[2];
		Server projectServer = jenkins.getJenkinsServer(projectKey, repository);
		render(resp, JENKINS_REPOSITORTY_SETTINGS, ImmutableMap
				.<String, Object> of(SERVER, projectServer != null ? projectServer
						: "", PROJECT_KEY, projectKey, REPO_SLUG, repository, ERRORS, ""));
	}

	private void renderForGlobal(HttpServletResponse resp) throws IOException, ServletException {
		Server server = jenkins.getJenkinsServer();
		render(resp, JENKINS_ADMIN_SETTINGS, ImmutableMap
				.<String, Object> of(SERVER, server != null ? server : "", ERRORS, ""));
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse res)
			throws ServletException, IOException {
		try {
			String pathInfo = req.getPathInfo();
			if (pathInfo.contains("/jenkins/account")) {
				postAccountSettings(req.getParameterMap());
				doGet(req, res);
			} else {
				Server server = getServerFromMap(req);
				boolean clearSettings = req.getParameter("clear-settings") != null
						&& "on".equals(req.getParameter("clear-settings")) ? true : false;
				if (pathInfo.contains("/jenkins/project/")) {
					String projectKey = pathInfo.replaceAll(".*/jenkins/project/", "")
							.split("/")[0];
					postProjectSettings(server, clearSettings, projectKey, req, res);
				} else if (pathInfo.contains("/jenkins/projects/")) {
					String[] relPath = pathInfo.replaceAll(".*/jenkins/projects/", "").split("/");
					String projectKey =relPath[0];
					String repository = relPath[2];
					postRepositorySettings(server, clearSettings, projectKey, repository, req, res);
				} else {
					postGlobalSettings(server, clearSettings, req, res);
				}
			}
		} catch (Exception e) {
			logger.error("Exception in CIServlet.doPost: " + e.getMessage(), e);
		}
	}

	private void postAccountSettings(Map<String, String[]> parameters) {
		Set<String> parameterKeys = parameters.keySet();
		for (String key : parameterKeys) {
			if (key.startsWith(PROJECT_KEY_PREFIX)) {
				String projectKey = parameters.get(key)[0];
				String token = parameters.get(USER_TOKEN_PREFIX + projectKey)[0];
				jenkins.saveUserToken(authContext.getCurrentUser().getSlug(), projectKey, token);
			}
		}
	}

	private Server getServerFromMap(HttpServletRequest req) {
		boolean jenkinsAltUrl = req.getParameter("jenkinsAltUrl") != null
				&& "on".equals(req.getParameter("jenkinsAltUrl")) ? true : false;
		return new Server(req.getParameter("jenkinsUrl"), req.getParameter("jenkinsUser"),
				req.getParameter("jenkinsToken"), jenkinsAltUrl);
	}

	private void postGlobalSettings(Server server, boolean clearSettings, HttpServletRequest req,
			HttpServletResponse res) throws IOException, ServletException {
		if (clearSettings) {
			jenkins.saveJenkinsServer(null);
		} else if (server.getBaseUrl().isEmpty()) {
			render(res, JENKINS_ADMIN_SETTINGS, ImmutableMap
					.<String, Object> of(SERVER, server, ERRORS, "Base URL required"));
			return;
		} else {
			jenkins.saveJenkinsServer(server);
		}
		doGet(req, res);
	}

	private void postProjectSettings(Server server, boolean clearSettings, String projectKey,
			HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		if (clearSettings) {
			jenkins.saveJenkinsServer(null, projectKey);
		} else if (server.getBaseUrl().isEmpty()) {
			render(res, JENKINS_PROJECT_SETTINGS, ImmutableMap
					.<String, Object> of(SERVER, server, PROJECT_KEY, projectKey, ERRORS, "Base URL required"));
			return;
		} else {
			jenkins.saveJenkinsServer(server, projectKey);
		}
		doGet(req, res);
	}

	private void postRepositorySettings(Server server, boolean clearSettings, String projectKey, String repository,
									 HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		if (clearSettings) {
			jenkins.saveJenkinsServer(null, projectKey, repository);
		} else if (server.getBaseUrl().isEmpty()) {
			render(res, JENKINS_REPOSITORTY_SETTINGS, ImmutableMap
					.<String, Object> of(SERVER, server, PROJECT_KEY, projectKey, REPO_SLUG, repository,
							ERRORS, "Base URL required"));
			return;
		} else {
			jenkins.saveJenkinsServer(server, projectKey, repository);
		}
		doGet(req, res);
	}

	private void render(HttpServletResponse resp, String templateName, Map<String, Object> data)
			throws IOException, ServletException {
		resp.setContentType("text/html;charset=UTF-8");
		try {
			soyTemplateRenderer.render(resp
					.getWriter(), "com.kylenicholls.stash.parameterized-builds:jenkins-admin-soy", templateName, data);
		} catch (SoyException e) {
			Throwable cause = e.getCause();
			if (cause instanceof IOException) {
				throw (IOException) cause;
			}
			throw new ServletException(e);
		}
	}
}