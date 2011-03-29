/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.jpa.processor;

import org.jboss.as.ee.component.AbstractComponentDescription;
import org.jboss.as.ee.component.AbstractDeploymentDescriptorBindingsProcessor;
import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.BindingDescription;
import org.jboss.as.ee.component.BindingSourceDescription;
import org.jboss.as.ee.component.DeploymentDescriptorEnvironment;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ee.component.LazyBindingSourceDescription;
import org.jboss.as.ee.component.LookupBindingSourceDescription;
import org.jboss.as.jpa.container.PersistenceUnitSearch;
import org.jboss.as.jpa.service.PersistenceContextBindingSourceDescription;
import org.jboss.as.jpa.service.PersistenceUnitBindingSourceDescription;
import org.jboss.as.jpa.service.PersistenceUnitService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.metadata.javaee.spec.PersistenceContextReferenceMetaData;
import org.jboss.metadata.javaee.spec.PersistenceContextReferencesMetaData;
import org.jboss.metadata.javaee.spec.PersistenceUnitReferenceMetaData;
import org.jboss.metadata.javaee.spec.PersistenceUnitReferencesMetaData;
import org.jboss.metadata.javaee.spec.PropertiesMetaData;
import org.jboss.metadata.javaee.spec.PropertyMetaData;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.ServiceName;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceContext;
import javax.persistence.PersistenceContextType;
import javax.persistence.PersistenceUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deployment processor responsible for processing persistence unit / context references from deployment descriptors.
 *
 * @author Stuart Douglas
 */
public class PersistenceRefProcessor extends AbstractDeploymentDescriptorBindingsProcessor {


