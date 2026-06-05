plugins {
	java
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.fightforfuture"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-batch")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Parquet reading — parquet-avro pulls in parquet-hadoop which needs Hadoop on classpath
	implementation("org.apache.parquet:parquet-avro:1.14.3")
	implementation("org.apache.hadoop:hadoop-common:3.3.6") {
		exclude(group = "org.slf4j")
		exclude(group = "log4j")
		exclude(group = "ch.qos.logback")
		exclude(group = "com.sun.jersey")
		exclude(group = "io.netty", module = "netty")
		exclude(group = "org.apache.zookeeper")
		exclude(group = "org.apache.kerby")
	}
	// parquet-hadoop references FileInputFormat from this module at class-init time
	implementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.3.6") {
		exclude(group = "org.slf4j")
		exclude(group = "log4j")
		exclude(group = "ch.qos.logback")
		exclude(group = "io.netty")
		exclude(group = "org.apache.zookeeper")
		exclude(group = "org.apache.avro")
	}
	// S3 client — uses the BOM version already declared above
	implementation("software.amazon.awssdk:s3")
	runtimeOnly("org.postgresql:postgresql")

	// AWS SDK v2
	implementation(platform("software.amazon.awssdk:bom:2.25.39"))
	implementation("software.amazon.awssdk:athena")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
