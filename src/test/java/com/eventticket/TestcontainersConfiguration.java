package com.eventticket;

import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String BUCKET = "event-ticket-covers";

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	/**
	 * A real object store, because the part of the upload flow most likely to be wrong is the
	 * part no unit test can reach: a signature is either exactly right or completely broken,
	 * and only a store that verifies it can tell the two apart.
	 *
	 * <p>Started here rather than left to the framework so the bucket can be made before
	 * anything asks for it. Starting twice is a no-op.
	 */
	@Bean
	public MinIOContainer minioContainer() {
		MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:latest"))
				.withUserName("eventticket")
				.withPassword("eventticket");
		minio.start();
		try (S3Client s3 = S3Client.builder()
				.endpointOverride(URI.create(minio.getS3URL()))
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
				.forcePathStyle(true)
				.build()) {
			s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
			// Readable by anyone, as the deployed bucket is: a cover is fetched by a browser on
			// a public page, and a test that could not fetch one would not be exercising what the
			// URL is for. Objects, not the bucket - public read is not a directory anybody can
			// walk. Mirrors `mc anonymous set download` in compose.yaml.
			s3.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(BUCKET)
					.policy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::%s/*\"]}]}".formatted(BUCKET)).build());
		}
		return minio;
	}

	/**
	 * No {@code @ServiceConnection} for object storage, so the addresses are published by hand.
	 * The public base URL is the container's too: nothing in these tests fetches a cover, and a
	 * URL that is real is a better assertion than one that is invented.
	 */
	@Bean
	public DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
		return registry -> {
			registry.add("app.storage.endpoint", minio::getS3URL);
			registry.add("app.storage.bucket", () -> BUCKET);
			registry.add("app.storage.access-key", minio::getUserName);
			registry.add("app.storage.secret-key", minio::getPassword);
			registry.add("app.storage.public-base-url", () -> minio.getS3URL() + "/" + BUCKET);
			// Small, so that "too large" can be tested by uploading sixty-four kilobytes
			// rather than by pushing six megabytes through a container to prove a number in a
			// policy. The ceiling under test is the mechanism, not the value in nfr.md.
			registry.add("app.storage.max-bytes", () -> 65536);
		};
	}

}
