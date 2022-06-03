package apichanges;

import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiChangesReporter {
    private static final Pattern XML_VERSION_LINK_PATTERN = Pattern.compile("<version>(.*)</version>");
    private static final Pattern SOURCES_LINK_PATTERN = Pattern.compile("<a href=\"(.*-sources\\.jar)\"\\s");
    private static final String MAVEN = "https://repo1.maven.org/maven2/";

    //Configuration
    private static final Map<String, String> FRAMEWORKS = new LinkedHashMap<>();
    static {
        FRAMEWORKS.put("junit/junit/", "JUnit 4");
        FRAMEWORKS.put("org/junit/jupiter/junit-jupiter/", "JUnit 5");
        FRAMEWORKS.put("org/testng/testng/", "TestNG");
    }
    private static final List<String> TARGET_API_CLASSES = Arrays.asList("Assert.java", "Assertions.java", "ArrayAsserts.java");

    //Model
    private static final Map<String, Map<String, Map<String, List<String>>>> APIS = new LinkedHashMap<>();


    public static void main(String[] args) throws IOException {
        File tmpDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "ApiChangesReporter");
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs())
            throw new IOException("Cannot create directory " + tmpDir.getAbsolutePath());

        for (Map.Entry<String, String> entry : FRAMEWORKS.entrySet()) {
            String frameWorkName = entry.getValue();
            String base = entry.getKey();

            List<String> versions = getVersions(base);
            for (String version : versions) {
                String versionPage = getText(new URL(MAVEN + base + version));
                Matcher matcher = SOURCES_LINK_PATTERN.matcher(versionPage);
                if (matcher.find()) {
                    String sourceFileName = matcher.group(1);
                    File jarFile = new File(tmpDir, sourceFileName);
                    if (!jarFile.isFile())
                        downloadJarFile(frameWorkName, base, version, sourceFileName, jarFile);
                    try (
                            FileInputStream fis = new FileInputStream(jarFile);
                            JarInputStream jarInputStream = new JarInputStream(fis)
                    ) {
                        for (JarEntry jarEntry = jarInputStream.getNextJarEntry(); jarEntry != null; jarEntry = jarInputStream.getNextJarEntry()) {
                            String className = jarEntry.getName();
                            int i = className.lastIndexOf("/");
                            if (i != -1 && i < className.length() - 1) className = className.substring(i + 1);
                            if (TARGET_API_CLASSES.contains(className)) {
                                collectAPI(frameWorkName, version, className, new String(getBytes(jarInputStream)));
                            }
                        }
                    }
                }
            }

            reportApiChanges(frameWorkName, versions);
        }
    }

    private static void reportApiChanges(String frameWorkName, List<String> versions) {
        System.out.println("API changes for " + frameWorkName + ":");

        boolean changesFound = false;
        String previousVersion = null;
        for (String version : versions) {
            if (previousVersion != null) {
                boolean changesWithPrevVersionFound = false;
                Map<String, List<String>> classToApiLines1 = APIS.computeIfAbsent(frameWorkName, k -> new LinkedHashMap<>()).get(previousVersion);
                Map<String, List<String>> classToApiLines2 = APIS.computeIfAbsent(frameWorkName, k2 -> new LinkedHashMap<>()).get(version);
                //For some versions there is no sources JAR at all
                if (classToApiLines1 != null && classToApiLines2 != null) {
                    for (Map.Entry<String, List<String>> e : classToApiLines2.entrySet()) {
                        String className = e.getKey();
                        List<String> prevMethodsList = classToApiLines1.get(className);
                        if (prevMethodsList != null) {
                            for (String method : e.getValue()) {
                                if (!prevMethodsList.contains(method)) {
                                    System.out.println(previousVersion + " to " + version + ", " + className + ": added " + method);
                                    changesWithPrevVersionFound = true;
                                }
                            }
                            for (String prevMethodSignature : prevMethodsList) {
                                if (!e.getValue().contains(prevMethodSignature)) {
                                    System.out.println(previousVersion + " to " + version + ", " + className + ": removed " + prevMethodSignature);
                                    changesWithPrevVersionFound = true;
                                }
                            }
                        } else {
                            System.out.println(previousVersion + " to " + version + ", added class " + className);
                            changesWithPrevVersionFound = true;
                        }
                    }
                }
                changesFound |= changesWithPrevVersionFound;
                if (changesWithPrevVersionFound) System.out.println();
            }
            previousVersion = version;
        }
        if (!changesFound) System.out.println("No changes found\n");
    }

    private static void downloadJarFile(String frameWorkName, String base, String version, String sourceFileName, File target) throws IOException {
        System.out.print(frameWorkName + " version " + version + " absent in local repository, downloading started...");
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(getBytes(new URL(MAVEN + base + version + "/" + sourceFileName)));
            fos.flush();
            System.out.println("DONE");
        }
    }

    private static void collectAPI(String frameWorkName, String version, String className, String source) {
        String[] lines = source.split("\n");
        StringBuilder lineToJoin = null;
        for (String line : lines) {
            if (isAPI(line) || lineToJoin != null) {
                line = line.trim();
                if (line.contains("{") || line.contains("interface")) {
                    line = line.substring(0, line.length() - 1).trim();
                    if (lineToJoin != null) {
                        line = lineToJoin + " " + line;
                        lineToJoin = null;
                    }
                } else {
                    if (lineToJoin != null) lineToJoin.append(" ").append(line);
                    else lineToJoin = new StringBuilder(line);
                    continue;
                }
                line = line.replace("static public", "public static").replaceAll("\\(\\s+", "(").trim();
                APIS.computeIfAbsent(frameWorkName, k2 -> new LinkedHashMap<>())
                        .computeIfAbsent(version, k1 -> new LinkedHashMap<>())
                        .computeIfAbsent(className, k -> new ArrayList<>()).add(line);
            }
        }
    }

    private static boolean isAPI(String line) {
        boolean lineIsInteresting = line.contains("void") || line.contains("interface");
        lineIsInteresting &= line.contains("public");
        lineIsInteresting &= !line.contains("*");
        return lineIsInteresting;
    }

    private static List<String> getVersions(String base) throws IOException {
        List<String> versionSet = new ArrayList<>();
        String basePage = getText(new URL(MAVEN + base + "maven-metadata.xml"));
        Matcher matcher = XML_VERSION_LINK_PATTERN.matcher(basePage);
        while (matcher.find()) versionSet.add(matcher.group(1));
        return versionSet;
    }

    private static byte[] getBytes(URL url) throws IOException {
        try (InputStream is = url.openConnection().getInputStream()) {
            return getBytes(is);
        }
    }

    private static byte[] getBytes(InputStream is) throws IOException {
        byte[] buff = new byte[16384];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int len = is.read(buff); len > 0; len = is.read(buff)) baos.write(buff, 0, len);
        return baos.toByteArray();
    }


    private static String getText(URL url) throws IOException {
        return new String(getBytes(url));
    }
}
