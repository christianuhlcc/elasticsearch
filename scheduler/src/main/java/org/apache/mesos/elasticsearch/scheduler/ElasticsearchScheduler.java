package org.apache.mesos.elasticsearch.scheduler;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.apache.mesos.elasticsearch.common.Binaries;
import org.apache.mesos.elasticsearch.common.Configuration;
import org.apache.mesos.elasticsearch.common.Resources;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Scheduler for Elasticsearch.
 */
@SuppressWarnings("PMD.TooManyMethods")
public class ElasticsearchScheduler implements Scheduler, Runnable {

    public static final Logger LOGGER = Logger.getLogger(ElasticsearchScheduler.class.toString());

    public static final String TASK_DATE_FORMAT = "yyyyMMdd'T'HHmmss.SSS'Z'";

    Clock clock = new Clock();

    Set<Task> tasks = new HashSet<>();

    private CountDownLatch initialized = new CountDownLatch(1);

    private int numberOfHwNodes;

    private String masterHost;

    private String dnsHost;

    private boolean useDocker;

    private String namenode;

    private Protos.FrameworkID frameworkId;

    public ElasticsearchScheduler(String masterHost, String dnsHost, int numberOfHwNodes, boolean useDocker, String namenode) {
        this.masterHost = masterHost;
        this.dnsHost = dnsHost;
        this.numberOfHwNodes = numberOfHwNodes;
        this.useDocker = useDocker;
        this.namenode = namenode;
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("m", "master host", true, "master host");
        options.addOption("dns", "DNS host", true, "DNS host");
        options.addOption("n", "numHardwareNodes", true, "number of hardware nodes");
        options.addOption("d", "useDocker", false, "use docker to launch Elasticsearch");
        options.addOption("nn", "namenode", true, "name node hostname + port");
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            String masterHost = cmd.getOptionValue("m");
            String dnsHost = cmd.getOptionValue("dns");
            String numberOfHwNodesString = cmd.getOptionValue("n");
            String nameNode = cmd.getOptionValue("nn");
            if (masterHost == null || numberOfHwNodesString == null || nameNode == null) {
                printUsage(options);
                return;
            }
            int numberOfHwNodes;
            try {
                numberOfHwNodes = Integer.parseInt(numberOfHwNodesString);
            } catch (IllegalArgumentException e) {
                printUsage(options);
                return;
            }

            boolean useDocker = cmd.hasOption('d');

            LOGGER.info("Starting ElasticSearch on Mesos - [master: " + masterHost + ", numHwNodes: " + numberOfHwNodes + ", docker: " + (useDocker ? "enabled" : "disabled") + ", dns: " + dnsHost + "]");

            final ElasticsearchScheduler scheduler = new ElasticsearchScheduler(masterHost, dnsHost, numberOfHwNodes, useDocker, nameNode);

            final Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo.newBuilder();
            frameworkBuilder.setUser("jclouds");
            frameworkBuilder.setName(Configuration.FRAMEWORK_NAME);
            frameworkBuilder.setCheckpoint(true);

            final MesosSchedulerDriver driver = new MesosSchedulerDriver(scheduler, frameworkBuilder.build(), masterHost + ":" + Configuration.MESOS_PORT);

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    driver.stop();
                    scheduler.onShutdown();
                }
            }));

            Thread schedThred = new Thread(scheduler);
            schedThred.start();
            scheduler.waitUntilInit();
        } catch (ParseException e) {
            printUsage(options);
        }
    }

    private void onShutdown() {
        LOGGER.info("On shutdown...");
    }

    private void waitUntilInit() {
        try {
            initialized.await();
        } catch (InterruptedException e) {
            LOGGER.error("Elasticsearch framework interrupted");
        }
    }

    private static void printUsage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(Configuration.FRAMEWORK_NAME, options);
    }

    @Override
    public void run() {
        LOGGER.info("Starting up ...");
        SchedulerDriver driver = new MesosSchedulerDriver(this, Protos.FrameworkInfo.newBuilder().setUser("").setName(Configuration.FRAMEWORK_NAME).build(), masterHost + ":" + Configuration.MESOS_PORT);
        driver.run();
    }

    @Override
    public void registered(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) {
        this.frameworkId = frameworkId;

        LOGGER.info("Framework registered as " + frameworkId.getValue());

        List<Protos.Resource> resources = buildResources();

        Protos.Request request = Protos.Request.newBuilder()
                .addAllResources(resources)
                .build();

        List<Protos.Request> requests = Collections.singletonList(request);
        driver.requestResources(requests);
    }

    private static List<Protos.Resource> buildResources() {
        Protos.Resource cpus = Resources.cpus(Configuration.CPUS);
        Protos.Resource mem = Resources.mem(Configuration.MEM);
        Protos.Resource disk = Resources.disk(Configuration.DISK);
        Protos.Resource ports = Resources.portRange(Configuration.BEGIN_PORT, Configuration.END_PORT);
        return Arrays.asList(cpus, mem, disk, ports);
    }

    @Override
    public void reregistered(SchedulerDriver driver, Protos.MasterInfo masterInfo) {
        LOGGER.info("Framework re-registered");
    }

    @Override
    public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offers) {
        for (Protos.Offer offer : offers) {
            if (!isOfferGood(offer)) {
                driver.declineOffer(offer.getId());
                LOGGER.info("Declined offer: Offer is not sufficient");
            } else if (haveEnoughNodes()) {
                driver.declineOffer(offer.getId());
                LOGGER.info("Declined offer: Node " + offer.getHostname() + " already has an Elasticsearch task");
            } else {
                LOGGER.info("Accepted offer: " + offer.getHostname());

                String id = taskId(offer);

                Protos.TaskInfo taskInfo = buildTask(driver, offer.getResourcesList(), offer, id);

                driver.launchTasks(Collections.singleton(offer.getId()), Collections.singleton(taskInfo));
                tasks.add(new Task(offer.getHostname(), id));
            }
        }

        if (haveEnoughNodes()) {
            initialized.countDown();
        }
    }

    private Protos.TaskInfo buildTask(SchedulerDriver driver, List<Protos.Resource> offeredResources, Protos.Offer offer, String id) {
        List<Protos.Resource> acceptedResources = new ArrayList<>();

        addAllScalarResources(offeredResources, acceptedResources);

        List<Integer> ports = selectPorts(offeredResources);

        if (ports.size() != 2) {
            LOGGER.info("Declined offer: Offer did not contain 2 ports for Elasticsearch client and transport connection");
            driver.declineOffer(offer.getId());
        } else {
            LOGGER.info("Elasticsearch client port " + ports.get(0));
            LOGGER.info("Elasticsearch transport port " + ports.get(1));
            acceptedResources.add(Resources.singlePortRange(ports.get(0)));
            acceptedResources.add(Resources.singlePortRange(ports.get(1)));
        }

        Protos.TaskInfo.Builder taskInfoBuilder = Protos.TaskInfo.newBuilder()
                .setName(Configuration.TASK_NAME)
                .setTaskId(Protos.TaskID.newBuilder().setValue(id))
                .setSlaveId(offer.getSlaveId())
                .addAllResources(acceptedResources);

        if (useDocker) {
            LOGGER.info("Using Docker to start Elasticsearch cloud mesos on slaves");
            Protos.ContainerInfo.Builder containerInfo = Protos.ContainerInfo.newBuilder();
            PortMapping clientPortMapping = PortMapping.newBuilder().setContainerPort(Configuration.ELASTICSEARCH_CLIENT_PORT).setHostPort(ports.get(0)).build();
            PortMapping transportPortMapping = PortMapping.newBuilder().setContainerPort(Configuration.ELASTICSEARCH_TRANSPORT_PORT).setHostPort(ports.get(1)).build();

            InetAddress masterAddress = null;
            masterAddress = resolveHost(masterAddress, masterHost);
            if (masterAddress == null) {
                LOGGER.error("Could not resolve master host : " + masterHost);
                return taskInfoBuilder.build();
            }

            InetAddress dnsAddress = null;
            dnsAddress = resolveHost(dnsAddress, dnsHost);
            if (dnsAddress == null) {
                LOGGER.error("Could not resolve DNS host: " + dnsHost);
                return taskInfoBuilder.build();
            }

            InetAddress slaveAddress = null;
            slaveAddress = resolveHost(slaveAddress, offer.getHostname());
            if (slaveAddress == null) {
                LOGGER.error("Could not resolve slave host: " + offer.getHostname());
                return taskInfoBuilder.build();
            }

            Protos.ContainerInfo.DockerInfo.Builder docker = Protos.ContainerInfo.DockerInfo.newBuilder()
                    .setNetwork(Protos.ContainerInfo.DockerInfo.Network.BRIDGE)
                    .setImage("mesos/elasticsearch-cloud-mesos")
                    .addPortMappings(clientPortMapping)
                    .addPortMappings(transportPortMapping);

            if (dnsHost != null) {
                docker.addParameters(Protos.Parameter.newBuilder().setKey("dns").setValue(dnsAddress.getHostAddress()));
            }

            containerInfo.setDocker(docker.build());
            containerInfo.setType(Protos.ContainerInfo.Type.DOCKER);
            taskInfoBuilder.setContainer(containerInfo);
            taskInfoBuilder
                    .setCommand(Protos.CommandInfo.newBuilder()
                            .addArguments("elasticsearch")
                            .addArguments("--network.publish_host").addArguments(offer.getHostname())
                            .addArguments("--node.master").addArguments("true")
                            .addArguments("--cloud.mesos.master").addArguments("http://" + masterAddress.getHostAddress() + ":" + Configuration.MESOS_PORT)
                            .addArguments("--logger.discovery").addArguments("DEBUG")
                            .addArguments("--logger.cloud.mesos").addArguments("DEBUG")
                            .addArguments("--discovery.type").addArguments("mesos")
                            .setShell(false))
                    .build();
        } else {
            LOGGER.info("Using Executor to start Elasticsearch cloud mesos on slaves");
            Protos.ExecutorInfo executorInfo = Protos.ExecutorInfo.newBuilder()
                    .setExecutorId(Protos.ExecutorID.newBuilder().setValue(UUID.randomUUID().toString()))
                    .setFrameworkId(frameworkId)
                    .setCommand(Protos.CommandInfo.newBuilder()
                            .addUris(Protos.CommandInfo.URI.newBuilder().setValue("hdfs://" + namenode + Binaries.ES_EXECUTOR_HDFS_PATH))
                            .addUris(Protos.CommandInfo.URI.newBuilder().setValue("hdfs://" + namenode + Binaries.ES_CLOUD_MESOS_HDFS_PATH))
                            .setValue("java -jar " + Binaries.ES_EXECUTOR_JAR))
                    .setName("" + UUID.randomUUID())
                    .addAllResources(acceptedResources)
                    .build();

            taskInfoBuilder.setExecutor(executorInfo);
        }

        return taskInfoBuilder.build();
    }

    private InetAddress resolveHost(InetAddress masterAddress, String host) {
        try {
            masterAddress = InetAddress.getByName(host);
            LOGGER.info("Resolving " + host + " to " + masterAddress);
        } catch (UnknownHostException e) {
            LOGGER.error("Could not resolve IP address for hostname " + host);
        }
        return masterAddress;
    }

    private List<Integer> selectPorts(List<Protos.Resource> offeredResources) {
        List<Integer> ports = new ArrayList<>();
        for (Protos.Resource resource : offeredResources) {
            if (resource.getType().equals(Protos.Value.Type.RANGES)) {
                for (Protos.Value.Range range : resource.getRanges().getRangeList()) {
                    if (ports.size() < 2) {
                        ports.add((int) range.getBegin());
                        if (ports.size() < 2 && range.getBegin() != range.getEnd()) {
                            ports.add((int) range.getBegin() + 1);
                        }
                    }
                }
            }
        }
        return ports;
    }

    private void addAllScalarResources(List<Protos.Resource> offeredResources, List<Protos.Resource> acceptedResources) {
        for (Protos.Resource resource : offeredResources) {
            if (resource.getType().equals(Protos.Value.Type.SCALAR)) {
                acceptedResources.add(resource);
            }
        }
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
        LOGGER.info("Offer " + offerId.getValue() + " rescinded");
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
        LOGGER.info("Status update - Task ID: " + status.getTaskId() + ", State: " + status.getState());
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, byte[] data) {
        LOGGER.info("Framework Message - Executor: " + executorId.getValue() + ", SlaveID: " + slaveId.getValue());
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        LOGGER.warn("Disconnected");
    }

    @Override
    public void slaveLost(SchedulerDriver driver, Protos.SlaveID slaveId) {
        LOGGER.info("Slave lost: " + slaveId.getValue());
    }

    @Override
    public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId, Protos.SlaveID slaveId, int status) {
        LOGGER.info("Executor lost: " + executorId.getValue() +
                "on slave " + slaveId.getValue() +
                "with status " + status);
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOGGER.error("Error: " + message);
    }

    private String taskId(Protos.Offer offer) {
        String date = new SimpleDateFormat(TASK_DATE_FORMAT).format(clock.now());
        return String.format("elasticsearch_%s_%s", offer.getHostname(), date);
    }

    private boolean isOfferGood(Protos.Offer offer) {
        // Don't start the same framework multiple times on the same host
        for (Task task : tasks) {
            if (task.getHostname().equals(offer.getHostname())) {
                return false;
            }
        }
        return true;

        //TODO: return tasks.stream().map(Task::getHostname).noneMatch(Predicate.isEqual(offer.getHostname()));
    }

    private boolean haveEnoughNodes() {
        return tasks.size() == numberOfHwNodes;
    }

    public Set<Task> getTasks() {
        return tasks;
    }
}
