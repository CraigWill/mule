/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.functional.junit4;

import static java.util.Collections.emptyMap;
import static org.mule.runtime.config.spring.api.SpringXmlConfigurationBuilderFactory.createConfigurationBuilder;
import static org.mule.runtime.core.api.config.bootstrap.ArtifactType.APP;

import org.mule.functional.api.flow.FlowRunner;
import org.mule.runtime.container.internal.ContainerClassLoaderFactory;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.construct.FlowConstruct;
import org.mule.runtime.core.api.processor.MessageProcessorChain;
import org.mule.runtime.core.api.util.IOUtils;
import org.mule.runtime.module.artifact.classloader.ArtifactClassLoader;
import org.mule.tck.junit4.AbstractMuleContextTestCase;
import org.mule.tck.processor.FlowAssert;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;

/**
 * A base test case for tests that initialize Mule using a configuration file. The default configuration builder used is
 * SpringXmlConfigurationBuilder. To use this test case, ensure you have the mule-modules-builders JAR file on your classpath. To
 * use a different builder, just overload the <code>getBuilder()</code> method of this class to return the type of builder you
 * want to use with your test.
 */
public abstract class FunctionalTestCase extends AbstractMuleContextTestCase {

  /**
   * The executionClassLoader used to run this test. It will be created per class or per method depending on
   * {@link #disposeContextPerClass}.
   */
  private static ArtifactClassLoader executionClassLoader;

  private volatile boolean tearingDown = false;
  private Set<FlowRunner> runners = new HashSet<>();

  public FunctionalTestCase() {
    super();
    // A functional test case that starts up the management context by default.
    setStartContext(true);
  }

  /**
   * @return
   * @deprecated use getConfigFile instead.
   */
  @Deprecated
  protected String getConfigResources() {
    return null;
  }

  @Override
  protected ConfigurationBuilder getBuilder() throws Exception {
    String configResources = getConfigResources();
    if (configResources != null) {
      return createConfigurationBuilder(configResources, emptyMap(), APP);
    }
    configResources = getConfigFile();
    if (configResources != null) {
      if (configResources.contains(",")) {
        throw new RuntimeException("Do not use this method when the config is composed of several files. Use getConfigFiles method instead.");
      }
      return createConfigurationBuilder(configResources, emptyMap(), APP);
    }
    return createConfigurationBuilder(getConfigFiles(), emptyMap(), APP);
  }

  /**
   * @return a single file that defines a mule application configuration
   */
  protected String getConfigFile() {
    return null;
  }

  /**
   * @return a several files that define a mule application configuration
   */
  protected String[] getConfigFiles() {
    return null;
  }

  protected FlowConstruct getFlowConstruct(String flowName) throws Exception {
    return muleContext.getRegistry().lookupFlowConstruct(flowName);
  }

  @Override
  protected ClassLoader getExecutionClassLoader() {
    if (!isDisposeContextPerClass() || executionClassLoader == null) {
      executionClassLoader =
          new ContainerClassLoaderFactory().createContainerClassLoader(getClass().getClassLoader());
    }

    return executionClassLoader.getClassLoader();
  }

  @Override
  protected void doTearDown() throws Exception {
    synchronized (runners) {
      tearingDown = true;
      for (FlowRunner runner : runners) {
        runner.dispose();
      }
    }
    super.doTearDown();
  }

  protected String loadResourceAsString(String resourceName) throws IOException {
    return IOUtils.getResourceAsString(resourceName, getClass());
  }

  protected InputStream loadResource(String resourceName) throws IOException {
    return IOUtils.getResourceAsStream(resourceName, getClass());
  }

  protected void stopFlowConstruct(String flowName) throws Exception {
    FlowConstruct flowConstruct = getFlowConstruct(flowName);
    Flow flow = (Flow) flowConstruct;
    flow.stop();
  }

  /**
   * Initializes a builder to construct an event and the running context to run it through a flow.
   * 
   * @param flowName
   * @return the {@link FlowRunner}
   */
  protected FlowRunner flowRunner(String flowName) {
    synchronized (runners) {
      if (tearingDown) {
        throw new IllegalStateException("Already tearing down.");
      }
      final FlowRunner flowRunner = new FlowRunner(muleContext, flowName);
      runners.add(flowRunner);
      return flowRunner;
    }
  }

  /**
   * Runs the given flow with a default event
   * 
   * @param flowName the name of the flow to be executed
   * @return the resulting <code>MuleEvent</code>
   * @throws Exception
   */
  protected Event runFlow(String flowName) throws Exception {
    return flowRunner(flowName).run();
  }

  /**
   * Retrieve a flow by name from the registry
   * 
   * @param name Name of the flow to retrieve
   */
  protected Flow lookupFlowConstruct(String name) {
    return (Flow) muleContext.getRegistry().lookupFlowConstruct(name);
  }

  @After
  public final void clearFlowAssertions() throws Exception {
    FlowAssert.reset();
  }

  protected MessageProcessorChain getSubFlow(String subflowName) {
    return (MessageProcessorChain) muleContext.getRegistry().lookupObject(subflowName);
  }
}
