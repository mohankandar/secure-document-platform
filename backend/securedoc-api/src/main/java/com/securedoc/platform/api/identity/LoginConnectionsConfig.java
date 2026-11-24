package com.securedoc.platform.api.identity;

import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.LoginConnections;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/** Task 0058: platform-identity's login connections (drafts, test sign-in, switching, going back) over the app's database. */
@Configuration
public class LoginConnectionsConfig {

    @Bean
    LoginConnectionStore loginConnectionStore(DataSource dataSource) {
        return new PostgresLoginConnectionStore(dataSource);
    }

    @Bean
    LoginConnections loginConnections(LoginConnectionStore store, IssuerFetch fetch, DevelopmentLicence licence) {
        return new LoginConnections(store, new OidcTestSignIn(fetch, Clock.systemUTC()), licence);
    }
}
