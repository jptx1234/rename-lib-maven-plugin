package io.github.jptx1234;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Maven插件，用于重命名Spring Boot可执行JAR包中的库文件。
 * 该插件会将BOOT-INF/lib/目录下的所有JAR文件重命名为哈希值，
 * 并在JAR包中添加一个mapping.log文件记录原始文件名与新文件名的映射关系。
 */
@Mojo(name = "rename-libraries", defaultPhase = LifecyclePhase.PACKAGE)
public class RenameLibrariesMojo extends AbstractMojo {

    /**
     * 当前Maven项目
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * 是否跳过执行
     */
    @Parameter(property = "rename-libraries.skip", defaultValue = "false")
    private boolean skip;

    /**
     * 是否保留原始JAR文件
     */
    @Parameter(property = "rename-libraries.keepOriginal", defaultValue = "true")
    private boolean keepOriginal;

    /**
     * 重命名后的文件扩展名
     */
    @Parameter(property = "rename-libraries.extension", defaultValue = ".lib")
    private String extension;

    /**
     * 映射文件的路径
     */
    @Parameter(property = "rename-libraries.mappingFile", defaultValue = "BOOT-INF/mapping.log")
    private String mappingFile;

    /**
     * 跳过重命名的正则表达式列表
     * 如果jar文件名匹配任一正则表达式，则跳过重命名
     */
    @Parameter(property = "rename-libraries.skipPatterns")
    private List<String> skipPatterns;

    /**
     * 编译后的正则表达式模式缓存
     */
    private List<Pattern> compiledPatterns;

    /**
     * 执行插件的主方法
     */
    @Override
    public void execute() {
        if (this.skip) {
            getLog().info("Skipping rename-libraries execution");
            return;
        }

        try {
            // 初始化编译后的正则表达式模式
            initializeCompiledPatterns();

            File jarFile = getJarFile();
            if (!jarFile.exists()) {
                getLog().error("JAR file not found: " + jarFile.getAbsolutePath());
                return;
            }

            getLog().info("Processing JAR file: " + jarFile.getAbsolutePath());
            getLog().info("Configuration: keepOriginal=" + this.keepOriginal + ", extension=" + this.extension);
            if (this.skipPatterns != null && !this.skipPatterns.isEmpty()) {
                getLog().info("Skip patterns: " + this.skipPatterns);
            }

            long startTime = System.currentTimeMillis();
            int renamedCount = processJarFile(jarFile);
            long endTime = System.currentTimeMillis();

            getLog().info("Successfully renamed " + renamedCount + " libraries in " + (endTime - startTime) + "ms");
        } catch (Exception e) {
            getLog().error("Error processing JAR file: " + e.getMessage(), e);
        }
    }

    /**
     * 获取要处理的JAR文件
     *
     * @return JAR文件对象
     */
    private File getJarFile() {
        if (this.project.getBuild() == null) {
            throw new IllegalStateException("Project build information is not available");
        }

        String finalName = this.project.getBuild().getFinalName();
        String outputDirectory = this.project.getBuild().getDirectory();

        if (finalName == null || outputDirectory == null) {
            throw new IllegalStateException("Project build finalName or directory is not available");
        }

        return new File(outputDirectory, finalName + ".jar");
    }

