package org.gluu.oxauthconfigapi.rest.ressource;

import java.util.Set;
import java.util.HashSet;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import org.slf4j.Logger;

import org.gluu.oxauth.model.configuration.AppConfiguration;
import org.gluu.oxauth.model.common.GrantType;
import org.gluu.oxtrust.service.JsonConfigurationService;
import org.gluu.oxauthconfigapi.rest.model.DynamicConfiguration;
import org.gluu.oxauthconfigapi.util.ApiConstants;

@Path(ApiConstants.BASE_API_URL + ApiConstants.DYN_REGISTRATION)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DynamicConfigurationResource {
	
	@Inject
	Logger log;
	
	@Inject
	JsonConfigurationService jsonConfigurationService;
	
	@GET
	@Operation(summary = "Retrieve dynamic client registration configuration")
	@APIResponses(value = {
			@APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = DynamicConfiguration.class, required = true))),
			@APIResponse(responseCode = "500", description = "Server error") })
	public Response getDynamicConfiguration() {
		try {
			log.debug("DynamicConfigurationResource::getDynamicConfiguration() - Retrieve dynamic client registration configuration");
			AppConfiguration appConfiguration = this.jsonConfigurationService.getOxauthAppConfiguration();
			DynamicConfiguration dynamicConfiguration = new DynamicConfiguration();
			dynamicConfiguration.setDynamicRegistrationEnabled(appConfiguration.getDynamicRegistrationEnabled());
			dynamicConfiguration.setDynamicRegistrationPasswordGrantTypeEnabled(appConfiguration.getDynamicRegistrationPasswordGrantTypeEnabled());
			dynamicConfiguration.setDynamicRegistrationPersistClientAuthorizations(appConfiguration.getDynamicRegistrationPersistClientAuthorizations());
			dynamicConfiguration.setDynamicRegistrationScopesParamEnabled(appConfiguration.getDynamicRegistrationScopesParamEnabled());
			dynamicConfiguration.setLegacyDynamicRegistrationScopeParam(appConfiguration.getLegacyDynamicRegistrationScopeParam());
			dynamicConfiguration.setDynamicRegistrationCustomObjectClass(appConfiguration.getDynamicRegistrationCustomObjectClass());
			dynamicConfiguration.setDefaultSubjectType(appConfiguration.getDefaultSubjectType());
			dynamicConfiguration.setDynamicRegistrationExpirationTime(appConfiguration.getDynamicRegistrationExpirationTime());
			//dynamicConfiguration.setDynamicGrantTypeDefault(appConfiguration.getDynamicGrantTypeDefault());
			dynamicConfiguration.setDynamicRegistrationCustomAttributes(appConfiguration.getDynamicRegistrationCustomAttributes());
			if(appConfiguration.getDynamicGrantTypeDefault() != null && !appConfiguration.getDynamicGrantTypeDefault().isEmpty()) {
				Set<String> dynamicGrantTypeDefault = new HashSet<String>();
				for(GrantType grantType : appConfiguration.getDynamicGrantTypeDefault() )
					dynamicGrantTypeDefault.add(grantType.getValue());
				dynamicConfiguration.setDynamicGrantTypeDefault(dynamicGrantTypeDefault);
			}
        	return Response.ok(dynamicConfiguration).build();
						
		}catch(Exception ex) {
			log.error("Failed to retrieve dynamic client registration configuration", ex);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();			
		}
	}
	
	@PUT
	@Operation(summary = "Update dynamic client registration configuration")
	@APIResponses(value = {
			@APIResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Response.class, required = true))),
			@APIResponse(responseCode = "500", description = "Server error") })
	public Response updateDynamicConfiguration(@Valid DynamicConfiguration dynamicConfiguration) {
		try {
			log.debug("DynamicConfigurationResource::updateDynamicConfiguration() - Update dynamic client registration configuration");
			
			AppConfiguration appConfiguration = this.jsonConfigurationService.getOxauthAppConfiguration();
			appConfiguration.setDynamicRegistrationEnabled(dynamicConfiguration.getDynamicRegistrationEnabled());
			appConfiguration.setDynamicRegistrationPasswordGrantTypeEnabled(dynamicConfiguration.getDynamicRegistrationPasswordGrantTypeEnabled());
			appConfiguration.setDynamicRegistrationPersistClientAuthorizations(dynamicConfiguration.getDynamicRegistrationPersistClientAuthorizations());
			appConfiguration.setDynamicRegistrationScopesParamEnabled(dynamicConfiguration.getDynamicRegistrationScopesParamEnabled());
			appConfiguration.setLegacyDynamicRegistrationScopeParam(dynamicConfiguration.getLegacyDynamicRegistrationScopeParam());
			appConfiguration.setDynamicRegistrationCustomObjectClass(dynamicConfiguration.getDynamicRegistrationCustomObjectClass());
			appConfiguration.setDefaultSubjectType(dynamicConfiguration.getDefaultSubjectType());
			appConfiguration.setDynamicRegistrationExpirationTime(dynamicConfiguration.getDynamicRegistrationExpirationTime());
			//appConfiguration.setDynamicGrantTypeDefault(dynamicConfiguration.getDynamicGrantTypeDefault());
			appConfiguration.setDynamicRegistrationCustomAttributes(dynamicConfiguration.getDynamicRegistrationCustomAttributes());
			if(dynamicConfiguration.getDynamicGrantTypeDefault() != null && !dynamicConfiguration.getDynamicGrantTypeDefault().isEmpty()) {
				Set<GrantType> dynamicGrantTypeDefault = new HashSet<GrantType>();
				for(String strType : dynamicConfiguration.getDynamicGrantTypeDefault() ) {
					GrantType grantType = GrantType.getByValue(strType);
					dynamicGrantTypeDefault.add(grantType);
				}
					
				appConfiguration.setDynamicGrantTypeDefault(dynamicGrantTypeDefault);
			}
			
			//Update
			this.jsonConfigurationService.saveOxAuthAppConfiguration(appConfiguration);
			
			return Response.ok(dynamicConfiguration).build();
		}catch(Exception ex) {
			log.error("Failed to update dynamic client registration configuration", ex);
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();			
		}
	}
	
}
