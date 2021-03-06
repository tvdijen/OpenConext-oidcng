/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package oidc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import oidc.repository.AuthenticationRequestRepository;
import oidc.repository.OpenIDClientRepository;
import oidc.repository.UserRepository;
import oidc.secure.LoggingStrictHttpFirewall;
import oidc.user.SamlProvisioningAuthenticationManager;
import oidc.web.ConcurrentSavedRequestAwareAuthenticationSuccessHandler;
import oidc.web.ConfigurableSamlAuthenticationRequestFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.saml.SamlRequestMatcher;
import org.springframework.security.saml.SamlValidator;
import org.springframework.security.saml.provider.SamlServerConfiguration;
import org.springframework.security.saml.provider.provisioning.SamlProviderProvisioning;
import org.springframework.security.saml.provider.service.ServiceProviderService;
import org.springframework.security.saml.provider.service.authentication.SamlAuthenticationResponseFilter;
import org.springframework.security.saml.provider.service.config.SamlServiceProviderServerBeanConfiguration;
import org.springframework.security.saml.spi.DefaultValidator;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import javax.servlet.Filter;
import java.io.IOException;

@Configuration
@EnableScheduling
public class BeanConfig extends SamlServiceProviderServerBeanConfiguration {


    private AppConfig appConfiguration;
    private UserRepository userRepository;
    private AuthenticationRequestRepository authenticationRequestRepository;
    private OpenIDClientRepository openIDClientRepository;
    private ObjectMapper objectMapper;

    public BeanConfig(AppConfig config,
                      UserRepository userRepository,
                      AuthenticationRequestRepository authenticationRequestRepository,
                      OpenIDClientRepository openIDClientRepository,
                      ObjectMapper objectMapper) {
        this.appConfiguration = config;
        this.userRepository = userRepository;
        this.openIDClientRepository = openIDClientRepository;
        this.authenticationRequestRepository = authenticationRequestRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected SamlServerConfiguration getDefaultHostSamlServerConfiguration() {
        return appConfiguration;
    }

    @Override
    @Bean
    public Filter spSelectIdentityProviderFilter() {
        return (request, response, chain) -> chain.doFilter(request, response);
    }

    @Override
    @Bean
    public SamlValidator samlValidator() {
        DefaultValidator defaultValidator = (DefaultValidator) super.samlValidator();
        //IdP determines session expiration not we
        defaultValidator.setMaxAuthenticationAgeMillis(1000 * 60 * 60 * 24 * 365);
        defaultValidator.setResponseSkewTimeMillis(1000 * 60 * 10);
        return defaultValidator;
    }

    @Override
    @Bean
    public Filter spAuthenticationRequestFilter() {
        SamlProviderProvisioning<ServiceProviderService> provisioning = getSamlProvisioning();
        SamlRequestMatcher requestMatcher = new SamlRequestMatcher(provisioning, "authorize", false);
        return new ConfigurableSamlAuthenticationRequestFilter(provisioning, requestMatcher,
                authenticationRequestRepository, openIDClientRepository);
    }

    @Bean
    public SamlProvisioningAuthenticationManager samlProvisioningAuthenticationManager() throws IOException {
        return new SamlProvisioningAuthenticationManager(this.userRepository, this.objectMapper);
    }

    @Bean
    public StrictHttpFirewall strictHttpFirewall() {
        return new LoggingStrictHttpFirewall();
    }

    @Override
    @Bean
    public Filter spAuthenticationResponseFilter() {
        SamlAuthenticationResponseFilter filter =
                (SamlAuthenticationResponseFilter) super.spAuthenticationResponseFilter();
        try {
            filter.setAuthenticationManager(this.samlProvisioningAuthenticationManager());
            filter.setAuthenticationSuccessHandler(new ConcurrentSavedRequestAwareAuthenticationSuccessHandler(this.authenticationRequestRepository));
        } catch (IOException e) {
            //super has no throw clause
            throw new RuntimeException(e);
        }
        return filter;

    }
}
