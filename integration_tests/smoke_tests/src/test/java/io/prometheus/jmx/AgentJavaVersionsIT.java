package io.prometheus.jmx;

import com.github.dockerjava.api.model.Ulimit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs the JmxExampleApplication on different Java Docker images with the jmx_exporter agent attached,
 * and checks if a couple of example metrics are exposed.
 * <p/>
 * Run with
 * <pre>mvn verify</pre>
 */
@RunWith(Parameterized.class)
public class AgentJavaVersionsIT {

  private final String agentModule;
  private final GenericContainer<?> javaContainer;
  private final Volume volume;
  private final Scraper scraper;

  @Parameterized.Parameters(name="{0}")
  public static String[][] images() {
    return new String[][] {

        // HotSpot
        { "openjdk:8-jre", "jmx_prometheus_javaagent" },
        { "openjdk:8-jre","jmx_prometheus_javaagent_java6" },

        { "openjdk:11-jre", "jmx_prometheus_javaagent_java6" },
        { "openjdk:11-jre", "jmx_prometheus_javaagent" },

        { "openjdk:17-oracle", "jmx_prometheus_javaagent_java6" },
        { "openjdk:17-oracle", "jmx_prometheus_javaagent" },

        { "ticketfly/java:6",  "jmx_prometheus_javaagent_java6" },

        { "openjdk:7", "jmx_prometheus_javaagent_java6" },
        { "openjdk:7", "jmx_prometheus_javaagent" },

        // OpenJ9
        { "ibmjava:8-jre", "jmx_prometheus_javaagent_java6" },
        { "ibmjava:8-jre", "jmx_prometheus_javaagent" },

        { "ibmjava:11", "jmx_prometheus_javaagent_java6" },
        { "ibmjava:11", "jmx_prometheus_javaagent" },

        { "adoptopenjdk/openjdk11-openj9", "jmx_prometheus_javaagent_java6" },
        { "adoptopenjdk/openjdk11-openj9", "jmx_prometheus_javaagent" },
    };
  }

  public AgentJavaVersionsIT(String baseImage, String agentModule) throws IOException, URISyntaxException {
    this.agentModule = agentModule;
    volume = Volume.create("agent-integration-test-");
    volume.copyAgentJar(agentModule);
    volume.copyConfigYaml("config.yml");
    volume.copyExampleApplication();
    String cmd = "java -javaagent:agent.jar=9000:config.yaml -jar jmx_example_application.jar";
    javaContainer = new GenericContainer<>(baseImage)
            .withFileSystemBind(volume.getHostPath(), "/app", BindMode.READ_ONLY)
            // The firefly/java:6 container needs an increased number of file descriptors, so set the nofile ulimit here.
            .withCreateContainerCmdModifier(c -> c.getHostConfig().withUlimits(new Ulimit[]{new Ulimit("nofile", 65536L, 65536L)}))
            .withWorkingDirectory("/app")
            .withExposedPorts(9000)
            .withCommand(cmd)
            .waitingFor(Wait.forLogMessage(".*registered.*", 1))
            .withLogConsumer(frame -> System.out.print(frame.getUtf8String()));
    javaContainer.start();
    scraper = new Scraper(javaContainer.getHost(), javaContainer.getMappedPort(9000));
  }

  @After
  public void tearDown() throws IOException {
    javaContainer.stop();
    volume.close();
  }

  @Test
  public void testJvmMetric() throws Exception {
    String metricName = "java_lang_Memory_NonHeapMemoryUsage_committed";
    String metric = scraper.scrape(10 * 1000).stream()
        .filter(line -> line.startsWith(metricName))
        .findAny()
        .orElseThrow(() -> new AssertionError("Metric " + metricName + " not found."));
    double value = Double.parseDouble(metric.split(" ")[1]);
    Assert.assertTrue(metricName + " should be > 0", value > 0);
  }

  @Test
  public void testTabularMetric() throws Exception {
    String[] expectedMetrics = new String[] {
        "io_prometheus_jmx_tabularData_Server_1_Disk_Usage_Table_size{source=\"/dev/sda1\"} 7.516192768E9",
        "io_prometheus_jmx_tabularData_Server_1_Disk_Usage_Table_size{source=\"/dev/sda2\"} 1.5032385536E10",
        "io_prometheus_jmx_tabularData_Server_2_Disk_Usage_Table_size{source=\"/dev/sda1\"} 2.5769803776E10",
        "io_prometheus_jmx_tabularData_Server_2_Disk_Usage_Table_size{source=\"/dev/sda2\"} 1.073741824E11"
    };
    List<String> metrics = scraper.scrape(10 * 1000);
    for (String expectedMetric : expectedMetrics) {
      metrics.stream()
          .filter(line -> line.startsWith(expectedMetric))
          .findAny()
          .orElseThrow(() -> new AssertionError("Metric " + expectedMetric + " not found."));
    }
  }

  @Test
  public void testBuildInfoMetricName() {
    AtomicReference expectedName = new AtomicReference("\"jmx_prometheus_javaagent\"");
    if (agentModule.endsWith("_java6")) {
      expectedName.set("\"jmx_prometheus_javaagent_java6\"");
    }

    List<String> metrics = scraper.scrape(10 * 1000);
    metrics.stream()
            .filter(line -> line.startsWith("jmx_exporter_build_info"))
            .findAny()
            .ifPresent(line -> {
              Assert.assertTrue(line.contains((String) expectedName.get()));
            });
  }

  @Test
  public void testBuildVersionMetricNotUnknown() {
    // No easy way to test the version, so make sure it's not "unknown"
    List<String> metrics = scraper.scrape(10 * 1000);
    metrics.stream()
            .filter(line -> line.startsWith("jmx_exporter_build_info"))
            .findAny()
            .ifPresent(line -> {
              Assert.assertTrue(!line.contains("\"unknown\""));
            });
  }
}
