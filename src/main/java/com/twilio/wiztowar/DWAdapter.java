package com.twilio.wiztowar;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.http.HttpServlet;
import javax.ws.rs.core.Application;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.yammer.dropwizard.Service;
import com.yammer.dropwizard.config.Bootstrap;
import com.yammer.dropwizard.config.Configuration;
import com.yammer.dropwizard.config.ConfigurationException;
import com.yammer.dropwizard.config.ConfigurationFactory;
import com.yammer.dropwizard.config.ExtendedEnvironment;
import com.yammer.dropwizard.config.HttpConfiguration;
import com.yammer.dropwizard.config.LoggingFactory;
import com.yammer.dropwizard.jersey.JacksonMessageBodyProvider;
import com.yammer.dropwizard.json.ObjectMapperFactory;
import com.yammer.dropwizard.servlets.ThreadNameFilter;
import com.yammer.dropwizard.tasks.TaskServlet;
import com.yammer.dropwizard.util.Generics;
import com.yammer.dropwizard.validation.Validator;
import com.yammer.metrics.HealthChecks;
import com.yammer.metrics.core.HealthCheck;
import com.yammer.metrics.reporting.AdminServlet;
import com.yammer.metrics.util.DeadlockHealthCheck;

/**
 * The {@link DWAdapter} adapts a Dropwizard {@link Service} to be hooked in to the lifecycle of a WAR.
 */
public abstract class DWAdapter<T extends Configuration> extends Application implements ServletContextListener {

    /**
     * The {@link ServletContext} that Dropwizard will run in.
     */
    private static ServletContext servletContext;

    /**
     * The {@link Logger} to use.
     */
    final static Logger logger = LoggerFactory.getLogger(DWAdapter.class);

    /**
     * The Jersey singletons.
     */
    private HashSet<Object> singletons;

    /**
     * The Jersey classes.
     */
    private HashSet<Class<?>> classes;

    /**
     * The {@link Service} which we are adapting.
     */
    private Service dwService;

    /**
     * The {@link Service} which we are adapting.
     */
    private ExtendedEnvironment environment;

    /**
     * Implementation of the Jersey Application. Returns the classes.
     *
     * @return the Jersey configured classes
     */
    @Override
    public Set<Class<?>> getClasses() {
        synchronized (this) {
            if (dwService == null) {
                initialize();
            }
        }
        return classes;
    }

    /**
     * Implementation of the Jersey Application. Returns the singletons.
     *
     * @return the Jersey configured singletons.
     */
    @Override
    public Set<Object> getSingletons() {
        synchronized (this) {
            if (dwService == null) {
                initialize();
            }
        }
        return singletons;
    }

