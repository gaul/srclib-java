package com.sourcegraph.javagraph;

import org.apache.commons.lang3.SystemUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Created by sqs on 12/21/14.
 */
public class GradleProject implements Project {
    private SourceUnit unit;
    public GradleProject(SourceUnit unit) {
        this.unit=unit;
    }


    public static String getGradleClassPath(Path build) throws IOException {
        return BuildAnalysis.Gradle.collectMetaInformation(getWrapper(), build).classPath;
    }

    // TODO Merge this function with "getGradleDependencies".
    public static BuildAnalysis.BuildInfo getGradleAttrs(String repoURI, Path build) throws IOException {
        BuildAnalysis.BuildInfo ret = BuildAnalysis.Gradle.collectMetaInformation(getWrapper(), build);

        // HACK: fix the project name inside docker containers. By default, the name of a Gradle project is the name
        // of its containing directory. srclib checks out code to /src inside Docker containers, which makes the name of
        // every Gradle project rooted at the VCS root directory "src". This hack could erroneously change the project
        // name if the name is actually supposed to be "src" (e.g., if the name is set manually).
        if (System.getenv().get("IN_DOCKER_CONTAINER") != null && ret.attrs.artifactID.equals("src")) {
            String[] parts = repoURI.split("/");
            ret.attrs.artifactID = parts[parts.length - 1];
        }

        return ret;
    }

    public static Path getWrapper() {
        Path result;
        if (SystemUtils.IS_OS_WINDOWS) {
            result = Paths.get("./gradlew.bat").toAbsolutePath();
        } else {
            result = Paths.get("./gradlew").toAbsolutePath();
        }
        File tmp = new File(result.toString());
        if (tmp.exists() && !tmp.isDirectory()) {
            return result;
        }

        return null;
    }

    public static HashSet<RawDependency> getGradleDependencies(Path build) throws IOException {
        return BuildAnalysis.Gradle.collectMetaInformation(getWrapper(), build).dependencies;
    }

    @Override
    public Set<RawDependency> listDeps() throws Exception {
        return null;
    }

    @Override
    public List<String> getClassPath() throws Exception {
        return null;
    }

    @Override
    public RawDependency getDepForJAR(Path jarFile) throws Exception {
        return null;
    }

    private static SourceUnit createSourceUnit(Path gradleFile, String repoURI) throws IOException, XmlPullParserException {
        BuildAnalysis.BuildInfo info = getGradleAttrs(repoURI, gradleFile);

        final SourceUnit unit = new SourceUnit();
        unit.Type = "JavaArtifact";
        unit.Name = info.attrs.groupID + "/" + info.attrs.artifactID;
        unit.Dir = PathUtil.normalize(gradleFile.getParent().toString());
        unit.Data.put("GradleFile", PathUtil.normalize(gradleFile.toString()));
        unit.Data.put("Description", info.attrs.description);

        unit.Files = new LinkedList<>();
        Path root = gradleFile.getParent().toAbsolutePath().normalize();
        for (String file : info.sources) {
            unit.Files.add(PathUtil.normalize(root.relativize(Paths.get(file)).toString()));
        }
        unit.sortFiles();

        // This will list all dependencies, not just direct ones.
        unit.Dependencies = new ArrayList<>(getGradleDependencies(gradleFile));

        return unit;
    }


    public static Collection<SourceUnit> findAllSourceUnits(String repoURI) throws IOException {
        HashSet<Path> gradleFiles = ScanUtil.findMatchingFiles("build.gradle");
        List<SourceUnit> units = new ArrayList<>();
        for (Path gradleFile : gradleFiles) {
            try {
                SourceUnit unit = createSourceUnit(gradleFile, repoURI);
                units.add(unit);
            } catch (Exception e) {
                System.err.println("Error processing Gradle build file " + gradleFile + ": " + e.toString());
            }
        }
        return units;
    }
}
