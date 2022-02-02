<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<groupId>com.maventest</groupId>
	<artifactId>aproject</artifactId>
	<packaging>jar</packaging>
	<version>1.1</version>
	<name>aproject</name>
	<url>http://maven.apache.org</url>
	<dependencies>
		<dependency>
			<groupId>junit</groupId>
			<artifactId>junit</artifactId>
			<version>3.8.1</version>
			<scope>test</scope>
		</dependency>
	</dependencies>
	<build>
		<plugins>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-source-plugin</artifactId>
				<executions>
					<execution>
						<id>attach-sources</id>
						<goals>
							<goal>jar</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
		</plugins>
		<extensions>
			<!-- begin - needed for deploying to repository using webdav -->
			<extension>
				<groupId>org.apache.maven.wagon</groupId>
				<artifactId>wagon-webdav</artifactId>
				<version>1.0-beta-2</version>
			</extension>
			<!-- end - needed for deploying to repository using webdav -->
		</extensions>
	</build>
	<distributionManagement>
		<repository>
			<id>archiva.internal</id>
			<name>Internal Release Repository</name>
			<url>dav:http://192.168.1.7:8081/archiva/repository/internal</url>
		</repository>
		<snapshotRepository>
			<id>archiva.snapshots</id>
			<name>Internal Snapshot Repository</name>
			<url>dav:http://192.168.1.7:8081/archiva/repository/snapshots</url>
		</snapshotRepository>
	</distributionManagement>
</project>
