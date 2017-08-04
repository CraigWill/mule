/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.internal;

import static java.lang.String.format;
import static org.mule.runtime.config.spring.api.XmlConfigurationDocumentLoader.noValidationDocumentLoader;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_CONNECTIVITY_TESTING_SERVICE;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_METADATA_SERVICE;
import static org.mule.runtime.core.api.config.MuleProperties.OBJECT_VALUE_PROVIDER_SERVICE;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.initialiseIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.startIfNeeded;
import static org.mule.runtime.core.api.util.ClassUtils.withContextClassLoader;
import static org.mule.runtime.core.api.util.ExceptionUtils.tryExpecting;

import org.mule.runtime.api.app.declaration.ArtifactDeclaration;
import org.mule.runtime.api.component.ConfigurationProperties;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.metadata.MetadataService;
import org.mule.runtime.api.value.ValueProviderService;
import org.mule.runtime.config.spring.api.XmlConfigurationDocumentLoader;
import org.mule.runtime.config.spring.api.dsl.model.ApplicationModel;
import org.mule.runtime.config.spring.api.dsl.model.ComponentModel;
import org.mule.runtime.config.spring.internal.dsl.model.MinimalApplicationModelGenerator;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigResource;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.connectivity.ConnectivityTestingService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Implementation of {@link MuleArtifactContext} that allows to create configuration components lazily.
 * <p/>
 * Components will be created upon request to use the from the exposed services.
 *
 * @since 4.0
 */
public class LazyMuleArtifactContext extends MuleArtifactContext implements LazyComponentTaskExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(LazyMuleArtifactContext.class);

  private ConnectivityTestingService lazyConnectivityTestingService;
  private ValueProviderService valueProviderService;
  private MetadataService metadataService;

  /**
   * Parses configuration files creating a spring ApplicationContext which is used as a parent registry using the SpringRegistry
   * registry implementation to wraps the spring ApplicationContext
   *
   * @param muleContext the {@link MuleContext} that own this context
   * @param artifactDeclaration the mule configuration defined programmatically
   * @param optionalObjectsController the {@link OptionalObjectsController} to use. Cannot be {@code null} @see
   *        org.mule.runtime.config.spring.internal.SpringRegistry
   * @param parentConfigurationProperties
   * @since 4.0
   */
  public LazyMuleArtifactContext(MuleContext muleContext, ConfigResource[] artifactConfigResources,
                                 ArtifactDeclaration artifactDeclaration, OptionalObjectsController optionalObjectsController,
                                 Map<String, String> artifactProperties, ArtifactType artifactType,
                                 List<ClassLoader> pluginsClassLoaders,
                                 Optional<ConfigurationProperties> parentConfigurationProperties)
      throws BeansException {
    super(muleContext, artifactConfigResources, artifactDeclaration, optionalObjectsController, artifactProperties,
          artifactType, pluginsClassLoaders, parentConfigurationProperties);
    this.applicationModel.executeOnEveryMuleComponentTree(componentModel -> componentModel.setEnabled(false));
  }

  @Override
  protected XmlConfigurationDocumentLoader newXmlConfigurationDocumentLoader() {
    return noValidationDocumentLoader();
  }

  private void createComponents(DefaultListableBeanFactory beanFactory, ApplicationModel applicationModel, boolean mustBeRoot) {
    applyLifecycle(super.createApplicationComponents(beanFactory, applicationModel, mustBeRoot));
  }

  private void applyLifecycle(List<String> createdComponentModels) {
    if (muleContext.isInitialised()) {
      for (String createdComponentModelName : createdComponentModels) {
        Object object = muleContext.getRegistry().get(createdComponentModelName);
        try {
          initialiseIfNeeded(object, true, muleContext);
        } catch (InitialisationException e) {
          throw new RuntimeException(e);
        }
      }
    }
    if (muleContext.isStarted()) {
      for (String createdComponentModelName : createdComponentModels) {
        Object object = muleContext.getRegistry().get(createdComponentModelName);
        try {
          startIfNeeded(object);
        } catch (MuleException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  @Override
  public <T, E extends Exception> T withContext(Location location, Callable<T> callable) throws E {
    return withContextClassLoader(muleContext.getExecutionClassLoader(), () -> {
      MinimalApplicationModelGenerator minimalApplicationModelGenerator =
          new MinimalApplicationModelGenerator(this.applicationModel, componentBuildingDefinitionRegistry);
      try {
        ApplicationModel minimalApplicationModel = minimalApplicationModelGenerator.getMinimalModel(location);
        createComponents((DefaultListableBeanFactory) this.getBeanFactory(), minimalApplicationModel, false);

        return tryExpecting(RuntimeException.class, callable, e -> {
          throw new MuleRuntimeException(e);
        });
      } finally {
        if (minimalApplicationModelGenerator != null) {
          unregisterComponents(minimalApplicationModelGenerator.resolveComponentModelDependencies());
        }
      }
    });
  }

  private void unregisterComponents(List<ComponentModel> componentModels) {
    if (muleContext.isStarted()) {
      componentModels.stream().forEach(componentModel -> {
        final String nameAttribute = componentModel.getNameAttribute();
        if (nameAttribute != null) {
          try {
            muleContext.getRegistry().unregisterObject(nameAttribute);
          } catch (Exception e) {
            logger
                .warn(format("Exception unregistering an object during lazy initialization of component %s, exception message is %s",
                             nameAttribute, e.getMessage()));
            if (logger.isDebugEnabled()) {
              logger.debug(e.getMessage(), e);
            }
          }
        }
      });
    }
    applicationModel.executeOnEveryMuleComponentTree(componentModel -> componentModel.setEnabled(false));
  }

  @Override
  public ConnectivityTestingService getConnectivityTestingService() {
    if (lazyConnectivityTestingService == null) {
      lazyConnectivityTestingService =
          new LazyConnectivityTestingService(this, muleContext.getRegistry().get(OBJECT_CONNECTIVITY_TESTING_SERVICE));
    }
    return lazyConnectivityTestingService;
  }

  @Override
  public MetadataService getMetadataService() {
    if (metadataService == null) {
      metadataService = new LazyMetadataService(this, muleContext.getRegistry().get(OBJECT_METADATA_SERVICE));
    }
    return metadataService;
  }

  @Override
  public ValueProviderService getValueProviderService() {
    if (valueProviderService == null) {
      valueProviderService = new LazyValueProviderService(this, muleContext.getRegistry().get(OBJECT_VALUE_PROVIDER_SERVICE));
    }
    return valueProviderService;
  }
}