    @Override
    public void deploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final DeploymentDescriptorEnvironment environment = deploymentUnit.getAttachment(Attachments.MODULE_DEPLOYMENT_DESCRIPTOR_ENVIRONMENT);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX);
        final EEModuleDescription description = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);
        if (description == null) {
            return;
        }
        if (environment != null) {
            List<BindingDescription> bindings = getPersistenceUnitRefs(deploymentUnit, environment, module.getClassLoader(), deploymentReflectionIndex, description, null);
            description.getBindingsContainer().addBindings(bindings);
            bindings = getPersistenceContextRefs(deploymentUnit, environment, module.getClassLoader(), deploymentReflectionIndex, description, null);
            description.getBindingsContainer().addBindings(bindings);
        }
        for (final AbstractComponentDescription componentDescription : description.getComponentDescriptions()) {
            if (componentDescription.getDeploymentDescriptorEnvironment() != null) {
                List<BindingDescription> bindings = getPersistenceUnitRefs(deploymentUnit, componentDescription.getDeploymentDescriptorEnvironment(), module.getClassLoader(), deploymentReflectionIndex, description, componentDescription);
                componentDescription.addBindings(bindings);
                bindings = getPersistenceContextRefs(deploymentUnit, componentDescription.getDeploymentDescriptorEnvironment(), module.getClassLoader(), deploymentReflectionIndex, description, componentDescription);
                componentDescription.addBindings(bindings);
            }
        }
    }

    @Override
    public void undeploy(DeploymentUnit context) {
    }

    /**
     * Resolves persistence-unit-ref
     *
     * @param environment               The environment to resolve the elements for
     * @param classLoader               The deployment class loader
     * @param deploymentReflectionIndex The reflection index
     * @return The bindings for the environment entries
     */
    private List<BindingDescription> getPersistenceUnitRefs(DeploymentUnit deploymentUnit, DeploymentDescriptorEnvironment environment, ModuleClassLoader classLoader, DeploymentReflectionIndex deploymentReflectionIndex, EEModuleDescription moduleDescription, AbstractComponentDescription componentDescription) throws DeploymentUnitProcessingException {

        List<BindingDescription> bindingDescriptions = new ArrayList<BindingDescription>();
        if (environment.getEnvironment() == null) {
            return bindingDescriptions;
        }
        PersistenceUnitReferencesMetaData persistenceUnitRefs = environment.getEnvironment().getPersistenceUnitRefs();

        if (persistenceUnitRefs != null) {
            for (PersistenceUnitReferenceMetaData puRef : persistenceUnitRefs) {
                String name = puRef.getName();
                String persistenceUnitName = puRef.getPersistenceUnitName();
                String lookup = puRef.getLookupName();

                if (!isEmpty(lookup) && !isEmpty(persistenceUnitName)) {
                    throw new DeploymentUnitProcessingException("Cannot specify both <lookup-name> (" + lookup + ") and persistence-unit-name (" + persistenceUnitName + ") in <persistence-unit-ref/> for " + componentDescription);
                }
                if (!name.startsWith("java:")) {
                    name = environment.getDefaultContext() + name;
                }

                BindingDescription bindingDescription = new BindingDescription(name);
                bindingDescriptions.add(bindingDescription);


                //add any injection targets
                processInjectionTargets(classLoader, deploymentReflectionIndex, puRef, bindingDescription, PersistenceUnit.class);
                bindingDescription.setBindingType(PersistenceUnit.class.getName());

                if (!isEmpty(lookup)) {
                    if (componentDescription != null) {
                        bindingDescription.setReferenceSourceDescription(new LookupBindingSourceDescription(lookup, componentDescription));
                    } else {
                        bindingDescription.setReferenceSourceDescription(new LookupBindingSourceDescription(lookup, moduleDescription));
                    }
                } else if (!isEmpty(persistenceUnitName)) {
                    bindingDescription.setReferenceSourceDescription(getPersistenceUnitBindingSource(deploymentUnit,persistenceUnitName));
                } else {
                    bindingDescription.setReferenceSourceDescription(new LazyBindingSourceDescription());
                }
            }
        }
        return bindingDescriptions;
    }

    /**
     * Resolves persistence-unit-ref
     *
     * @param environment               The environment to resolve the elements for
     * @param classLoader               The deployment class loader
     * @param deploymentReflectionIndex The reflection index
     * @return The bindings for the environment entries
     */
    private List<BindingDescription> getPersistenceContextRefs(DeploymentUnit deploymentUnit, DeploymentDescriptorEnvironment environment, ModuleClassLoader classLoader, DeploymentReflectionIndex deploymentReflectionIndex, EEModuleDescription moduleDescription, AbstractComponentDescription componentDescription) throws DeploymentUnitProcessingException {

        List<BindingDescription> bindingDescriptions = new ArrayList<BindingDescription>();
        if (environment.getEnvironment() == null) {
            return bindingDescriptions;
        }
        PersistenceContextReferencesMetaData persistenceUnitRefs = environment.getEnvironment().getPersistenceContextRefs();

        if (persistenceUnitRefs != null) {
            for (PersistenceContextReferenceMetaData puRef : persistenceUnitRefs) {
                String name = puRef.getName();
                String persistenceUnitName = puRef.getPersistenceUnitName();
                String lookup = puRef.getLookupName();

                if (!isEmpty(lookup) && !isEmpty(persistenceUnitName)) {
                    throw new DeploymentUnitProcessingException("Cannot specify both <lookup-name> (" + lookup + ") and persistence-unit-name (" + persistenceUnitName + ") in <persistence-context-ref/> for " + componentDescription);
                }
                if (!name.startsWith("java:")) {
                    name = environment.getDefaultContext() + name;
                }

                BindingDescription bindingDescription = new BindingDescription(name);
                bindingDescriptions.add(bindingDescription);

                //add any injection targets
                processInjectionTargets(classLoader, deploymentReflectionIndex, puRef, bindingDescription, PersistenceContext.class);
                bindingDescription.setBindingType(PersistenceContext.class.getName());

                if (!isEmpty(lookup)) {
                    if (componentDescription != null) {
                        bindingDescription.setReferenceSourceDescription(new LookupBindingSourceDescription(lookup, componentDescription));
                    } else {
                        bindingDescription.setReferenceSourceDescription(new LookupBindingSourceDescription(lookup, moduleDescription));
                    }
                } else if (!isEmpty(persistenceUnitName)) {
                    PropertiesMetaData properties = puRef.getProperties();
                    Map map = new HashMap();
                    if(properties != null) {
                        for(PropertyMetaData prop : properties) {
                            map.put(prop.getKey(),prop.getValue());
                        }
                    }
                    bindingDescription.setReferenceSourceDescription(getPersistenceContextBindingSource(deploymentUnit, persistenceUnitName,puRef.getPersistenceContextType(), map));
                } else {
                    bindingDescription.setReferenceSourceDescription(new LazyBindingSourceDescription());
                }
            }
        }
        return bindingDescriptions;
    }


    private BindingSourceDescription getPersistenceUnitBindingSource(
            final DeploymentUnit deploymentUnit,
            final String unitName)
            throws DeploymentUnitProcessingException {

        String scopedPuName = getScopedPuName(deploymentUnit, unitName);
        ServiceName puServiceName = getPuServiceName(scopedPuName);
        return new PersistenceUnitBindingSourceDescription(puServiceName, deploymentUnit, scopedPuName, EntityManagerFactory.class.getName());
    }

    private BindingSourceDescription getPersistenceContextBindingSource(
            final DeploymentUnit deploymentUnit,
            final String unitName, PersistenceContextType type, Map properties)
            throws DeploymentUnitProcessingException {

        String scopedPuName = getScopedPuName(deploymentUnit, unitName);
        ServiceName puServiceName = getPuServiceName(scopedPuName);
        return new PersistenceContextBindingSourceDescription(type, properties, puServiceName, deploymentUnit, scopedPuName, EntityManager.class.getName());
    }

    private String getScopedPuName(final DeploymentUnit deploymentUnit, final String puName)
            throws DeploymentUnitProcessingException {

        String scopedPuName;
        scopedPuName = PersistenceUnitSearch.resolvePersistenceUnitSupplier(deploymentUnit, puName);
        if (null == scopedPuName) {
            throw new DeploymentUnitProcessingException("Can't find a deployment unit named " + puName + " at " + deploymentUnit);
        }
        return scopedPuName;
    }

    private ServiceName getPuServiceName(String scopedPuName)
            throws DeploymentUnitProcessingException {

        return PersistenceUnitService.getPUServiceName(scopedPuName);
    }

    private boolean isEmpty(String string) {
        return string == null || string.isEmpty();
    }

}