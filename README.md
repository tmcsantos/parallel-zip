# Lib for replacing default zipping implementation in maven-assembly-plugin [EXPERIMENTAL]

Uses java nio and native zip provider

##### How to use
 
Place this in you pom.xml and activate with `-Dexperimental`

```
<profile>
  <id>assembly-experimental</id>
  <activation>
    <property>
      <name>experimental</name>
    </property>
  </activation>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-assembly-plugin</artifactId>
        <dependencies>
          <dependency>
            <groupId>org.hitachivantara.utils</groupId>
            <artifactId>parallel-zip</artifactId>
            <version>1.0-SNAPSHOT</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
</profile>
```

If everything goes well you should see in your assembly logs something like this:
```
[INFO] --- maven-assembly-plugin:3.1.0:single (assembly_package) @ sample ---
[INFO] Using concurrent ZIP compression with Java NIO
[INFO] Building zip: ....
```