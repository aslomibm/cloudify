/*******************************************************************************
 * Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.cloudifysource.shell.installer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.jini.core.discovery.LookupLocator;
import net.jini.discovery.Constants;

import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.dsl.internal.packaging.CloudConfigurationHolder;
import org.cloudifysource.dsl.utils.ServiceUtils;
import org.cloudifysource.shell.AdminFacade;
import org.cloudifysource.shell.ConditionLatch;
import org.cloudifysource.shell.ShellUtils;
import org.cloudifysource.shell.commands.CLIException;
import org.cloudifysource.shell.commands.CLIStatusException;
import org.openspaces.admin.Admin;
import org.openspaces.admin.AdminException;
import org.openspaces.admin.AdminFactory;
import org.openspaces.admin.AgentGridComponent;
import org.openspaces.admin.esm.ElasticServiceManager;
import org.openspaces.admin.gsa.GridServiceAgent;
import org.openspaces.admin.gsm.GridServiceManager;
import org.openspaces.admin.internal.gsa.InternalGridServiceAgent;
import org.openspaces.admin.internal.support.NetworkExceptionHelper;
import org.openspaces.admin.lus.LookupService;
import org.openspaces.admin.pu.ProcessingUnit;
import org.openspaces.admin.pu.ProcessingUnitAlreadyDeployedException;
import org.openspaces.admin.pu.ProcessingUnitInstance;
import org.openspaces.admin.vm.VirtualMachineAware;
import org.openspaces.core.GigaSpace;
import org.openspaces.core.util.MemoryUnit;

import com.gigaspaces.grid.gsa.GSA;
import com.gigaspaces.internal.client.spaceproxy.ISpaceProxy;
import com.gigaspaces.internal.utils.StringUtils;
import com.j_spaces.kernel.Environment;

/**
 * @author rafi, barakm, adaml, noak
 * @since 2.0.0
 * 
 *        This class handles the start up and shut down of the cloud components - management components (LUS,
 *        GSM, ESM), containers (GSCs) and an agent.
 */
public class LocalhostGridAgentBootstrapper {

	private static final int MIN_PROC_ERROR_TIME = 2000;
	// isolate localcloud from default lookup settings
	/**
	 * Default localcloud lookup group.
	 */
	public static final String LOCALCLOUD_LOOKUPGROUP = "localcloud";

	private static final String MANAGEMENT_APPLICATION = ManagementWebServiceInstaller.MANAGEMENT_APPLICATION_NAME;
	private static final String LUS_PORT_CONTEXT_PROPERTY = "com.sun.jini.reggie.initialUnicastDiscoveryPort";
	private static final String AUTO_SHUTDOWN_COMMANDLINE_ARGUMENT = "-Dcom.gs.agent.auto-shutdown-enabled=true";
	private static final int WAIT_AFTER_ADMIN_CLOSED_MILLIS = 10 * 1000;
	private static final String TIMEOUT_ERROR_MESSAGE = "The operation timed out waiting for the agent to start";
	private static final int GSA_MEMORY_IN_MB = 128;
	private static final int LUS_MEMORY_IN_MB = 128;
	private static final int GSM_MEMORY_IN_MB = 128;
	private static final int ESM_MEMORY_IN_MB = 128;
	private static final int REST_MEMORY_IN_MB = 128; // we don't have wars that big
	private static final int MANAGEMENT_SPACE_MEMORY_IN_MB = 64;
	private static final int REST_PORT = 8100;
	private static final String REST_FILE = "tools" + File.separator + "rest" + File.separator + "rest.war";
	private static final String REST_NAME = "rest";
	private static final int WEBUI_MEMORY_IN_MB = 512;
	private static final int WEBUI_PORT = 8099;
	private static final String WEBUI_FILE = "tools" + File.separator + "gs-webui" + File.separator + "gs-webui.war";
	private static final String WEBUI_NAME = "webui";
	private static final String MANAGEMENT_SPACE_NAME = CloudifyConstants.MANAGEMENT_SPACE_NAME;

	private static final String LINUX_SCRIPT_PREFIX = "#!/bin/bash\n";
	private static final String MANAGEMENT_GSA_ZONE = "management";
	private static final long WAIT_EXISTING_AGENT_TIMEOUT_SECONDS = 10;

	// management agent starts 1 global esm, 1 gsm,1 lus
	private static final String[] CLOUD_MANAGEMENT_ARGUMENTS = new String[] { "gsa.global.lus", "0", "gsa.lus", "1",
			"gsa.gsc", "0", "gsa.global.gsm", "0", "gsa.gsm", "1", "gsa.global.esm", "1" };

	// localcloud management agent starts 1 esm, 1 gsm,1 lus
	private static final String[] LOCALCLOUD_MANAGEMENT_ARGUMENTS = new String[] { "gsa.global.lus", "0", "gsa.lus",
			"0", "gsa.gsc", "0", "gsa.global.gsm", "0", "gsa.gsm_lus", "1", "gsa.global.esm", "0", "gsa.esm", "1" };

	private static final String[] AGENT_ARGUMENTS = new String[] { "gsa.global.lus", "0", "gsa.gsc", "0",
			"gsa.global.gsm", "0", "gsa.global.esm", "0" };

	// script must spawn a daemon process (that is not a child process)
	private static final String[] WINDOWS_COMMAND = new String[] { "cmd.exe", "/c", "gs-agent.bat" };
	private static final String[] LINUX_COMMAND = new String[] { "gs-agent.sh" };

	// script must suppress output, since this process is not consuming it and
	// so any output could block it.
	private static final String[] WINDOWS_ARGUMENTS_POSTFIX = new String[] { ">nul", "2>&1" };

	private static final String[] LINUX_ARGUMENTS_POSTFIX = new String[] { ">/dev/null", "2>&1" };

