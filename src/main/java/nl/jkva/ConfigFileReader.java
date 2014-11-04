package nl.jkva;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

public class ConfigFileReader {

    private final Log log;
    private final MavenProject project;

    public ConfigFileReader(Log log, MavenProject project) {
        this.log = log;
        this.project = project;
    }

    public Config readConfigFile(List<MavenProject> reactorProjects) throws MojoFailureException {
        final Config config;
        try {
            config = new Config();
            config.setParentPath("");
            config.setDistPath("");
            config.setName("TODO");
            config.setProjectBase(project.getBasedir().getCanonicalFile());

            setModules(project, reactorProjects, config.getModules());
            setModuleParents(config);
            setReleasableModuleParents(config);
            updateAutoDeterminedFields(config);
        } catch (IOException e) {
            throw new MojoFailureException("Invalid config file, valid JSON?", e);
        }
        return config;
    }

    private void setModules(MavenProject mavenProject, List<MavenProject> reactorProjects, List<ProjectModule> modules)
            throws MojoFailureException {
        List<String> moduleNames = mavenProject.getModules();
        for (String moduleName : moduleNames) {
            MavenProject moduleProject = findMavenProjectForModule(mavenProject, reactorProjects, moduleName);
            ProjectModule projectModule = new ProjectModule();
            projectModule.setGroupId(moduleProject.getGroupId());
            projectModule.setArtifactId(moduleProject.getArtifactId());
            projectModule.setRelatedMavenProject(moduleProject);

            String modulePath;
            String projectPath;
            try {
                modulePath = moduleProject.getFile().getParentFile().getCanonicalPath();
                projectPath = mavenProject.getFile().getParentFile().getCanonicalPath();
            } catch (IOException e) {
                throw new MojoFailureException("", e);
            }
            String relativeModulePath = modulePath.replace(projectPath, "").replace("\\", "/").substring(1);
            projectModule.setPath(relativeModulePath);

            modules.add(projectModule);
            setModules(moduleProject, reactorProjects, projectModule.getModules());
        }
    }

    private void setReleasableModuleParents(Config config) throws MojoFailureException {
        List<ProjectModule> allModules = ConfigUtil.getFlatListOfAllModules(config.getModules());
        for (ProjectModule module : allModules) {
            boolean b = determineReleasableModuleParent(module);
            module.setReleasableModuleParent(b);
        }
    }

    private boolean determineReleasableModuleParent(ProjectModule module) throws MojoFailureException {
        try {
            File pomFile = module.getRelatedMavenProject().getFile();
            List<String> strings = Files.readLines(pomFile, Charsets.UTF_8);
            for (String string : strings) {
                if (string.contains("<scm>")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new MojoFailureException("IOException", e);
        }
    }

    private void setModuleParents(Config config) throws MojoFailureException {
        List<ProjectModule> allModules = ConfigUtil.getFlatListOfAllModules(config.getModules());
        for (ProjectModule module : allModules) {
            MavenProject relatedMavenProject = module.getRelatedMavenProject();
            if (relatedMavenProject == null) {
                throw new MojoFailureException("Null related maven project: " + module);
            }
            MavenProject parent = relatedMavenProject.getParent();
            if (parent == null) {
                throw new MojoFailureException("Null parent maven project for module: " + module);
            }
            String groupId = parent.getGroupId();
            String artifactId = parent.getArtifactId();
            ProjectModule parentModule = findProjectModule(groupId, artifactId, allModules);
            module.setParent(parentModule);
        }
    }

    private ProjectModule findProjectModule(String groupId, String artifactId, List<ProjectModule> allModules)
            throws MojoFailureException {
        for (ProjectModule module : allModules) {
            if (module.getGroupId().equals(groupId) && module.getArtifactId().equals(artifactId)) {
                return module;
            }
        }
        return null;
    }

    private MavenProject findMavenProjectForModule(MavenProject mavenProject, List<MavenProject> reactorProjects,
                                                   String moduleName) throws MojoFailureException {
        String qModuleName = mavenProject.getFile().getParentFile().getPath() + "\\" + moduleName;
        qModuleName = qModuleName.replace("\\", "/");
        for (MavenProject reactorProject : reactorProjects) {
            String reactorPath = reactorProject.getFile().getParentFile().getPath();
            reactorPath = reactorPath.replace("\\", "/");
            // log.info("reactorProject: " + reactorPath);
            if (reactorPath.equalsIgnoreCase(qModuleName)) {
                // log.info("qmodulename: " + qModuleName + " resolved to: " + reactorPath);
                return reactorProject;
            }
        }
        throw new MojoFailureException("Cannot find module MavenProject for module name: " + moduleName);
    }

    private void updateAutoDeterminedFields(Config config) {
        final List<ProjectModule> modules = config.getModules();
        for (ProjectModule module : modules) {
            updateAutoDeterminedFieldsForSubModules(module);
        }
    }

    private void updateAutoDeterminedFieldsForSubModules(ProjectModule module) {
        for (ProjectModule subModule : module.getModules()) {
            subModule.setParent(module);
            determineReleasableParent(subModule);
            updateAutoDeterminedFieldsForSubModules(subModule);
        }
    }

    private void determineReleasableParent(ProjectModule module) {
        if (module.getParent() != null) {
            ProjectModule parent = module;
            while ((parent = parent.getParent()) != null) {
                if (parent.isReleasableModuleParent()) {
                    module.setReleasableParent(parent);
                    return;
                }
            }
        }
    }

    public String outputConfig(Config config) {
        final List<ProjectModule> modules = config.getModules();
        return outputModules(modules, "");
    }

    private String outputModules(List<ProjectModule> modules, String indent) {
        StringBuilder ret = new StringBuilder();
        for (ProjectModule module : modules) {
            ret.append(indent).append(" - ").append(module.toString()).append('\n');
            ret.append(outputModules(module.getModules(), indent + ".."));
        }
        return ret.toString();
    }

}
