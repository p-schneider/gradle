/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.configurations.RoleBasedConfigurationContainerInternal;
import org.gradle.api.internal.artifacts.dsl.LazyPublishArtifact;
import org.gradle.api.internal.attributes.AttributesFactory;
import org.gradle.api.internal.java.WebApplication;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.internal.JavaPluginHelper;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.War;
import org.gradle.internal.deprecation.DeprecationLogger;

import javax.inject.Inject;
import java.util.concurrent.Callable;

/**
 * <p>A {@link Plugin} which extends the {@link JavaPlugin} to add tasks which assemble a web application into a WAR
 * file.</p>
 *
 * @see <a href="https://docs.gradle.org/current/userguide/war_plugin.html">WAR plugin reference</a>
 */
public abstract class WarPlugin implements Plugin<Project> {
    public static final String PROVIDED_COMPILE_CONFIGURATION_NAME = "providedCompile";
    public static final String PROVIDED_RUNTIME_CONFIGURATION_NAME = "providedRuntime";
    public static final String WAR_TASK_NAME = "war";

    private final ObjectFactory objectFactory;
    private final AttributesFactory attributesFactory;

    private Project project;
    private JvmFeatureInternal mainFeature;

    @Inject
    public WarPlugin(ObjectFactory objectFactory, AttributesFactory attributesFactory) {
        this.objectFactory = objectFactory;
        this.attributesFactory = attributesFactory;
    }

    @Override
    public void apply(final Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        this.project = project;
        this.mainFeature = JavaPluginHelper.getJavaComponent(project).getMainFeature();

        project.getTasks().withType(War.class).configureEach(task -> {
            task.getWebAppDirectory().convention(project.getLayout().getProjectDirectory().dir("src/main/webapp"));
            task.from(task.getWebAppDirectory());
            task.dependsOn((Callable<FileCollection>) () -> mainFeature.getSourceSet().getRuntimeClasspath());
            task.classpath((Callable<FileCollection>) () -> {
                Configuration providedRuntime = project.getConfigurations().getByName(PROVIDED_RUNTIME_CONFIGURATION_NAME);
                return mainFeature.getSourceSet().getRuntimeClasspath().minus(providedRuntime);
            });
        });

        TaskProvider<War> war = project.getTasks().register(WAR_TASK_NAME, War.class, warTask -> {
            warTask.setDescription("Generates a war archive with all the compiled classes, the web-app content and the libraries.");
            warTask.setGroup(BasePlugin.BUILD_GROUP);
        });

        PublishArtifact warArtifact = new LazyPublishArtifact(war, ((ProjectInternal) project).getFileResolver(), ((ProjectInternal) project).getTaskDependencyFactory());
        DeprecationLogger.whileDisabled(() -> {
            project.getConfigurations().getByName(Dependency.ARCHIVES_CONFIGURATION).getArtifacts().add(warArtifact);
        });
        configureConfigurations(((ProjectInternal) project).getConfigurations(), mainFeature);
        configureComponent(project, warArtifact);
    }

    @SuppressWarnings("deprecation")
    private void configureConfigurations(RoleBasedConfigurationContainerInternal configurationContainer, JvmFeatureInternal mainFeature) {
        Configuration providedCompileConfiguration = configurationContainer.resolvableDependencyScopeLocked(PROVIDED_COMPILE_CONFIGURATION_NAME, conf -> {
            conf.setDescription("Additional compile classpath for libraries that should not be part of the WAR archive.");
        });

        Configuration providedRuntimeConfiguration = configurationContainer.resolvableDependencyScopeLocked(PROVIDED_RUNTIME_CONFIGURATION_NAME, conf -> {
            conf.extendsFrom(providedCompileConfiguration);
            conf.setDescription("Additional runtime classpath for libraries that should not be part of the WAR archive.");
        });

        mainFeature.getImplementationConfiguration().extendsFrom(providedCompileConfiguration);
        mainFeature.getRuntimeClasspathConfiguration().extendsFrom(providedRuntimeConfiguration);
        mainFeature.getRuntimeElementsConfiguration().extendsFrom(providedRuntimeConfiguration);

        JvmTestSuite defaultTestSuite = JavaPluginHelper.getDefaultTestSuite(project);
        configurationContainer.getByName(defaultTestSuite.getSources().getRuntimeClasspathConfigurationName()).extendsFrom(providedRuntimeConfiguration);
    }

    private void configureComponent(Project project, PublishArtifact warArtifact) {
        AttributeContainer attributes = attributesFactory.mutable()
            .attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME));
        project.getComponents().add(objectFactory.newInstance(WebApplication.class, warArtifact, "master", attributes));
    }
}
