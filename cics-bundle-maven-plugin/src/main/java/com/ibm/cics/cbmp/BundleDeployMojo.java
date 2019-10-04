package com.ibm.cics.cbmp;

/*-
 * #%L
 * CICS Bundle Maven Plugin
 * %%
 * Copyright (C) 2019 IBM Corp.
 * %%
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * #L%
 */

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import com.ibm.cics.bundle.deploy.BundleDeployException;
import com.ibm.cics.bundle.deploy.BundleDeployHelper;

/**
 * <p>This mojo deploys a CICS bundle to the specified CICS region using the CICS bundle deployment API. A matching bundle definition must be provided in the CSD in advance.</p>
 * <p>The <code>deploy</code> goal is not bound by default, so will not run unless specifically configured. You might choose to configure the <code>deploy</code> goal to run inside a specific profile, so that
 * a developer can choose whether to deploy their bundle with a command-line parameter to switch Maven profiles.</p>
 */
@Mojo(name = "deploy", requiresDependencyResolution = ResolutionScope.TEST, defaultPhase = LifecyclePhase.VERIFY)
public class BundleDeployMojo extends AbstractMojo {
	
	/**
	 * The current user system settings for use in Maven.
	 */
	@Parameter( defaultValue = "${settings}", readonly = true )
	protected Settings settings;
	
	/**
	 * The name of the bundle definition that will install this bundle. Must be present already in the CSD relating
	 * to the configured cicsplex/region, and must be configured with the correct bundle directory according to the 
	 * bundle deployment API configuration and the name of the bundle the user is deploying.
	 */
	@Parameter(required = true)
	private String bunddef;
	
	/**
	 * The CSD group containing the bundle definition to be installed.
	 */
	@Parameter(required = true)
	private String csdgroup;
	
	/**
	 * The ID of a server configured in your Maven settings
	 */
	@Parameter
	private String serverId;
	
	/**
	 * The name of the CICSplex the bundle should be installed into.
	 * Specifying this parameter overrides any value provided within a Maven settings server entry.
	 */
	@Parameter
	private String cicsplex;
	
	/**
	 * The name of the region the bundle should be installed into.
	 * Specifying this parameter overrides any value provided within a Maven settings server entry.
	 */
	@Parameter
	private String region;

	/**
	 * The filename of the bundle archive file to be deployed.
	 */
	@Parameter
	private String bundle;
	
	/**
	 * The classifier of a bundle attached to this project which is to be deployed.
	 * If a value for the @bundle parameter is supplied, this classifier is ignored.
	 */
	@Parameter
	private String classifier;
	
	/**
	 * The full URL of the endpoint.
	 * Specifying this parameter overrides any value provided within a Maven settings server entry.
	 */
	@Parameter
	private String url;
	
	/**
	 * The username to authenticate with.
	 * Specifying this parameter overrides any value provided within a Maven settings server entry.
	 */
	@Parameter
	private String username;
	
	/**
	 * The password to authenticate with.
	 * Specifying this parameter overrides any value provided within a Maven settings server entry.
	 */
	@Parameter
	private String password;
	
	@Parameter(property = "project", readonly = true)
	private MavenProject project;

	@Component
	private SettingsDecrypter settingsDecrypter;

	@Override
	public void execute() throws MojoExecutionException {
		ServerConfig serverConfig = getServerConfig();

		//Override settings.xml with pom configuration
		if (url != null) serverConfig.setEndpointUrl(parseURL(url));
		if (cicsplex != null) serverConfig.setCicsplex(cicsplex);
		if (region != null) serverConfig.setRegion(region);
		if (username != null) serverConfig.setUsername(username);
		if (password != null) serverConfig.setPassword(password);
		
		//Validate mandatory configuration
		if (serverConfig.getEndpointUrl() == null) throw new MojoExecutionException("url must be specified either in plugin configuration or server configuration");
		if (StringUtils.isEmpty(serverConfig.getCicsplex())) throw new MojoExecutionException("cicsplex must be specified either in plugin configuration or server configuration");
		if (StringUtils.isEmpty(serverConfig.getRegion())) throw new MojoExecutionException("region must be specified either in plugin configuration or server configuration");
		
		try {
			BundleDeployHelper.deployBundle(
				serverConfig.getEndpointUrl(),
				getBundle(),
				bunddef,
				csdgroup,
				serverConfig.getCicsplex(),
				serverConfig.getRegion(),
				serverConfig.getUsername(),
				serverConfig.getPassword()
			);
		} catch (BundleDeployException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		} catch (IOException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}
	
	private AuthenticationInfo getAuthenticationInfo(Server server) {
		AuthenticationInfo authInfo = new AuthenticationInfo();
		authInfo.setUsername(server.getUsername());
		authInfo.setPassword(server.getPassword());
		authInfo.setPrivateKey(server.getPrivateKey());
		authInfo.setPassphrase(server.getPassphrase());
		return authInfo;
	}
	
	private ServerConfig getServerConfig() throws MojoExecutionException {
		ServerConfig serverConfig = new ServerConfig();
		if (serverId != null) {
			Server encryptedServer = settings.getServer(serverId);
			Server server;
			if (encryptedServer != null) {
				server = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(encryptedServer)).getServer();
				if (server == null) {
					throw new MojoExecutionException("Server ID is null");
				}
			} else {
				throw new MojoExecutionException("Server '" + serverId + "' does not exist");
			}
			
			AuthenticationInfo authenticationInfo = getAuthenticationInfo(server);
			serverConfig.setUsername(authenticationInfo.getUsername());
			serverConfig.setPassword(authenticationInfo.getPassword());
			
			Object configuration = server.getConfiguration();
			
			if (configuration instanceof Xpp3Dom) {
				Xpp3Dom c = (Xpp3Dom) configuration;
				
				Xpp3Dom endpointUrl = c.getChild("url");
				if (endpointUrl != null) {
					serverConfig.setEndpointUrl(parseURL(endpointUrl.getValue()));
				}
				
				Xpp3Dom cicsplex = c.getChild("cicsplex");
				if (cicsplex != null) {
					serverConfig.setCicsplex(cicsplex.getValue());
				}
				
				Xpp3Dom region = c.getChild("region");
				if (region != null) {
					serverConfig.setRegion(region.getValue());
				}
			} else {
				throw new MojoExecutionException("Unknown server configuration format: " + configuration.getClass());
			}
		}
		return serverConfig;
	}

	private static URI parseURL(String x) throws MojoExecutionException {
		try {
			return new URI(x);
		} catch (URISyntaxException e) {
			throw new MojoExecutionException("Endpoint URL is invalid", e);
		}
	}
	
    private File getBundle() throws MojoExecutionException {
		if (bundle != null) {
			return new File(bundle);
		} else {
			Artifact artifact;
			if (classifier != null) {
				artifact = project
					.getAttachedArtifacts()
					.stream()
					.filter(a -> classifier.equals(a.getClassifier()))
					.findFirst()
					.orElse(null);
			} else {
				artifact = project.getArtifact();
			}
			
			if (artifact == null) {
				throw new MojoExecutionException("Artifact not found");
			}

			//TODO: have a look inside the artifact to see if it contains a cics.xml instead of validating the packaging type
			File file = artifact.getFile();
			if (file != null) {
				return file;
			} else {
				throw new MojoExecutionException("CICS bundle not found");
			}
		}
	}

}