    /**
     * 处理JAR文件，重命名其中的库文件
     *
     * @param originalJarFile 原始JAR文件
     * @return 重命名的库文件数量
     * @throws IOException              如果发生I/O错误
     * @throws NoSuchAlgorithmException 如果找不到MD5算法
     */
    private int processJarFile(File originalJarFile) throws IOException, NoSuchAlgorithmException {
        // 创建临时文件，使用原始文件名加.tmp后缀
        File tempJarFile = new File(originalJarFile.getParentFile(), originalJarFile.getName() + ".tmp");

        Map<String, String> fileMapping = new HashMap<>();

        // 检查并保存可能存在的启动脚本（非ZIP格式内容）
        byte[] executableScript = extractExecutableScript(originalJarFile);

        try (JarFile jar = new JarFile(originalJarFile);
             FileOutputStream fos = new FileOutputStream(tempJarFile);
             JarOutputStream jos = new JarOutputStream(fos)) {

            // 如果存在启动脚本，先写入到新文件
            if (executableScript != null && executableScript.length > 0) {
                fos.write(executableScript);
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (entryName.startsWith("BOOT-INF/lib/") && entryName.endsWith(".jar")) {
                    // 处理库文件
                    String originalFileName = new File(entryName).getName();

                    // 检查是否应该跳过重命名
                    if (shouldSkipRename(originalFileName)) {
                        getLog().info("Skipping rename for: " + originalFileName + " (matches skip pattern)");
                        // 记录映射关系（原文件名映射到自己）
                        fileMapping.put(originalFileName, originalFileName);
                        // 直接复制原文件，不重命名
                        copyZipEntry(entry, jar, jos, entryName);
                    } else {
                        String newFileName = generateNewFileName(originalFileName);
                        String newEntryName = "BOOT-INF/lib/" + newFileName;

                        // 记录映射关系
                        fileMapping.put(originalFileName, newFileName);

                        // 创建新条目，设置为STORED模式（不压缩）
                        copyZipEntry(entry, jar, jos, newEntryName);

                        getLog().info("Renamed: " + originalFileName + " -> " + newFileName);
                    }
                } else if (entryName.equals("BOOT-INF/classpath.idx")) {
                    // 跳过原始的classpath.idx文件，稍后重新生成
                    getLog().info("Skipping original classpath.idx file, will regenerate after processing all libraries");
                } else {
                    // 复制其他文件
                    copyZipEntry(entry, jar, jos, entryName);
                }
            }

            // 在内存中构建映射文件内容并添加到JAR文件
            if (!fileMapping.isEmpty()) {
                StringBuilder mappingContent = new StringBuilder();
                for (Map.Entry<String, String> entry : fileMapping.entrySet()) {
                    mappingContent.append(entry.getKey())
                            .append(" --> ")
                            .append(entry.getValue())
                            .append("\n");
                }

                if (this.mappingFile != null && !this.mappingFile.isEmpty()) {
                    ZipEntry mappingEntry = new ZipEntry(this.mappingFile);
                    jos.putNextEntry(mappingEntry);
                    jos.write(mappingContent.toString().getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();

                    getLog().info("Added mapping file: " + this.mappingFile);
                }

                // 生成新的classpath.idx文件
                generateClasspathIdx(jos, fileMapping);
            }
        }

        // 处理原始文件
        if (this.keepOriginal) {
            // 将原始文件重命名为.norename后缀
            File noRenameFile = new File(originalJarFile.getParentFile(), originalJarFile.getName() + ".norename");
            getLog().debug("Keeping original JAR as: " + noRenameFile.getAbsolutePath());
            Files.move(originalJarFile.toPath(), noRenameFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            // 删除原始文件
            getLog().debug("Deleting original JAR file");
            Files.delete(originalJarFile.toPath());
        }

        // 将临时文件重命名为原始文件名
        getLog().debug("Renaming temporary file to original name");
        Files.move(tempJarFile.toPath(), originalJarFile.toPath());

        getLog().info("Successfully processed JAR file: " + originalJarFile.getAbsolutePath());
        return fileMapping.size();
    }


    /**
     * 生成新的classpath.idx文件
     *
     * @param jos         JAR输出流
     * @param fileMapping 文件名映射关系
     * @throws IOException 如果发生I/O错误
     */
    private void generateClasspathIdx(JarOutputStream jos, Map<String, String> fileMapping) throws IOException {
        StringBuilder content = new StringBuilder();

        // 根据文件映射关系生成classpath.idx内容
        for (Map.Entry<String, String> mapping : fileMapping.entrySet()) {
            String newFileName = mapping.getValue();
            String newPath = "BOOT-INF/lib/" + newFileName;
            content.append("- \"").append(newPath).append("\"\n");
        }

        // 写入新的classpath.idx文件
        ZipEntry classpathEntry = new ZipEntry("BOOT-INF/classpath.idx");
        classpathEntry.setTime(System.currentTimeMillis());
        jos.putNextEntry(classpathEntry);
        jos.write(content.toString().getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();

        getLog().info("Generated new classpath.idx file with " + fileMapping.size() + " library references");
        getLog().debug("Generated classpath.idx content:\n" + content);
    }

    /**
     * 复制ZIP条目到输出JAR文件
     *
     * @param fromEntry    源JAR条目
     * @param jar          源JAR文件
     * @param jos          JAR输出流
     * @param newEntryName 新条目名称
     * @throws IOException 如果发生I/O错误
     */
    private void copyZipEntry(JarEntry fromEntry, JarFile jar, JarOutputStream jos, String newEntryName) throws IOException {
        ZipEntry newEntry = new ZipEntry(newEntryName);
        if (fromEntry.getMethod() == ZipEntry.STORED) {
            newEntry.setMethod(ZipEntry.STORED);
            newEntry.setSize(fromEntry.getSize());
            newEntry.setCrc(fromEntry.getCrc());
        }
        newEntry.setTime(fromEntry.getTime());
        jos.putNextEntry(newEntry);
        try (InputStream is = jar.getInputStream(fromEntry)) {
            copyStream(is, jos);
        }
        jos.closeEntry();
    }


    /**
     * 初始化编译后的正则表达式模式
     */
    private void initializeCompiledPatterns() {
        if (this.skipPatterns == null || this.skipPatterns.isEmpty()) {
            this.compiledPatterns = null;
            return;
        }

        this.compiledPatterns = new java.util.ArrayList<>();
        for (String patternStr : this.skipPatterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr);
                this.compiledPatterns.add(pattern);
                getLog().debug("Compiled skip pattern: " + patternStr);
            } catch (Exception e) {
                getLog().warn("Invalid regex pattern: " + patternStr + ", error: " + e.getMessage());
            }
        }
    }

    /**
     * 检查是否应该跳过重命名
     *
     * @param fileName 文件名
     * @return 如果应该跳过重命名则返回true，否则返回false
     */
    private boolean shouldSkipRename(String fileName) {
        if (this.compiledPatterns == null || this.compiledPatterns.isEmpty()) {
            return false;
        }

        for (Pattern pattern : this.compiledPatterns) {
            if (pattern.matcher(fileName).matches()) {
                getLog().debug("File '" + fileName + "' matches skip pattern: " + pattern.pattern());
                return true;
            }
        }

        return false;
    }

    /**
     * 生成新的文件名
     *
     * @param originalFileName 原始文件名
     * @return 生成的新文件名
     * @throws NoSuchAlgorithmException 如果找不到MD5算法
     */
    private String generateNewFileName(String originalFileName) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(originalFileName.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, digest.length); i++) {
            sb.append(String.format("%02x", digest[i] & 0xff));
        }
        sb.append(this.extension);

