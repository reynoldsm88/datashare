package org.icij.datashare.mode;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import net.codestory.http.security.SessionIdStore;
import net.codestory.http.security.Users;
import org.icij.datashare.IndexResource;
import org.icij.datashare.NamedEntityResource;
import org.icij.datashare.TaskResource;
import org.icij.datashare.session.OAuth2CookieFilter;
import org.icij.datashare.session.RedisSessionIdStore;
import org.icij.datashare.session.RedisUsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Properties;

public class ServerMode extends CommonMode {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    public ServerMode(Properties properties) { super(properties);}

    ServerMode(Map<String, String> properties) { super(properties);}

    @Override
    protected void configure() {
        super.configure();
        bind(Users.class).to(RedisUsers.class);
        bind(SessionIdStore.class).to(RedisSessionIdStore.class);
        String authFilterClassName = propertiesProvider.get("authFilter").orElse("");
        Class<? extends Filter> authFilterClass = OAuth2CookieFilter.class;
        if (!authFilterClassName.isEmpty()) {
            try {
                authFilterClass = (Class<? extends Filter>) Class.forName(authFilterClassName);
                logger.info("setting auth filter to {}", authFilterClass);
            } catch (ClassNotFoundException e) {
                logger.warn("\"{}\" auth filter class not found. Setting filter to {}", authFilterClassName, authFilterClass);
            }
        }
        bind(Filter.class).to(authFilterClass).asEagerSingleton();
    }

    @Override
    protected Routes addModeConfiguration(Routes routes) {
        return routes.add(TaskResource.class).add(IndexResource.class).add(NamedEntityResource.class);
    }
}
