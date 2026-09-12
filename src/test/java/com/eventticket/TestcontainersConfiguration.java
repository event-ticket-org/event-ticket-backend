package com.eventticket;

import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.mongodb.MongoDBContainer;
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

	/**
	 * A replica set of one, which is not a preference and not a production shape - it is the
	 * only way to get transactions at all. A standalone {@code mongod} refuses them outright,
	 * because MongoDB implements them on top of the oplog and a standalone has none.
	 *
	 * <p><strong>{@code withReplicaSet()} is not optional and is easy to miss.</strong>
	 * Testcontainers 1.x initiated a replica set by default; 2.x made it opt-in, so without
	 * this call the container is a standalone {@code mongod} and every transaction fails with
	 * {@code IllegalOperation: Transaction numbers are only allowed on a replica set member}.
	 *
	 * <p>The way it fails is the part worth remembering. The application context started
	 * cleanly and the smoke test passed - the failures were logged and swallowed, because
	 * nothing in this codebase asks whether the datastore can do transactions until something
	 * tries one. The equivalent Postgres container needed no such thing: a single Postgres has
	 * been able to begin a transaction since before replication existed.
	 */
	@Bean
	@ServiceConnection
	public MongoDBContainer mongoContainer() {
		return new MongoDBContainer(DockerImageName.parse("mongo:8.0")).withReplicaSet();
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
			// The address the browser is handed, spelled differently from the one the
			// application uses, so that every upload here goes through the branch a
			// deployment depends on. Containerising the backend is what separates the two -
			// `endpoint` becomes a name only the container network resolves - and the same
			// signature has to remain valid at the other address, because a POST policy signs
			// the policy document and not the host.
			//
			// A rewritten spelling of the same container rather than an invented address:
			// the upload has to actually arrive. Where Docker is remote and the host is not
			// localhost this is a no-op, and the test proves less rather than failing.
			registry.add("app.storage.upload-base-url",
					() -> minio.getS3URL().replace("//localhost", "//127.0.0.1"));
			// Small, so that "too large" can be tested by uploading sixty-four kilobytes
			// rather than by pushing six megabytes through a container to prove a number in a
			// policy. The ceiling under test is the mechanism, not the value in nfr.md.
			registry.add("app.storage.max-bytes", () -> 65536);
		};
	}

}