        return sb.toString();
    }

    /**
     * 复制输入流到输出流
     *
     * @param is  输入流
     * @param jos JAR输出流
     * @throws IOException 如果发生I/O错误
     */
    private void copyStream(InputStream is, JarOutputStream jos) throws IOException {
        byte[] buffer = new byte[16384]; // 增加缓冲区大小到16KB以提高性能
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            jos.write(buffer, 0, bytesRead);
        }
    }

    /**
     * 提取JAR文件开头的可执行脚本（如果存在）
     *
     * @param jarFile JAR文件
     * @return 可执行脚本的字节数组，如果不存在则返回null
     * @throws IOException 如果发生I/O错误
     */
    private byte[] extractExecutableScript(File jarFile) throws IOException {
        getLog().debug("Searching for executable script in JAR file");
        try (FileInputStream fis = new FileInputStream(jarFile)) {
            // 使用缓冲区读取文件，直到找到ZIP文件头标记或读取到文件结尾
            byte[] buffer = new byte[16384]; // 使用16KB的缓冲区提高性能
            int bytesRead;
            ByteArrayOutputStream scriptContent = new ByteArrayOutputStream();
            int zipHeaderIndex = -1;

            // 循环读取文件内容
            while ((bytesRead = fis.read(buffer)) != -1) {
                // 在当前缓冲区中查找ZIP文件头标记（PK\003\004）
                for (int i = 0; i < bytesRead - 3; i++) {
                    if (buffer[i] == 0x50 && buffer[i + 1] == 0x4B &&
                            buffer[i + 2] == 0x03 && buffer[i + 3] == 0x04) {
                        zipHeaderIndex = i;
                        break;
                    }
                }

                if (zipHeaderIndex != -1) {
                    // 找到ZIP头，将当前缓冲区中ZIP头之前的内容添加到脚本内容中
                    scriptContent.write(buffer, 0, zipHeaderIndex);
                    break;
                } else {
                    // 未找到ZIP头，将整个缓冲区添加到脚本内容中
                    scriptContent.write(buffer, 0, bytesRead);
                }
            }

            // 如果读取到文件结尾仍未找到ZIP头，抛出异常
            if (zipHeaderIndex == -1) {
                throw new IOException("Invalid JAR file: ZIP header not found");
            }

            // 如果找到ZIP头并且不在文件开头，说明前面有启动脚本
            if (scriptContent.size() > 0) {
                byte[] script = scriptContent.toByteArray();
                getLog().info("Found executable script of " + script.length + " bytes at the beginning of JAR");
                return script;
            }
        }

        return null;
    }
}