/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
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
package org.cloudifysource.rest.validators;

import static org.cloudifysource.rest.ResponseConstants.FAILED_TO_LOCATE_APP;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.cloudifysource.dsl.internal.CloudifyConstants;
import org.cloudifysource.rest.ResponseConstants;
import org.cloudifysource.rest.controllers.RestErrorException;
import org.openspaces.admin.Admin;
import org.openspaces.admin.application.Application;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Validator for uninstall application command that validates the application name is not 
 * the same as the management application name.
 * 
 * @author adaml
 *
 */
@Component
public class ValidateApplicationNotManagement implements UninstallApplicationValidator{
	
	private static final Logger logger = Logger.getLogger(ValidateGsmState.class.getName());
	
	@Autowired(required = true)
	private Admin admin;
	
	@Override
	public void validate(final UninstallApplicationValidationContext validationContext)
			throws RestErrorException {
		final String appName = validationContext.getApplicationName();
		final Application app = this.admin.getApplications().waitFor(
				appName, 10, TimeUnit.SECONDS);
		if (app == null) {
			logger.log(Level.INFO, "Cannot uninstall application "
					+ appName
					+ " since it has not been discovered yet.");
			throw new RestErrorException(FAILED_TO_LOCATE_APP, appName);
		}
		if (app.getName().equals(CloudifyConstants.MANAGEMENT_APPLICATION_NAME)) {
			logger.log(Level.INFO,
					"Cannot uninstall the Management application.");
			throw new RestErrorException(
					ResponseConstants.CANNOT_UNINSTALL_MANAGEMENT_APP);
		}
		
	}

}
