package com.eventticket.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	static final String BUCKET = "event-ticket-covers";

	static final String ACCESS_KEY = "eventticket";

	static final String SECRET_KEY = "eventticket";

	private static final int S3_PORT = 8333;

	/**
	 * SeaweedFS refuses every request until an identity exists, so the credentials arrive as a
	 * file rather than as environment variables.
	 *
	 * <p>Spelled exactly as {@code docker/storage/s3-identities.json} spells it, because a test
	 * store configured more permissively than the real one cannot fail for the reason the real one
	 * would. The anonymous identity is what serves a cover to a public page, scoped to the one
	 * bucket and granted Read and never List: the keys carry organization and event ids, so a
	 * listable bucket publishes which organizations exist and how many events each has.
	 */
	private static final String IDENTITIES = """
			{"identities":[
			  {"name":"app","credentials":[{"accessKey":"%s","secretKey":"%s"}],
			   "actions":["Admin","Read","Write","List","Tagging"]},
			  {"name":"anonymous","actions":["Read:%s"]}
			]}""".formatted(ACCESS_KEY, SECRET_KEY, BUCKET);

	@Bean
	@ServiceConnection
	public PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"));
	}

	/**
	 * A real Elasticsearch, for the whole suite.
	 *
	 * <p>No in-memory fake behind the port. The premise the search work rests on is that
	 * {@code asciifolding} reproduces what Postgres's {@code unaccent} does - so that "ha noi"
	 * finds "Hà Nội" - and that is a claim about an analyzer, which only an analyzer can answer.
	 * A fake would let it regress in silence, and the same is true of every mapping decision:
	 * a keyword field that should have been text is a query returning nothing, and a stand-in
	 * has no opinion about either.
	 *
	 * <p>Security off and a single node, because this is a test cluster on a laptop and in CI.
	 * A small heap for the same reason - the default sizes itself from the host's RAM, which on
	 * a CI runner is most of the runner.
	 */
	static final String SEARCH_PASSWORD = "eventticket";

	@Bean
	public ElasticsearchContainer searchContainer() {
		return new ElasticsearchContainer(
				DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:9.4.5"))
				.withEnv("discovery.type", "single-node")
				// Security on, exactly as the deployment runs it, so the authenticated path is
				// the one the suite exercises. A test cluster that accepted anything would pass
				// whether or not the client ever sent a credential - the same shape as a store
				// that accepts every write, which this file already refuses to be.
				.withEnv("xpack.security.enabled", "true")
				// ...and TLS off, also as the deployment runs it: the cluster is reachable on
				// the compose network and loopback only, so authentication is what separates
				// callers and transport encryption between containers on one host buys nothing.
				.withEnv("xpack.security.http.ssl.enabled", "false")
				.withEnv("ELASTIC_PASSWORD", SEARCH_PASSWORD)
				.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
	}

	/**
	 * Points the application at it. {@code app.search.uri} present is what turns the indexer on
	 * - absent means there is no cluster, which is how a deployment without one runs.
	 */
	@Bean
	public DynamicPropertyRegistrar searchProperties(ElasticsearchContainer search) {
		return registry -> {
			registry.add("app.search.uri",
					() -> "http://" + search.getHttpHostAddress().replaceFirst("^https?://", ""));
			registry.add("app.search.username", () -> "elastic");
			registry.add("app.search.password", () -> SEARCH_PASSWORD);
		};
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
	public ObjectStoreContainer objectStoreContainer() {
		ObjectStoreContainer store = new ObjectStoreContainer()
				.withCopyToContainer(Transferable.of(IDENTITIES), "/etc/seaweedfs/s3.json")
				.withCommand("server", "-dir=/data", "-s3", "-s3.port=" + S3_PORT,
						"-s3.config=/etc/seaweedfs/s3.json")
				.withExposedPorts(S3_PORT)
				// Liveness only, deliberately: the gateway answers `/` with 200 or 403 depending
				// on what the anonymous identity may list, and which one it is says nothing about
				// whether the server is up. Whether the credentials are real is a separate
				// question, asked below rather than inferred from a status code. The timeout is
				// generous because a cold start here runs a master, a volume server, a filer and
				// the gateway, and has been seen to take over twenty seconds.
				.waitingFor(Wait.forHttp("/")
						.forStatusCodeMatching(code -> code == 200 || code == 403)
						.forPort(S3_PORT)
						.withStartupTimeout(Duration.ofMinutes(3)));
		store.start();
		try (S3Client s3 = S3Client.builder()
				.endpointOverride(URI.create(s3Url(store)))
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
				.forcePathStyle(true)
				.build()) {
			s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
		}
		// After the bucket exists, never before: a write to a bucket that is not there is refused
		// for the wrong reason, and the check passes while proving nothing. Found by planting it.
		refuseIfAnonymousCanWrite(store);
		return store;
	}

	/**
	 * Fails the whole suite if the identity file did not load.
	 *
	 * <p>Without it the store accepts everything from everybody, and the fifteen tests that exist
	 * to prove a signature is verified all pass while proving nothing - the same shape as a
	 * tenancy test run through a tenant-filtered query. The store is only evidence while it is
	 * still refusing somebody.
	 */
	private static void refuseIfAnonymousCanWrite(ObjectStoreContainer store) {
		HttpRequest unsigned = HttpRequest.newBuilder(URI.create(s3Url(store) + "/" + BUCKET + "/anonymous-probe"))
				.PUT(HttpRequest.BodyPublishers.ofString("x"))
				.build();
		int status;
		try (HttpClient client = HttpClient.newHttpClient()) {
			status = client.send(unsigned, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not ask the object store whether it is enforcing credentials", ex);
		}
		if (status < 400) {
			throw new IllegalStateException(
					"the object store accepted an unsigned write (HTTP " + status + "), so its identity "
							+ "configuration did not load and no upload test here proves anything");
		}
	}

	/**
	 * A named type, not a bare {@code GenericContainer<?>}, because {@code PostgreSQLContainer}
	 * is one too - so two raw container beans are one ambiguous type and nothing can be injected
	 * by it. {@code MinIOContainer} gave the distinction away for free; a generic container has
	 * to be told.
	 */
	static final class ObjectStoreContainer extends GenericContainer<ObjectStoreContainer> {

		ObjectStoreContainer() {
			super(DockerImageName.parse("chrislusf/seaweedfs:4.46"));
		}

	}

	private static String s3Url(GenericContainer<?> store) {
		return "http://" + store.getHost() + ":" + store.getMappedPort(S3_PORT);
	}

	/**
	 * No {@code @ServiceConnection} for object storage, so the addresses are published by hand.
	 * The public base URL is the container's too: nothing in these tests fetches a cover, and a
	 * URL that is real is a better assertion than one that is invented.
	 */
	@Bean
	public DynamicPropertyRegistrar storageProperties(ObjectStoreContainer store) {
		return registry -> {
			registry.add("app.storage.endpoint", () -> s3Url(store));
			registry.add("app.storage.bucket", () -> BUCKET);
			registry.add("app.storage.access-key", () -> ACCESS_KEY);
			registry.add("app.storage.secret-key", () -> SECRET_KEY);
			registry.add("app.storage.public-base-url", () -> s3Url(store) + "/" + BUCKET);
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
					() -> s3Url(store).replace("//localhost", "//127.0.0.1"));
			// Small, so that "too large" can be tested by uploading sixty-four kilobytes
			// rather than by pushing six megabytes through a container to prove a number in a
			// policy. The ceiling under test is the mechanism, not the value in nfr.md.
			registry.add("app.storage.max-bytes", () -> 65536);
		};
	}

}
