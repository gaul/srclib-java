package com.sourcegraph.javagraph;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Model;
import org.apache.maven.model.Scm;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.RemoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves URI to {@code}ResolvedTarget{@code}
 */
public class Resolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(Resolver.class);

    private final Project proj;
    private final SourceUnit unit;
    private List<ArtifactRepository> artifactRepositories;

    private static RepositorySystem repositorySystem;
    private static RepositorySystemSession repositorySystemSession;
    private Map<String, DepResolution> depsCache;

    private static Map<Pattern, String> overrides;

    static {
        initRepositorySystem();

        overrides = new HashMap<>();
        InputStream is = Resolver.class.getResourceAsStream("/resolver.properties");
        if (is != null) {
            try {
                Properties props = new Properties();
                props.load(is);
                for (Object key : props.keySet()) {
                    overrides.put(Pattern.compile(key.toString()), props.get(key).toString());
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load substitution properties", e);
            }
        }
    }

    /**
     * Initializes Maven's local repository system and session
     */
    private static void initRepositorySystem() {
        repositorySystem = MavenProject.newRepositorySystem();
        repositorySystemSession = MavenProject.newRepositorySystemSession(repositorySystem);
    }

    /**
     * Constructs new resolver object
     * @param proj project to use
     * @param unit source unit
     */
    public Resolver(Project proj, SourceUnit unit, List<ArtifactRepository> artifactRepositories) {
        this.proj = proj;
        this.unit = unit;
        this.artifactRepositories = artifactRepositories;
        this.depsCache = new HashMap<>();
    }

    private Map<URI,ResolvedTarget> resolvedOrigins = new HashMap<>();

    /**
     * Resolves URI to target
     * @param origin jar of file URI
     * @return resolved target or null if URI cannot be resolved to known repository / unit
     * @throws Exception
     */
    public ResolvedTarget resolveOrigin(URI origin) throws Exception {
        if (origin == null) {
            return null;
        }
        URI normalizedOrigin = normalizeOrigin(origin);

        if (resolvedOrigins.containsKey(normalizedOrigin)) {
            return resolvedOrigins.get(normalizedOrigin);
        }

        Path jarFile;
        try {
            jarFile = getOriginJARFilePath(normalizedOrigin);
        } catch (URISyntaxException e) {
            LOGGER.warn("Error getting origin file path for origin: {}", normalizedOrigin, e);
            resolvedOrigins.put(normalizedOrigin, null);
            return null;
        }

        if (jarFile == null) {
            // trying to resolve origin based on source directories
            ResolvedTarget target = resolveFileOrigin(normalizedOrigin);
            resolvedOrigins.put(normalizedOrigin, target);
            return target;
        }

        ResolvedTarget target = processSpecialJar(origin, jarFile);
        if (target != null) {
            return target;
        }

        RawDependency rawDep = null;
        try {
            rawDep = proj.getDepForJAR(jarFile);
        } catch (Exception e) {
            LOGGER.warn("Error resolving JAR file path {} to dependency", jarFile, e);
        }
        if (rawDep == null) {
            resolvedOrigins.put(normalizedOrigin, null);
            return null;
        }

        DepResolution res = resolveRawDep(rawDep);
        if (res.Error != null) {
            resolvedOrigins.put(normalizedOrigin, null);
            return null;
        }
        resolvedOrigins.put(normalizedOrigin, res.Target);
        return res.Target;
    }

    /**
     * Detects special JAR files
     * @param origin origin to check
     * @param jarFile jar file we are working with
     * @return resolved target if jar file matches known one
     */
    private ResolvedTarget processSpecialJar(URI origin, Path jarFile) {
        if (PathUtil.normalize(jarFile.toString()).contains("jre/lib/")) {
            ResolvedTarget target = ResolvedTarget.jdk();
            resolvedOrigins.put(origin, target);
            return target;
        } else if (jarFile.getFileName().toString().equals("tools.jar")) {
            ResolvedTarget target = ResolvedTarget.langtools();
            resolvedOrigins.put(origin, target);
            return target;
        } else if (jarFile.getFileName().toString().equals("nashorn.jar")) {
            ResolvedTarget target = ResolvedTarget.nashorn();
            resolvedOrigins.put(origin, target);
            return target;
        } else if (jarFile.getFileName().toString().equals("android.jar")) {
            return AndroidOriginResolver.resolve(origin);
        }
        return null;
    }

    /**
     * @return the origin JAR file as a Path if its URI is a "jar:file:" or "file:" URI. For "jar:file:" URIs, the path inside the JAR after the "!" is stripped.
     */
    private static Path getOriginJARFilePath(URI origin) throws URISyntaxException {
        if (origin == null) return null;
        if (origin.getScheme().equals("jar")) {
            URI fileURI = new URI(origin.getRawSchemeSpecificPart());
            if (!fileURI.getScheme().equals("file")) {
                throw new URISyntaxException(origin.toString(), "def origin URI must be a jar:file: URI, not jar:" + fileURI.getScheme());
            }
            File localFile = new File(fileURI);

            // Split on the "!" (in, e.g., "jar:file:/path/to/my.jar!/path/to/class/file.class").
            String path = localFile.getPath();
            int i = path.indexOf('!');
            if (i != -1) {
                path = path.substring(0, i);
            }
            return FileSystems.getDefault().getPath(path);
        } else {
            // TODO (alexsaveliev) should we report an error or what?
            //throw new URISyntaxException(origin.toString(), "def origin URI must be a jar:file: URI");
            return null;
        }
    }

    /**
     * @param lookup GroupID + "/" + ArtifactID
     * @return A VCS url, if an override was found, null if not.
     */
    public static String checkOverrides(String lookup) {
        for (Map.Entry<Pattern, String> entry : overrides.entrySet()) {
            Matcher m = entry.getKey().matcher(lookup);
            if (m.find()) {
                return m.replaceAll(entry.getValue());
            }
        }
        return null;
    }

    /**
     * Try to resolveOrigin this raw Dependency to its VCS target.
     *
     * @return The DepResolution Object. Error will be non-null if a DepResolution
     * could not be performed.
     */
    public DepResolution resolveRawDep(RawDependency d) {

        String groupId = d.groupID;
        String key = groupId + ':' + d.artifactID + ':' + d.version + ':' + d.scope;
        DepResolution resolution = depsCache.get(key);
        if (resolution != null) {
            return resolution;
        }

        // HACK: Assume that if groupID of the RawDependency equals the groupID
        // of the current project, then it is from the same repo and shouldn't be resolved externally.
        if (StringUtils.substringBefore(unit.Name, "/").equals(groupId)) {
            ResolvedTarget target = new ResolvedTarget();
            target.ToUnit = groupId + '/' + d.artifactID;
            target.ToUnitType = SourceUnit.DEFAULT_TYPE;
            target.ToVersionString = d.version;
            resolution = new DepResolution(d, target);
            depsCache.put(key, resolution);
            return resolution;
        }

        String cloneURL = checkOverrides(groupId + '/' + d.artifactID);

        // We may know repo URI already
        if (cloneURL != null || d.repoURI != null) {
            ResolvedTarget target = new ResolvedTarget();
            target.ToUnit = groupId + '/' + d.artifactID;
            target.ToUnitType = SourceUnit.DEFAULT_TYPE;
            target.ToVersionString = d.version;
            target.ToRepoCloneURL = cloneURL == null ? d.repoURI : cloneURL;
            resolution = new DepResolution(d, target);
            depsCache.put(key, resolution);
            return resolution;
        }

        DepResolution res = new DepResolution(d, null);

        List<RemoteRepository> remoteRepositories = RepositoryUtils.toRepos( this.artifactRepositories );
        remoteRepositories.add(0, new RemoteRepository.Builder(
                "central", "default", "http://central.maven.org/maven2/").build());

        // Get the url to the POM file for this artifact
        for (RemoteRepository repo : remoteRepositories) {
            String urlBase = repo.getUrl();

            if (!urlBase.endsWith("/")) {
                urlBase += "/";
            }

            String url = urlBase
                + groupId.replace('.', '/') + '/' + d.artifactID + '/'
                + d.version + '/' + d.artifactID + '-' + d.version + ".pom";
            LOGGER.debug("Trying to resolve dependency {} - {}", d, url);

            try {
                URLConnection urlConnection = new URL(url).openConnection();
                if (repo.getAuthentication() != null) {
                    AuthenticationContext context = AuthenticationContext.forRepository(repositorySystemSession, repo);
                    String header = context.get(AuthenticationContext.USERNAME) + ":" + context.get(AuthenticationContext.PASSWORD);
                    urlConnection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(header.getBytes()));
                }
                InputStream input = new BOMInputStream(urlConnection.getInputStream());

                MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
                Model model = xpp3Reader.read(input);
                input.close();

                Scm scm = model.getScm();
                if (scm != null) {
                    cloneURL = scm.getUrl();
                }

                if (cloneURL != null) {
                    res.Raw = d;

                    ResolvedTarget target = new ResolvedTarget();
                    target.ToRepoCloneURL = cloneURL;
                    target.ToUnit = groupId + '/' + d.artifactID;
                    target.ToUnitType = SourceUnit.DEFAULT_TYPE;
                    target.ToVersionString = d.version;

                    res.Target = target;
                } else {
                    res.Error = d.artifactID + " does not have an associated SCM repository.";
                    LOGGER.debug("Unable to find SCM repository {} - {}", d, res.Error);
                }

                break;

            } catch (Exception e) {
                res.Error = "Could not download file " + e.getMessage();
                LOGGER.debug("Unable to resolve dependency {} - {}, trying next server...", d, res.Error);
            }
        }

        if (res.Error != null) {
            // TODO (alexsaveliev) should we consider this situation as a warning or a normal one?
            LOGGER.info("Unable to resolve dependency {} - {}", d, res.Error);
        }
        depsCache.put(key, res);

        return res;
    }

    /**
     * Resolves file-based URI to origin
     * @param origin file-based URI
     * @return resolved target or null if resolution failed
     */
    @SuppressWarnings("unchecked")
    private ResolvedTarget resolveFileOrigin(URI origin) {
        if (!origin.getScheme().equals("file")) {
            return null;
        }
        List<List<String>> sourceDirs = (List<List<String>>) unit.Data.get("SourcePath");
        if (sourceDirs == null) {
            return null;
        }
        File file = new File(origin);
        File cwd = PathUtil.CWD.toFile();
        for (List<String> dir : sourceDirs) {
            File root = PathUtil.concat(cwd, dir.get(2));
            try {
                if (root.isDirectory() && FileUtils.directoryContains(root, file)) {
                    ResolvedTarget target = new ResolvedTarget();
                    target.ToUnit = dir.get(0);
                    target.ToUnitType = SourceUnit.DEFAULT_TYPE;
                    target.ToVersionString = dir.get(1);
                    return target;
                }
            } catch (IOException e) {
                LOGGER.warn("I/O error while resolving local file origin for {} in {}", file, root, e);
            }
        }
        return null;
    }

    /**
     * Removes !.... from jar URI
     * @param origin origin to normalize
     * @return origin with stripped !... tail for jar URI, or unchanged origin otherwise
     */
    private static URI normalizeOrigin(URI origin) {
        if (origin.getScheme().equals("jar")) {
            String s = origin.toString();
            int pos = s.lastIndexOf('!');
            if (pos != -1) {
                s = s.substring(0, pos);
                try {
                    return new URI(s);
                } catch (URISyntaxException e) {
                    return origin;
                }
            } else {
                return origin;
            }
        } else {
            return origin;
        }
    }


}
