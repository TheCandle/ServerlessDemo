package com.facebook.presto.server.testing;

import com.facebook.airlift.node.NodeConfig;
import com.facebook.airlift.node.NodeInfo;
import com.google.common.net.InetAddresses;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class CustomTestingNodeModule
        implements Module
{
    // avoid having an accidental dependency on the environment name
    private static final AtomicLong nextId = new AtomicLong(ThreadLocalRandom.current().nextInt(1000000));

    private final String environment;
    private final Optional<String> pool;

    public CustomTestingNodeModule()
    {
        this(Optional.empty());
    }

    public CustomTestingNodeModule(Optional<String> environment)
    {
        this(environment.orElse("test" + nextId.getAndIncrement()));
    }

    public CustomTestingNodeModule(String environment)
    {
        this(environment, Optional.empty());
    }

    public CustomTestingNodeModule(String environment, Optional<String> pool)
    {
        checkArgument(!isNullOrEmpty(environment), "environment is null or empty");
        this.environment = environment;
        this.pool = requireNonNull(pool, "pool is null");
    }

    public CustomTestingNodeModule(String environment, String pool)
    {
        this(environment, Optional.of(requireNonNull(pool, "pool is null")));
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(NodeInfo.class).in(Scopes.SINGLETON);
        NodeConfig nodeConfig = new NodeConfig()
                .setEnvironment(environment)
                .setNodeInternalAddress(InetAddresses.toAddrString(getV4Localhost()))
                .setNodeBindIp(getV4Localhost());

        if (pool.isPresent()) {
            nodeConfig.setPool(pool.get());
        }

        binder.bind(NodeConfig.class).toInstance(nodeConfig);

        newExporter(binder).export(NodeInfo.class).withGeneratedName();
    }

    @SuppressWarnings("ImplicitNumericConversion")
    private static InetAddress getV4Localhost()
    {
        try {
            return InetAddress.getByAddress("0.0.0.0", new byte[] {0, 0, 0, 0});
        }
        catch (UnknownHostException e) {
            throw new AssertionError("Could not create localhost address");
        }
    }
}
