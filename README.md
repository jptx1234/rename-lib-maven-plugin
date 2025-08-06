# Rename Lib Maven Plugin

这个Maven插件用于重命名Spring Boot可执行JAR包中的依赖库文件。该插件会将`BOOT-INF/lib`
目录下的所有JAR文件重命名为基于MD5哈希的短文件名，并在JAR包中添加一个映射文件记录原始文件名与新文件名的对应关系。

## 功能特点

- 在Maven的package阶段执行
- 自动重命名`BOOT-INF/lib`目录中的所有JAR文件为哈希值
- 生成映射文件记录文件名变更关系
- 支持正则表达式配置跳过特定文件的重命名
- 兼容Spring Boot 3.x和JDK 21
- 保持Spring Boot可执行JAR的启动脚本完整性

## 使用方法

### 步骤1：添加插件到你的Spring Boot项目

在你的Spring Boot项目的`pom.xml`文件中添加以下配置：

```xml

<build>
    <plugins>
        <!-- Spring Boot Maven Plugin -->
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>

        <!-- Rename Lib Maven Plugin -->
        <plugin>
            <groupId>com.github.jptx1234</groupId>
            <artifactId>rename-lib-maven-plugin</artifactId>
            <version>1.0.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>rename-libraries</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 步骤2：构建你的项目

使用Maven构建你的项目：

```bash
mvn clean package
```

插件将在package阶段自动执行，重命名`BOOT-INF/lib`目录中的JAR文件。

### 步骤3：运行你的应用

正常运行你的Spring Boot应用：

```bash
java -jar target/your-application.jar
```

应用将正常启动和运行，依赖库文件已被重命名但功能不受影响。

## 配置选项

插件提供以下配置选项：

| 参数             | 描述                         | 默认值                    |
|----------------|----------------------------|------------------------|
| `skip`         | 是否跳过执行                     | `false`                |
| `keepOriginal` | 是否保留原始JAR文件（添加.norename后缀） | `true`                 |
| `extension`    | 重命名后的文件扩展名                 | `.lib`                 |
| `mappingFile`  | 映射文件在JAR中的路径               | `BOOT-INF/mapping.log` |
| `skipPatterns` | 跳过重命名的正则表达式列表              | 无                      |

### 配置示例：

```xml

<plugin>
    <groupId>com.github.jptx1234</groupId>
    <artifactId>rename-lib-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <skip>false</skip>
        <keepOriginal>true</keepOriginal>
        <extension>.renamed</extension>
        <mappingFile>BOOT-INF/lib-mapping.log</mappingFile>
        <skipPatterns>
            <skipPattern>spring-boot-.*\.jar</skipPattern>
            <skipPattern>important-lib-.*\.jar</skipPattern>
        </skipPatterns>
    </configuration>
</plugin>
```

## 工作原理

1. 插件在Maven的package阶段执行，处理Spring Boot Maven Plugin生成的可执行JAR
2. 遍历JAR中的所有条目，对`BOOT-INF/lib`目录中的JAR文件进行重命名
3. 使用MD5哈希算法生成新的文件名（取前6位十六进制字符）
4. 在JAR中添加映射文件记录原始文件名与新文件名的对应关系
5. 保持Spring Boot可执行JAR的启动脚本和其他文件完整性

## 系统要求

- JDK 21或更高版本
- Spring Boot 3.x
- Maven 3.6.0或更高版本