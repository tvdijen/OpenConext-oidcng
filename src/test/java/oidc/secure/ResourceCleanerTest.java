package oidc.secure;

import oidc.AbstractIntegrationTest;
import oidc.SeedUtils;
import oidc.model.AccessToken;
import oidc.model.AuthenticationRequest;
import oidc.model.AuthorizationCode;
import oidc.model.RefreshToken;
import oidc.model.User;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.query.Query;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.data.mongodb.uri=mongodb://127.0.0.1:27017/oidc_test",
                "mongodb_db=oidc_test",
                "cron.node-cron-job-responsible=true"
        })
@SuppressWarnings("unchecked")
public class ResourceCleanerTest extends AbstractIntegrationTest implements SeedUtils {

    @Autowired
    private ResourceCleaner subject;

    @Test
    public void clean() throws URISyntaxException {
        Class[] classes = {AccessToken.class, RefreshToken.class, AuthorizationCode.class, User.class, AuthenticationRequest.class};
        Stream.of(classes).forEach(clazz -> mongoTemplate.remove(new Query(), clazz));
        Date expiresIn = Date.from(LocalDateTime.now().minusDays(1).atZone(ZoneId.systemDefault()).toInstant());
        Stream.of(
                accessToken("value", expiresIn),
                new RefreshToken("value", "sub", "clientId", singletonList("openid"), expiresIn, "value", false),
                new AuthorizationCode("code", "sub", "clientId", emptyList(), new URI("http://redirectURI"),
                        "codeChallenge", "codeChallengeMethod", "nonce", emptyList(), true, expiresIn),
                new User("nope", "unspecifiedNameId", "authenticatingAuthority", "clientId",
                        Collections.emptyMap(), Collections.emptyList()),
                new AuthenticationRequest(UUID.randomUUID().toString(), expiresIn, "http://localhost/authorize")
        ).forEach(o -> mongoTemplate.insert(o));

        subject.clean();

        Stream.of(classes).forEach(clazz -> assertEquals(0, mongoTemplate.findAll(clazz).size()));
    }
}