    /**
     * Initialize the {@link Service} and configure the Servlet and Jersey environment.
     */
    private void initialize() {
        try {
            dwService = getSingletonService();
            if (dwService == null) {
                throw new IllegalStateException("The singleton service is null");
            }
            final Bootstrap<T> bootstrap = new Bootstrap<T>(dwService);
            dwService.initialize(bootstrap);
            final T configuration = parseConfiguration(getConfigurationFile(),
                    getConfigurationClass(),
                    bootstrap.getObjectMapperFactory().copy());
            if (configuration != null) {
                logger.info("The WizToWar adapter defers logging configuration to the application server");
                 new LoggingFactory(configuration.getLoggingConfiguration(),
                         bootstrap.getName()).configure();
            }

            final Validator validator = new Validator();
            environment = new ExtendedEnvironment(bootstrap.getName(), configuration, bootstrap.getObjectMapperFactory(), validator);
            final Map<String, Object> properties = this.getProperties(servletContext);
            if (properties != null) {
                for (final Map.Entry<String, Object> prop : properties.entrySet()) {
                    environment.setJerseyProperty(prop.getKey(), prop.getValue());
                }
            }
            try {

                environment.start();

                bootstrap.runWithBundles(configuration, environment); //Fix for issue https://github.com/twilio/wiztowar/issues/2

                dwService.run(configuration, environment);
                addHealthChecks(environment);
                if (servletContext == null) {
                    throw new IllegalStateException("ServletContext is null");
                }

                createInternalServlet(environment, servletContext);
                createExternalServlet(environment, configuration.getHttpConfiguration(), servletContext);
                environment.validateJerseyResources();
                environment.logEndpoints(configuration);

                // Now collect the Jersey configuration
                singletons = new HashSet<Object>();
                singletons.addAll(environment.getJerseyResourceConfig().getSingletons());
                classes = new HashSet<Class<?>>();
                classes.addAll(environment.getJerseyResourceConfig().getClasses());
                
                Dynamic registration = servletContext.addServlet("Jersey REST Service", environment.getJerseyServletContainer());
                registration.addMapping("/rest/*");
                registration.setLoadOnStartup(1);

            } catch (Exception e) {
                logger.error("Error {} ", e);
                throw new IllegalStateException(e);
            }
        } catch (Exception e) {
            logger.error("Error {} ", e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parse the configuration from the {@link File}.
     *
     * @param file                the {@link File} containing the configuration.
     * @param configurationClass  the configuration class
     * @param objectMapperFactory the {@link ObjectMapperFactory} to use
     * @return the configuration instance
     * @throws IOException
     * @throws ConfigurationException
     */
    private T parseConfiguration(final File file,
                                 final Class<T> configurationClass,
                                 final ObjectMapperFactory objectMapperFactory) throws IOException, ConfigurationException {
        final ConfigurationFactory<T> configurationFactory =
                ConfigurationFactory.forClass(configurationClass, new Validator(), objectMapperFactory);
        if (file != null) {
            if (!file.exists()) {
                throw new FileNotFoundException("File " + file + " not found");
            }
            return configurationFactory.build(file);
        }
        return configurationFactory.build();
    }

    /**
     * Retrieve the configuration class.
     *
     * @return the configuration class.
     */
    protected Class<T> getConfigurationClass() {
        return Generics.getTypeParameter(getClass(), Configuration.class);
    }

    /**
     * This method is adapted from ServerFactory.createInternalServlet.
     */
    private void createInternalServlet(final ExtendedEnvironment env, final ServletContext context) {
        if (context.getMajorVersion() >= 3) {

            // Add the Task servlet
            final ServletRegistration.Dynamic taskServlet = context.addServlet("TaskServlet", new TaskServlet(env.getTasks()));
            taskServlet.setAsyncSupported(true);
            taskServlet.addMapping("/tasks/*");

            // Add the Admin servlet
            final ServletRegistration.Dynamic adminServlet = context.addServlet("AdminServlet", new AdminServlet());
            adminServlet.setAsyncSupported(true);
            adminServlet.addMapping("/admin/*");
        } else throw new IllegalStateException("The WizToWar adapter doesn't support servlet versions under 3");
    }

    /**
     * This method is adapted from ServerFactory.createExternalServlet.
     *
     * @param env     the {@link ExtendedEnvironment} from which we find the resources to act on.
     * @param context the {@link ServletContext} to add to
     */
    private void createExternalServlet(ExtendedEnvironment env, HttpConfiguration config, ServletContext context) {
        context.addFilter("ThreadNameFilter", ThreadNameFilter.class);

        if (!env.getProtectedTargets().isEmpty()) {
            logger.warn("The WizToWar adapter doesn't support protected targets");
        }

        for (ImmutableMap.Entry<String, ServletHolder> entry : env.getServlets().entrySet()) {
            final HttpServlet servletInstance = (HttpServlet) entry.getValue().getServletInstance();
            final ServletRegistration.Dynamic servletReg = context.addServlet(entry.getKey(), servletInstance);
            servletReg.setAsyncSupported(true);
            servletReg.addMapping(entry.getKey());
            servletReg.setLoadOnStartup(1);
        }

        env.addProvider(new JacksonMessageBodyProvider(env.getObjectMapperFactory().build(),
                env.getValidator()));

        for (ImmutableMap.Entry<String, FilterHolder> entry : env.getFilters().entries()) {
            String mapping = entry.getKey();
            FilterHolder holder = entry.getValue();
            FilterRegistration.Dynamic filterReg = context.addFilter(holder.getName(), holder.getClassName());
            filterReg.addMappingForUrlPatterns(null, false, mapping);
            filterReg.setInitParameters(holder.getInitParameters());
        }

        for (EventListener listener : env.getServletListeners()) {
            context.addListener(listener);
        }

        for (Map.Entry<String, String> entry : config.getContextParameters().entrySet()) {
            context.setInitParameter(entry.getKey(), entry.getValue());
        }

        if (env.getSessionHandler() != null) {
            logger.warn("The WizToWar adapter doesn't support custom session handlers.");

        }
    }

    /**
     * This method is adapted from ServerFactory.buildServer.
     *
     * @param env the {@link ExtendedEnvironment} to get {@link HealthCheck}s from.
     */
    private void addHealthChecks(ExtendedEnvironment env) {
        HealthChecks.defaultRegistry().register(new DeadlockHealthCheck());
        for (HealthCheck healthCheck : env.getHealthChecks()) {
            HealthChecks.defaultRegistry().register(healthCheck);
        }

        if (env.getHealthChecks().isEmpty()) {
            logger.warn('\n' +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!    THIS SERVICE HAS NO HEALTHCHECKS. THIS MEANS YOU WILL NEVER KNOW IF IT    !\n" +
                    "!    DIES IN PRODUCTION, WHICH MEANS YOU WILL NEVER KNOW IF YOU'RE LETTING     !\n" +
                    "!     YOUR USERS DOWN. YOU SHOULD ADD A HEALTHCHECK FOR EACH DEPENDENCY OF     !\n" +
                    "!     YOUR SERVICE WHICH FULLY (BUT LIGHTLY) TESTS YOUR SERVICE'S ABILITY TO   !\n" +
                    "!      USE THAT SERVICE. THINK OF IT AS A CONTINUOUS INTEGRATION TEST.         !\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
                    "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
            );
        }


    }

    /**
     * Override to provide your particular Dropwizard Service.
     *
     * @return your {@link Service}
     */
    public abstract Service getSingletonService();

    /**
     * Override to provide your configuration {@link File} location.
     *
     * @return the {@link File} to read the configuration from.
     */
    public abstract File getConfigurationFile();

    /**
     * Override to return properties that will be accessible as Jersey properties in your service.
     * This is useful for accessing servlet context attributes, for example.
     *
     * @return a map of properties
     */
    public Map<String, Object> getProperties(final ServletContext servletContext) {
        return null;
    }

    public void shutDown() {
        try {
            this.environment.stop();
        } catch (Exception e) {
          logger.error("Failed to stop environment cleanly due to {}", e);
        }
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        servletContext = sce.getServletContext();
        initialize();
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        shutDown();
    }
}