	private final Logger logger = Logger.getLogger(this.getClass().getName());

	private boolean verbose;
	private String lookupGroups;
	private String lookupLocators;
	private String nicAddress;
	private String zone;
	private int progressInSeconds;
	private AdminFacade adminFacade;
	private boolean noWebServices;
	private boolean noManagementSpace;
	private boolean notHighlyAvailableManagementSpace;
	private int lusPort = CloudifyConstants.DEFAULT_LUS_PORT;
	private boolean autoShutdown;
	private boolean waitForWebUi;
	private String cloudContents;
	private boolean force;
	private final List<LocalhostBootstrapperListener> eventsListenersList = 
		new ArrayList<LocalhostBootstrapperListener>();

	/**
	 * Sets verbose mode.
	 * 
	 * @param verbose
	 *            mode (true - on, false - off)
	 */
	public void setVerbose(final boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Sets the lookup groups.
	 * 
	 * @param lookupGroups
	 *            lookup groups
	 */
	public void setLookupGroups(final String lookupGroups) {
		this.lookupGroups = lookupGroups;
	}

	/**
	 * Sets the lookup locators.
	 * 
	 * @param lookupLocators
	 *            lookup locators
	 */
	public void setLookupLocators(final String lookupLocators) {
		this.lookupLocators = lookupLocators;
	}

	/**
	 * Sets the nic address.
	 * 
	 * @param nicAddress
	 *            nic address
	 */
	public void setNicAddress(final String nicAddress) {
		this.nicAddress = nicAddress;
	}

	/**
	 * Sets the zone.
	 * 
	 * @param zone
	 *            Zone name
	 */
	public void setZone(final String zone) {
		this.zone = zone;
	}

	/**
	 * Sets the number of minutes between each progress check.
	 * 
	 * @param progressInSeconds
	 *            number of seconds
	 */
	public void setProgressInSeconds(final int progressInSeconds) {
		this.progressInSeconds = progressInSeconds;
	}

	/**
	 * Sets the admin facade to work with.
	 * 
	 * @param adminFacade
	 *            Admin facade object
	 */
	public void setAdminFacade(final AdminFacade adminFacade) {
		this.adminFacade = adminFacade;
	}

	/**
	 * Sets web services limitation mode (i.e. activation of webui and REST).
	 * 
	 * @param noWebServices
	 *            web services limitation mode (true - not active, false - active web services)
	 */
	public void setNoWebServices(final boolean noWebServices) {
		this.noWebServices = noWebServices;
	}

	/**
	 * Sets management space limitation mode.
	 * 
	 * @param noManagementSpace
	 *            noManagementSpace limitation mode (true - management space will not be installed, false - it
	 *            will be installed)
	 */
	public void setNoManagementSpace(final boolean noManagementSpace) {
		this.noManagementSpace = noManagementSpace;
	}

	/**
	 * Sets automatic shutdown on the agent.
	 * 
	 * @param autoShutdown
	 *            automatic shutdown mode (true - on, false - off)
	 */
	public void setAutoShutdown(final boolean autoShutdown) {
		this.autoShutdown = autoShutdown;
	}

	/**
	 * Sets whether to wait for the web UI installation to complete when starting management components.
	 * 
	 * @param waitForWebui
	 *            waitForWebui mode (true - wait, false - return without waiting)
	 */
	public void setWaitForWebui(final boolean waitForWebui) {
		this.waitForWebUi = waitForWebui;
	}

	/**
	 * Sets the availability mode of the space - if a backup space is required for the space to become
	 * available.
	 * 
	 * @param notHighlyAvailableManagementSpace
	 *            high-availability mode (true - the space will be available without a backup space, false - a
	 *            backup space is required)
	 */
	public void setNotHighlyAvailableManagementSpace(final boolean notHighlyAvailableManagementSpace) {
		this.notHighlyAvailableManagementSpace = notHighlyAvailableManagementSpace;
	}

	/**
	 * Gets the availability mode of the space.
	 * 
	 * @return high-availability mode (true - the space is available when a single instance is ready, false -
	 *         a backup space is required for the space to become available).
	 */
	public boolean isNotHighlyAvailableManagementSpace() {
		return notHighlyAvailableManagementSpace;
	}

	/**
	 * Enables force teardown. The force flag will terminate the gs agent without forcing uninstall on the
	 * currently deployed applications.
	 * 
	 * @param force
	 *            Boolean flag.
	 */
	public void setForce(final boolean force) {
		this.force = force;
	}

	/**
	 * Starts management processes (LUS, GSM, ESM) on a local cloud, and waits until the requested service
	 * installations complete (space, webui, REST), or until the timeout is reached.
	 * 
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to start the processes and services
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void startLocalCloudOnLocalhostAndWait(final int timeout, final TimeUnit timeunit) throws CLIException,
			InterruptedException, TimeoutException {

		setDefaultNicAddress();

		setDefaultLocalcloudLookup();

		startManagementOnLocalhostAndWaitInternal(LOCALCLOUD_MANAGEMENT_ARGUMENTS, timeout, timeunit, true);
	}

	private void setDefaultNicAddress() throws CLIException {

		if (nicAddress == null) {
			try {
				nicAddress = Constants.getHostAddress();
			} catch (final UnknownHostException e) {
				throw new CLIException(e);
			}
		}

		if (verbose) {
			publishEvent("NIC Address=" + nicAddress);
		}
	}

	private static String getLocalcloudLookupGroups() {
		return LOCALCLOUD_LOOKUPGROUP;
	}

	private String getLocalcloudLookupLocators() {
		if (nicAddress == null) {
			throw new IllegalStateException("nicAddress cannot be null");
		}
		return nicAddress + ":" + lusPort;
	}

	/**
	 * Starts management processes (LUS, GSM, ESM) and waits until the requested service installations
	 * complete (space, webui, REST), or until the timeout is reached. The cloud is not a local cloud.
	 * 
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to start the processes and services
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void startManagementOnLocalhostAndWait(final int timeout, final TimeUnit timeunit) throws CLIException,
			InterruptedException, TimeoutException {

		setZone(MANAGEMENT_GSA_ZONE);

		setDefaultNicAddress();

		startManagementOnLocalhostAndWaitInternal(CLOUD_MANAGEMENT_ARGUMENTS, timeout, timeunit, false);
	}

	/**
	 * Shuts down the local agent, if exists, and waits until shutdown is complete or until the timeout is
	 * reached. If management processes (GSM, ESM, LUS) are still active, the agent is not shutdown and a
	 * CLIException is thrown.
	 * 
	 * @param force
	 *            Force the agent to shut down even if the GSC still runs active services
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to shutdown the agent
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void shutdownAgentOnLocalhostAndWait(final boolean force, final int timeout, final TimeUnit timeunit)
			throws CLIException, InterruptedException, TimeoutException {

		setDefaultNicAddress();

		shutdownAgentOnLocalhostAndWaitInternal(false, force, timeout, timeunit);
	}

	/**
	 * Shuts down the local agent, management processes (GSM, ESM, LUS) and GSC. Waits until shutdown is
	 * complete or until the timeout is reached. Active services are forced to shut down.
	 * 
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to shutdown the agent
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void shutdownManagementOnLocalhostAndWait(final int timeout, final TimeUnit timeunit) throws CLIException,
			InterruptedException, TimeoutException {

		setDefaultNicAddress();

		shutdownAgentOnLocalhostAndWaitInternal(true, true, timeout, timeunit);
	}

	/**
	 * Shuts down the local cloud, and waits until shutdown is complete or until the timeout is reached.
	 * 
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 * @throws CLIException
	 *             Reporting a failure to shutdown the agent
	 */
	public void teardownLocalCloudOnLocalhostAndWait(final long timeout, final TimeUnit timeunit)
			throws InterruptedException, TimeoutException, CLIException {

		setDefaultNicAddress();

		setDefaultLocalcloudLookup();

		uninstallApplications(timeout, timeunit);

		shutdownAgentOnLocalhostAndWaitInternal(true, true, timeout, timeunit);
	}

	private void uninstallApplications(final long timeout, final TimeUnit timeunit) throws InterruptedException,
			TimeoutException, CLIException {

		List<String> applicationsList = null;
		boolean applicationsExist = false;
		try {
			if (!adminFacade.isConnected()) {
				throw new CLIException("Failed to fetch applications list. "
						+ "Client is not connected to the rest server.");
			}

			applicationsList = adminFacade.getApplicationsList();
			// If there existed other applications besides the management.
			applicationsExist = applicationsList.size() > 1;
		} catch (final CLIException e) {
			if (!force) {
				throw new CLIStatusException(e, "failed_to_access_rest_before_teardown");
			}
			final String errorMessage = "Failed to fetch the currently deployed applications list."
					+ " Continuing teardown-localcloud.";
			if (verbose) {
				logger.log(Level.FINE, errorMessage, e);
			} else {
				logger.log(Level.FINE, errorMessage);
			}
			// Suppress exception. continue with teardown.
			return;
		}

		if (applicationsExist && !force) {
			throw new CLIStatusException("apps_deployed_before_teardown_localcloud", applicationsList.toString());
		}

		for (final String appName : applicationsList) {
			try {
				if (!appName.equals(MANAGEMENT_APPLICATION)) {
					adminFacade.uninstallApplication(appName);
				}
			} catch (final CLIException e) {
				final String errorMessage = "Application " + appName + " faild to uninstall."
						+ " Continuing teardown-localcloud.";
				if (!force) {
					throw new CLIStatusException(e, "failed_to_uninstall_app_before_teardown", appName);
				}
				if (verbose) {
					logger.log(Level.FINE, errorMessage, e);
					publishEvent(errorMessage);
				} else {
					logger.log(Level.FINE, errorMessage);
				}
			}
		}
		if (applicationsExist) {
			waitForUninstallApplications(timeout, timeunit);
			publishEvent(ShellUtils.getMessageBundle().getString("all_apps_removed_before_teardown"));
			logger.fine(ShellUtils.getMessageBundle().getString("all_apps_removed_before_teardown"));
		}
	}

	private void waitForUninstallApplications(final long timeout, final TimeUnit timeunit)
			throws InterruptedException, TimeoutException, CLIException {
		createConditionLatch(timeout, timeunit).waitFor(new ConditionLatch.Predicate() {

			boolean messagePublished = false;
			@Override
			public boolean isDone() throws CLIException, InterruptedException {
				final List<String> applications = adminFacade.getApplicationsList();

				boolean done = true;
				for (final String applicationName : applications) {
					if (!MANAGEMENT_APPLICATION.equals(applicationName)) {
						done = false;
						break;
					}
				}

				if (!done) {
					if (!messagePublished){
						publishEvent("Waiting for all applications to uninstall");
						messagePublished = true;
					}else{
						publishEvent(null);
					}
					logger.fine("Waiting for all applications to uninstall");
				}

				return done;
			}
		});
	}

	private void setDefaultLocalcloudLookup() {

		if (zone != null) {
			throw new IllegalStateException("Local-cloud does not use zones");
		}

		lusPort = CloudifyConstants.DEFAULT_LOCALCLOUD_LUS_PORT;

		if (lookupLocators == null) {
			setLookupLocators(getLocalcloudLookupLocators());
		}

		if (lookupGroups == null) {
			setLookupGroups(getLocalcloudLookupGroups());
		}
	}

	/**
	 * Shuts down the local agent, if exists, and waits until shutdown is complete or until the timeout is
	 * reached.
	 * 
	 * @param allowManagement
	 *            Allow the agent to shut down even the management processes (GSM, ESM, LUS) it started are
	 *            still active
	 * @param allowContainers
	 *            Allow the agent to shut down even the GSC still runs active services
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to shutdown the agent, or the management/services components still
	 *             require it
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void shutdownAgentOnLocalhostAndWaitInternal(final boolean allowManagement, final boolean allowContainers,
			final long timeout, final TimeUnit timeunit) throws CLIException, InterruptedException, TimeoutException {

		final long end = System.currentTimeMillis() + timeunit.toMillis(timeout);
		final ConnectionLogsFilter connectionLogs = new ConnectionLogsFilter();
		connectionLogs.supressConnectionErrors();
		adminFacade.disconnect();
		final Admin admin = createAdmin();
		GridServiceAgent agent = null;
		try {
			setLookupDefaults(admin);
			try {
				agent = waitForExistingAgent(admin, WAIT_EXISTING_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			} catch (final TimeoutException e) {
				// continue
			}

			if (agent == null) {
				logger.fine("Agent not running on local machine");
				if (verbose){
					publishEvent("Agent not running on local machine");
				}
				throw new CLIStatusException("teardown_failed_agent_not_found");
			} else {
				// If the agent we attempt to shutdown is of a GSC that has active services, allowContainers
				// must be true or an exception will be thrown.
				if (!allowContainers) {
					for (final ProcessingUnit pu : admin.getProcessingUnits()) {
						for (final ProcessingUnitInstance instance : pu) {
							if (agent.equals(instance.getGridServiceContainer().getGridServiceAgent())) {
								throw new CLIException("Cannot shutdown agent since " + pu.getName()
										+ " service is still running on this machine. Use -force flag.");
							}
						}
					}
				}

				// If the agent we attempt to shutdown is a GSM, ESM or LUS, allowManagement must be true or
				// an exception will be thrown.
				if (!allowManagement) {
					final String message = "Cannot shutdown agent since management processes running on this machine. "
							+ "Use the shutdown-management command instead.";

					for (final GridServiceManager gsm : admin.getGridServiceManagers()) {
						if (agent.equals(gsm.getGridServiceAgent())) {
							throw new CLIException(message);
						}
					}

					for (final ElasticServiceManager esm : admin.getElasticServiceManagers()) {
						if (agent.equals(esm.getGridServiceAgent())) {
							throw new CLIException(message);
						}
					}

					for (final LookupService lus : admin.getLookupServices()) {
						if (agent.equals(lus.getGridServiceAgent())) {
							throw new CLIException(message);
						}
					}
				}
				// Close admin before shutting down the agent to avoid false warning messages the admin will
				// create if it concurrently monitor things that are shutting down.
				admin.close();
				shutdownAgentAndWait(agent, ShellUtils.millisUntil(TIMEOUT_ERROR_MESSAGE, end), TimeUnit.MILLISECONDS);
			}
		} finally {
			// close in case of exception, admin support double close if already closed
			admin.close();
			if (agent != null) {
				// admin.close() command does not verify that all of the internal lookup threads are actually
				// terminated
				// therefore we need to suppress connection warnings a little while longer
				Thread.sleep(WAIT_AFTER_ADMIN_CLOSED_MILLIS);
			}
			connectionLogs.restoreConnectionErrors();
		}
	}

	/**
	 * Shuts down the given agent, and waits until shutdown is complete or until the timeout is reached.
	 * 
	 * @param agent
	 *            The agent to shutdown
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 * @throws CLIException
	 *             Reporting a failure to shutdown the agent
	 */
	private void shutdownAgentAndWait(final GridServiceAgent agent, final long timeout, final TimeUnit timeunit)
			throws InterruptedException, TimeoutException, CLIException {

		// We need to shutdown the agent after we close the admin to avoid closed exception since the admin
		// still monitors
		// the deployment behind the scenes, we call the direct proxy to the gsa since the admin is closed and
		// we don't
		// want to use objects it generated
		final GSA gsa = ((InternalGridServiceAgent) agent).getGSA();
		try {
			gsa.shutdown();
		} catch (final RemoteException e) {
			if (!NetworkExceptionHelper.isConnectOrCloseException(e)) {
				logger.log(Level.FINER, "Failed to shutdown GSA", e);
				throw new AdminException("Failed to shutdown GSA", e);
			}
		}

		createConditionLatch(timeout, timeunit).waitFor(new ConditionLatch.Predicate() {
			boolean messagePublished = false;
			/**
			 * Pings the agent to verify it's not available, indicating it was shut down.
			 */
			@Override
			public boolean isDone() throws CLIException, InterruptedException {
				if (!messagePublished){
					publishEvent("Waiting for agent to shutdown");
					messagePublished = true;
				}
				logger.fine("Waiting for agent to shutdown");
				try {
					gsa.ping();
				} catch (final RemoteException e) {
					// Probably NoSuchObjectException meaning the GSA is going down
					return true;
				}
				publishEvent(null);
				return false;
			}

		});
	}

	private void runGsAgentOnLocalHost(final String name, final String[] gsAgentArguments) throws CLIException,
			InterruptedException {

		final List<String> args = new ArrayList<String>();
		args.addAll(Arrays.asList(gsAgentArguments));

		String[] command;
		if (isWindows()) {
			command = Arrays.copyOf(WINDOWS_COMMAND, WINDOWS_COMMAND.length);
			args.addAll(Arrays.asList(WINDOWS_ARGUMENTS_POSTFIX));
		} else {
			command = Arrays.copyOf(LINUX_COMMAND, LINUX_COMMAND.length);
			args.addAll(Arrays.asList(LINUX_ARGUMENTS_POSTFIX));
		}
		if (verbose){
			String message = "Starting "
				+ name
				+ (verbose ? ":\n" + StringUtils.collectionToDelimitedString(Arrays.asList(command), " ") + " "
						+ StringUtils.collectionToDelimitedString(args, " ") : "");
			publishEvent(message);
			logger.fine(message);
		}
		publishEvent(ShellUtils.getMessageBundle().getString("starting_cloudify_management"));
		runCommand(command, args.toArray(new String[args.size()]));

	}

	/**
	 * Starts management processes (LUS, GSM, ESM), and waits until the requested service installations
	 * complete (space, webui, REST), or until the timeout is reached.
	 * 
	 * @param gsAgentArgs
	 *            GS agent start-up switches
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @param isLocalCloud
	 *            Is this a local cloud (true - yes, false - no)
	 * @throws CLIException
	 *             Reporting a failure to start the processes and services
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	private void startManagementOnLocalhostAndWaitInternal(final String[] gsAgentArgs, final int timeout,
			final TimeUnit timeunit, final boolean isLocalCloud) throws CLIException, InterruptedException,
			TimeoutException {
		final long end = System.currentTimeMillis() + timeunit.toMillis(timeout);

		final ConnectionLogsFilter connectionLogs = new ConnectionLogsFilter();
		connectionLogs.supressConnectionErrors();
		final Admin admin = createAdmin();
		try {
			setLookupDefaults(admin);
			GridServiceAgent agent;
			try {
				try {
					if (!isLocalCloud || fastExistingAgentCheck()) {
						waitForExistingAgent(admin, progressInSeconds, TimeUnit.SECONDS);
						throw new CLIException("Agent already running on local machine.");
					}
				} catch (final TimeoutException e) {
					// no existing agent running on local machine
				}

				runGsAgentOnLocalHost("agent and management processes", gsAgentArgs);
				agent = waitForNewAgent(admin, ShellUtils.millisUntil(TIMEOUT_ERROR_MESSAGE, end),
						TimeUnit.MILLISECONDS);
			} finally {
				connectionLogs.restoreConnectionErrors();
			}

			// waiting for LUS, GSM and ESM services to start
			waitForManagementProcesses(agent, ShellUtils.millisUntil(TIMEOUT_ERROR_MESSAGE, end),
					TimeUnit.MILLISECONDS);

			final List<AbstractManagementServiceInstaller> waitForManagementServices = 
					new LinkedList<AbstractManagementServiceInstaller>();

			connectionLogs.supressConnectionErrors();
			try {
				ManagementSpaceServiceInstaller managementSpaceInstaller = null;
				if (!noManagementSpace) {
					final boolean highlyAvailable = !isLocalCloud && !notHighlyAvailableManagementSpace;
					managementSpaceInstaller = new ManagementSpaceServiceInstaller();
					managementSpaceInstaller.setAdmin(agent.getAdmin());
					managementSpaceInstaller.setVerbose(verbose);
					managementSpaceInstaller.setProgress(progressInSeconds, TimeUnit.SECONDS);
					managementSpaceInstaller.setMemory(MANAGEMENT_SPACE_MEMORY_IN_MB, MemoryUnit.MEGABYTES);
					managementSpaceInstaller.setServiceName(MANAGEMENT_SPACE_NAME);
					managementSpaceInstaller.setManagementZone(MANAGEMENT_GSA_ZONE);
					managementSpaceInstaller.setHighlyAvailable(highlyAvailable);
					managementSpaceInstaller.addListeners(this.eventsListenersList);
					try {
						managementSpaceInstaller.install();
						waitForManagementServices.add(managementSpaceInstaller);
					} catch (final ProcessingUnitAlreadyDeployedException e) {
						if (verbose) {
							logger.fine("Service " + MANAGEMENT_SPACE_NAME + " already installed");
							publishEvent("Service " + MANAGEMENT_SPACE_NAME + " already installed");
						}
					}
				}

				if (!noWebServices) {
					final ManagementWebServiceInstaller webuiInstaller = new ManagementWebServiceInstaller();
					webuiInstaller.setAdmin(agent.getAdmin());
					webuiInstaller.setVerbose(verbose);
					webuiInstaller.setProgress(progressInSeconds, TimeUnit.SECONDS);
					webuiInstaller.setMemory(WEBUI_MEMORY_IN_MB, MemoryUnit.MEGABYTES);
					webuiInstaller.setPort(WEBUI_PORT);
					webuiInstaller.setWarFile(new File(WEBUI_FILE));
					webuiInstaller.setServiceName(WEBUI_NAME);
					webuiInstaller.setManagementZone(MANAGEMENT_GSA_ZONE);
					webuiInstaller.addListeners(this.eventsListenersList);
					try {
						webuiInstaller.install();
					} catch (final ProcessingUnitAlreadyDeployedException e) {
						if (verbose) {
							logger.fine("Service " + WEBUI_NAME + " already installed");
							publishEvent("Service " + WEBUI_NAME + " already installed");
						}
					}
					if (waitForWebUi) {
						waitForManagementServices.add(webuiInstaller);
					} else {
						webuiInstaller.logServiceLocation();
					}

					final ManagementWebServiceInstaller restInstaller = new ManagementWebServiceInstaller();
					restInstaller.setAdmin(agent.getAdmin());
					restInstaller.setProgress(progressInSeconds, TimeUnit.SECONDS);
					restInstaller.setVerbose(verbose);
					
					restInstaller.setMemory(REST_MEMORY_IN_MB, MemoryUnit.MEGABYTES);
					restInstaller.setPort(REST_PORT);
					restInstaller.setWarFile(new File(REST_FILE));
					restInstaller.setServiceName(REST_NAME);
					restInstaller.setManagementZone(MANAGEMENT_GSA_ZONE);
					restInstaller.dependencies.add(CloudifyConstants.MANAGEMENT_SPACE_NAME);
					restInstaller.setWaitForConnection();
					restInstaller.addListeners(this.eventsListenersList);
					try {
						restInstaller.install();
					} catch (final ProcessingUnitAlreadyDeployedException e) {
						if (verbose) {
							logger.fine("Service " + REST_NAME + " already installed");
							publishEvent("Service " + REST_NAME + " already installed");
						}
					}
					waitForManagementServices.add(restInstaller);

				}

				for (final AbstractManagementServiceInstaller managementServiceInstaller : waitForManagementServices) {
					managementServiceInstaller.waitForInstallation(adminFacade, agent,
							ShellUtils.millisUntil(TIMEOUT_ERROR_MESSAGE, end), TimeUnit.MILLISECONDS);
					if (managementServiceInstaller instanceof ManagementSpaceServiceInstaller) {
						logger.fine("Writing cloud configuration to space.");
						if (verbose){
							publishEvent("Writing cloud configuration to space.");
						}
						final GigaSpace gigaspace = managementSpaceInstaller.getGigaSpace();

						final CloudConfigurationHolder holder = new CloudConfigurationHolder(getCloudContents());
						gigaspace.write(holder);
						// Shut down the space proxy so that if the cloud is turned down later, there will not
						// be any discovery errors.
						// Note: in a spring environment, the bean shutdown would clean this up.
						// TODO - Move the space writing part into the management space
						// installer and do the clean up there.
						((ISpaceProxy) gigaspace.getSpace()).close();
					}
				}

			} finally {
				connectionLogs.restoreConnectionErrors();
			}
		} finally {
			admin.close();
		}
	}

	private boolean fastExistingAgentCheck() {
		return !ServiceUtils.isPortFree(lusPort);
	}

	/**
	 * This method assumes that the admin has been supplied with this.lookupLocators and this.lookupGroups and
	 * that it applied the defaults if these were null.
	 * 
	 * @param admin
	 */
	private void setLookupDefaults(final Admin admin) {
		if (admin.getGroups().length == 0 || admin.getGroups() == null) {
			throw new IllegalStateException("Admin lookup group must be set");
		}
		this.lookupGroups = StringUtils.arrayToDelimitedString(admin.getGroups(), ",");
		final LookupLocator[] locators = admin.getLocators();
		if (locators != null && locators.length > 0) {
			this.lookupLocators = convertLookupLocatorToString(locators);
		}
	}

	/**
	 * Converts the given locators to a String of comma-delimited locator names. The locator names are of this
	 * format: <locator_host>:<locator_port>
	 * 
	 * @param locators
	 *            an array of {@link LookupLocator} objects to convert to String
	 * @return A comma-delimited list of lookup locators
	 */
	public static String convertLookupLocatorToString(final LookupLocator[] locators) {
		final List<String> trimmedLocators = new ArrayList<String>();
		if (locators != null) {
			for (final LookupLocator locator : locators) {
				trimmedLocators.add(locator.getHost() + ":" + locator.getPort());
			}
		}
		return StringUtils.collectionToDelimitedString(trimmedLocators, ",");
	}

	/**
	 * Starts an agent on the local host. If an agent is already running, a CLIException is thrown.
	 * 
	 * @param timeout
	 *            number of {@link TimeUnit}s to wait
	 * @param timeunit
	 *            the {@link TimeUnit} to use, to calculate the timeout
	 * @throws CLIException
	 *             Reporting a failure to start the processes and services
	 * @throws InterruptedException
	 *             Reporting the thread was interrupted while waiting
	 * @throws TimeoutException
	 *             Reporting the timeout was reached
	 */
	public void startAgentOnLocalhostAndWait(final long timeout, final TimeUnit timeunit) throws CLIException,
			InterruptedException, TimeoutException {

		if (zone == null || zone.length() == 0) {
			throw new CLIException("Agent must be started with a zone");
		}

		setDefaultNicAddress();

		final ConnectionLogsFilter connectionLogs = new ConnectionLogsFilter();
		connectionLogs.supressConnectionErrors();
		final Admin admin = createAdmin();
		try {
			setLookupDefaults(admin);

			try {
				waitForExistingAgent(admin, WAIT_EXISTING_AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
				throw new CLIException("Agent already running on local machine. Use shutdown-agent first.");
			} catch (final TimeoutException e) {
				// no existing agent running on local machine
			}
			runGsAgentOnLocalHost("agent", AGENT_ARGUMENTS);

			// wait for agent to start
			waitForNewAgent(admin, timeout, timeunit);
		} finally {
			admin.close();
			connectionLogs.restoreConnectionErrors();
		}
	}

	private void waitForManagementProcesses(final GridServiceAgent agent, final long timeout, final TimeUnit timeunit)
			throws TimeoutException, InterruptedException, CLIException {

		final Admin admin = agent.getAdmin();

		createConditionLatch(timeout, timeunit).waitFor(new ConditionLatch.Predicate() {

			/**
			 * {@inheritDoc}
			 */
			@Override
			public boolean isDone() throws CLIException, InterruptedException {

				boolean isDone = true;

				if (!isDone(admin.getLookupServices(), "LUS")) {
					if (verbose) {
						logger.fine("Waiting for Lookup Service");
						publishEvent("Waiting for Lookup Service");
					}
					isDone = false;
				}

				if (!isDone(admin.getGridServiceManagers(), "GSM")) {
					if (verbose) {
						logger.fine("Waiting for Grid Service Manager");
						publishEvent("Waiting for Grid Service Manager");
					}
					isDone = false;
				}

				if (admin.getElasticServiceManagers().isEmpty()) {
					if (verbose) {
						logger.fine("Waiting for Elastic Service Manager");
						publishEvent("Waiting for Elastic Service Manager");
					}
					isDone = false;
				}

				if (verbose) {
					logger.fine("Waiting for Management processes to start.");
					publishEvent("Waiting for Management processes to start.");
				}
				
				if (!isDone){
					publishEvent(null);
				}
				
				return isDone;
			}

			private boolean isDone(final Iterable<? extends AgentGridComponent> components, final String serviceName) {
				boolean found = false;
				for (final AgentGridComponent component : components) {
					if (checkAgent(component)) {
						found = true;
						break;
					}
				}

				if (verbose) {
					for (final Object component : components) {
						final GridServiceAgent agentThatStartedComponent = ((AgentGridComponent) component)
								.getGridServiceAgent();
						String agentUid = null;
						if (agentThatStartedComponent != null) {
							agentUid = agentThatStartedComponent.getUid();
						}
						String message = "Detected " + serviceName + " management process " + " started by agent "
								+ agentUid + " ";
						if (!checkAgent((AgentGridComponent) component)) {
							message += " expected agent " + agent.getUid();
						}
						logger.fine(message);
						publishEvent(message);
					}
				}
				if (!verbose){
					publishEvent(null);
				}
				return found;
			}

			private boolean checkAgent(final AgentGridComponent component) {
				return agent.equals(component.getGridServiceAgent());
			}

		});
	}

	private GridServiceAgent waitForExistingAgent(final Admin admin, final long timeout, final TimeUnit timeunit)
			throws InterruptedException, TimeoutException, CLIException {
		return waitForAgent(admin, true, timeout, timeunit);
	}

	private GridServiceAgent waitForNewAgent(final Admin admin, final long timeout, final TimeUnit timeunit)
			throws InterruptedException, TimeoutException, CLIException {
		return waitForAgent(admin, false, timeout, timeunit);
	}

	private GridServiceAgent waitForAgent(final Admin admin, final boolean existingAgent, final long timeout,
			final TimeUnit timeunit) throws InterruptedException, TimeoutException, CLIException {

		final AtomicReference<GridServiceAgent> agentOnLocalhost = new AtomicReference<GridServiceAgent>();

		createConditionLatch(timeout, timeunit).waitFor(new ConditionLatch.Predicate() {
			/**
			 * {@inheritDoc}
			 */
			@Override
			public boolean isDone() throws CLIException, InterruptedException {

				boolean isDone = false;
				for (final GridServiceAgent agent : admin.getGridServiceAgents()) {
					if (checkAgent(agent)) {
						agentOnLocalhost.set(agent);
						isDone = true;
						break;
					}
				}
				if (!isDone) {
						if (existingAgent) {
							logger.fine("Looking for an existing agent running on local machine");
						} else {
							logger.fine("Waiting for the agent on the local machine to start.");
						}
						publishEvent(null);
				}
				return isDone;
			}

			private boolean checkAgent(final GridServiceAgent agent) {
				final String agentNicAddress = agent.getMachine().getHostAddress();
				final String agentLookupGroups = getLookupGroups(agent);
				final boolean checkLookupGroups = lookupGroups != null && lookupGroups.equals(agentLookupGroups);
				final boolean checkNicAddress = nicAddress != null && agentNicAddress.equals(nicAddress)
						|| isThisMyIpAddress(agentNicAddress);
				if (verbose) {
					String message = "Discovered agent nic-address=" + agentNicAddress + " lookup-groups="
							+ agentLookupGroups + ". ";
					if (!checkLookupGroups) {
						message += "Ignoring agent. Filter lookupGroups='" + lookupGroups + "', agent LookupGroups='"
								+ agentLookupGroups + "'";
					}
					if (!checkNicAddress) {
						message += "Ignoring agent. Filter nicAddress='" + nicAddress
								+ "' or local address, agent nicAddress='" + agentNicAddress + "'";
					}
					publishEvent(message);
				}
				return checkLookupGroups && checkNicAddress;
			}

			/**
			 * @see http
			 *      ://stackoverflow.com/questions/2406341/how-to-check-if-an-ip-address-is-the-local-host-
			 *      on-a-multi-homed-system
			 */
			public boolean isThisMyIpAddress(final String ip) {
				InetAddress addr;
				try {
					addr = InetAddress.getByName(ip);
				} catch (final UnknownHostException e) {
					return false;
				}

				// Check if the address is a valid special local or loop back
				if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()) {
					return true;
				}

				// Check if the address is defined on any interface
				try {
					return NetworkInterface.getByInetAddress(addr) != null;
				} catch (final SocketException e) {
					return false;
				}
			}

			private String getLookupGroups(final VirtualMachineAware component) {

				final String prefix = "-Dcom.gs.jini_lus.groups=";
				return getCommandLineArgumentRemovePrefix(component, prefix);
			}

			private String getCommandLineArgumentRemovePrefix(final VirtualMachineAware component, final String prefix) {
				final String[] commandLineArguments = component.getVirtualMachine().getDetails().getInputArguments();
				String requiredArg = null;
				for (final String arg : commandLineArguments) {
					if (arg.startsWith(prefix)) {
						requiredArg = arg;
					}
				}

				if (requiredArg != null) {
					return requiredArg.substring(prefix.length());
				}
				return null;
			}
		});

		return agentOnLocalhost.get();
	}

	private boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private void runCommand(final String[] command, final String[] args) throws CLIException, InterruptedException {

		final File directory = new File(Environment.getHomeDirectory(), "/bin").getAbsoluteFile();

		// gs-agent.sh/bat need full path
		command[command.length - 1] = new File(directory, command[command.length - 1]).getAbsolutePath();

		final List<String> commandLine = new ArrayList<String>();
		commandLine.addAll(Arrays.asList(command));
		commandLine.addAll(Arrays.asList(args));

		final String commandString = StringUtils.collectionToDelimitedString(commandLine, " ");
		final File filename = createScript(commandString);
		final ProcessBuilder pb = new ProcessBuilder().command(filename.getAbsolutePath()).directory(directory);

		String gsaJavaOptions = "-Xmx" + GSA_MEMORY_IN_MB + "m";
		if (autoShutdown) {
			gsaJavaOptions += " " + AUTO_SHUTDOWN_COMMANDLINE_ARGUMENT;
		}
		String lusJavaOptions = "-Xmx" + LUS_MEMORY_IN_MB + "m" + " -D" + LUS_PORT_CONTEXT_PROPERTY + "=" + lusPort;
		String gsmJavaOptions = "-Xmx" + GSM_MEMORY_IN_MB + "m" + " -D" + LUS_PORT_CONTEXT_PROPERTY + "=" + lusPort;
		String esmJavaOptions = "-Xmx" + ESM_MEMORY_IN_MB + "m";
		String gscJavaOptions = "";

		final Map<String, String> environment = pb.environment();
		if (lookupGroups != null) {
			environment.put("LOOKUPGROUPS", lookupGroups);
		}

		if (lookupLocators != null) {
			environment.put("LOOKUPLOCATORS", lookupLocators);
			final String disableMulticast = "-Dcom.gs.multicast.enabled=false";
			gsaJavaOptions += " " + disableMulticast;
			lusJavaOptions += " " + disableMulticast;
			gsmJavaOptions += " " + disableMulticast;
			esmJavaOptions += " " + disableMulticast;
			gscJavaOptions += disableMulticast;
		}

		if (nicAddress != null) {
			environment.put("NIC_ADDR", nicAddress);
		}

		if (zone != null) {
			gsaJavaOptions += " -Dcom.gs.zones=" + zone;
		}

		environment.put("GSA_JAVA_OPTIONS", gsaJavaOptions);
		environment.put("LUS_JAVA_OPTIONS", lusJavaOptions);
		environment.put("GSM_JAVA_OPTIONS", gsmJavaOptions);
		environment.put("ESM_JAVA_OPTIONS", esmJavaOptions);
		environment.put("GSC_JAVA_OPTIONS", gscJavaOptions);

		// start process
		// there is no need to redirect output, since the process suppresses
		// output
		try {
			logger.fine("Executing command: " + commandString);
			final Process proc = pb.start();
			Thread.sleep(MIN_PROC_ERROR_TIME);
			try {
				// The assumption is that if the script contains errors,
				// the processBuilder will finish by the end of the above sleep period.
				if (proc.exitValue() != 0) {
					String errorMessage = "Error while starting agent. "
							+ "Please make sure that another agent is not already running. ";
					if (verbose) {
						errorMessage = errorMessage.concat("Command executed: " + commandString);
					}
					throw new CLIException(errorMessage);
				}
				// ProcessBuilder is still running. We assume the agent script is running fine.
			} catch (final IllegalThreadStateException e) {
				logger.fine("agent is starting...");
			}
		} catch (final IOException e) {
			throw new CLIException("Error while starting agent", e);
		}
	}

	private Admin createAdmin() {
		final AdminFactory adminFactory = new AdminFactory();
		if (lookupGroups != null) {
			adminFactory.addGroups(lookupGroups);
		}

		if (lookupLocators != null) {
			adminFactory.addLocators(lookupLocators);
		}

		final Admin admin = adminFactory.create();
		if (verbose) {
			if (admin.getLocators().length > 0) {
				logger.fine("Lookup Locators=" + convertLookupLocatorToString(admin.getLocators()));
				publishEvent("Lookup Locators=" + convertLookupLocatorToString(admin.getLocators()));
			}

			if (admin.getGroups().length > 0) {
				logger.fine("Lookup Groups=" + StringUtils.arrayToDelimitedString(admin.getGroups(), ","));
				publishEvent("Lookup Groups=" + StringUtils.arrayToDelimitedString(admin.getGroups(), ","));
			}
		}
		return admin;
	}

	private ConditionLatch createConditionLatch(final long timeout, final TimeUnit timeunit) {
		return new ConditionLatch().timeout(timeout, timeunit).pollingInterval(progressInSeconds, TimeUnit.SECONDS)
				.timeoutErrorMessage(TIMEOUT_ERROR_MESSAGE).verbose(verbose);
	}

	private File createScript(final String text) throws CLIException {
		File tempFile;
		try {
			tempFile = File.createTempFile("run-gs-agent", isWindows() ? ".bat" : ".sh");
		} catch (final IOException e) {
			throw new CLIException(e);
		}
		tempFile.deleteOnExit();
		BufferedWriter out = null;
		try {
			try {
				out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tempFile)));
				if (!isWindows()) {
					out.write(LINUX_SCRIPT_PREFIX);
				}
				out.write(text);
			} finally {
				if (out != null) {
					out.close();
				}
			}
		} catch (final IOException e) {
			throw new CLIException(e);
		}
		if (!isWindows()) {
			tempFile.setExecutable(true, true);
		}
		return tempFile;
	}

	/**
	 * Gets the cloud configuration.
	 * 
	 * @return cloudContents Cloud configuration content
	 */
	public String getCloudContents() {
		return cloudContents;
	}

	/**
	 * Sets the cloud configuration content.
	 * 
	 * @param cloudContents
	 *            Cloud configuration
	 */
	public void setCloudContents(final String cloudContents) {
		this.cloudContents = cloudContents;
	}
	
	/**********
	 * Registers an event listener for installation events.
	 * 
	 * @param listener
	 *            the listener.
	 */
	public void addListener(final LocalhostBootstrapperListener listener) {
		this.eventsListenersList.add(listener);
	}

	private void publishEvent(final String event) {
		for (final LocalhostBootstrapperListener listener : this.eventsListenersList) {
			listener.onLocalhostBootstrapEvent(event);
		}
	}

}
