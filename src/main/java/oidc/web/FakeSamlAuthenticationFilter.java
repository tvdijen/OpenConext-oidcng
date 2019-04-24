package oidc.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import oidc.model.User;
import oidc.repository.UserRepository;
import oidc.user.OidcSamlAuthentication;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml.saml2.attribute.Attribute;
import org.springframework.security.saml.saml2.authentication.Assertion;
import org.springframework.security.saml.saml2.authentication.NameIdPrincipal;
import org.springframework.security.saml.saml2.authentication.Subject;
import org.springframework.security.saml.saml2.metadata.NameId;
import org.springframework.security.saml.spi.DefaultSamlAuthentication;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;

public class FakeSamlAuthenticationFilter extends GenericFilterBean {

    private UserRepository userRepository;
    private ObjectMapper objectMapper;

    public FakeSamlAuthenticationFilter(UserRepository userRepository, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if ((authentication == null || !authentication.isAuthenticated()) && !(authentication instanceof DefaultSamlAuthentication)) {
            User user = objectMapper.readValue(IOUtils.toString(new ClassPathResource("data/user.json").getInputStream(), Charset.defaultCharset()), User.class);
            userRepository.deleteAll();
            userRepository.insert(user);

            OidcSamlAuthentication samlAuthentication = new OidcSamlAuthentication(
                    true,
                    getAssertion(),
                    "http://mock-idp",
                    "http://mock-idp",
                    "http://mock-rp",
                    user
            );
            SecurityContextHolder.getContext().setAuthentication(samlAuthentication);
        }
        chain.doFilter(request, response);
    }

    private Assertion getAssertion() {
        Assertion assertion = new Assertion();
        Subject subject = new Subject();

        NameIdPrincipal principal = new NameIdPrincipal();
        principal.setValue("urn:collab:person:example.com:admin");
        principal.setFormat(NameId.UNSPECIFIED);

        subject.setPrincipal(principal);

        assertion.setSubject(subject);
        List<Attribute> attributes = Arrays.asList(
                attribute("urn:mace:dir:attribute-def:displayName", "John Doe"),
                attribute("urn:mace:dir:attribute-def:uid", "admin"),
                attribute("urn:mace:dir:attribute-def:cn", "John Doe"),
                attribute("urn:mace:dir:attribute-def:sn", "Doe"),
                attribute("urn:mace:dir:attribute-def:eduPersonPrincipalName", "j.doe@example.com"),
                attribute("urn:mace:dir:attribute-def:givenName", "John"),
                attribute("urn:mace:dir:attribute-def:mail", "j.doe@example.com"),
                attribute("urn:mace:terena.org:attribute-def:schacHomeOrganization", "example.com"),
                attribute("urn:mace:dir:attribute-def:isMemberOf", "urn:collab:org:surf.nl")
        );
        assertion.setAttributes(attributes);
        return assertion;
    }

    @SuppressWarnings("unchecked")
    private Attribute attribute(String name, String... values) {
        Attribute attribute = new Attribute();
        attribute.setName(name);
        attribute.setValues(Arrays.asList(values));
        return attribute;
    }

}