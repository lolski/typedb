/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.server;

import grabl.tracing.client.GrablTracing;
import grabl.tracing.client.GrablTracingThreadStatic;
import grakn.common.util.Pair;
import grakn.core.common.exception.GraknException;
import grakn.core.common.concurrent.NamedThreadFactory;
import grakn.core.server.util.Options;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.PropertiesDefaultProvider;
import picocli.CommandLine.UnmatchedArgumentException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static grakn.core.common.exception.Error.Server.ENV_VAR_NOT_EXIST;
import static grakn.core.common.exception.Error.Server.EXITED_WITH_ERROR;
import static grakn.core.common.exception.Error.Server.FAILED_AT_STOPPING;
import static grakn.core.common.exception.Error.Server.FAILED_PARSE_PROPERTIES;
import static grakn.core.common.exception.Error.Server.PROPERTIES_FILE_NOT_AVAILABLE;
import static grakn.core.common.exception.Error.Server.UNCAUGHT_EXCEPTION;


public class GraknServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(GraknServer.class);
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors();

    private Server rpcServer;
    private Options options;

    private GraknServer(Options options) {
        this.options = options;
        if (this.options.grablTrace()) enableGrablTracing();
        rpcServer = createRPCServer();
        Runtime.getRuntime().addShutdownHook(NamedThreadFactory.create(GraknServer.class, "shutdown").newThread(this::close));
        Thread.setDefaultUncaughtExceptionHandler((Thread t, Throwable e) -> LOG.error(UNCAUGHT_EXCEPTION.message(t.getName()), e));
    }

    private void enableGrablTracing() {
        GrablTracing grablTracingClient;
        grablTracingClient = GrablTracing.withLogging(GrablTracing.tracing(
                options.grablURI().toString(),
                options.grablUsername(),
                options.grablToken()
        ));
        GrablTracingThreadStatic.setGlobalTracingClient(grablTracingClient);
        GraknServer.LOG.info("Grabl tracing is enabled");
    }

    private Server createRPCServer() {
        NioEventLoopGroup workerELG = new NioEventLoopGroup(MAX_THREADS, NamedThreadFactory.create(GraknServer.class, "worker"));
        return NettyServerBuilder.forPort(options.databasePort())
                .executor(Executors.newFixedThreadPool(MAX_THREADS, NamedThreadFactory.create(GraknServer.class, "executor")))
                .workerEventLoopGroup(workerELG)
                .bossEventLoopGroup(workerELG)
                .maxConnectionIdle(1, TimeUnit.HOURS) // TODO: why 1 hour?
                .channelType(NioServerSocketChannel.class)
                .build();
    }

    @Override
    public void close() {
        try {
            rpcServer.shutdown();
            rpcServer.awaitTermination();
        } catch (InterruptedException e) {
            LOG.error(FAILED_AT_STOPPING.message(), e);
            Thread.currentThread().interrupt();
        }
    }

    private void start() throws IOException {
        try {
            rpcServer.start();
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
            throw e;
        }
    }

    private void serve() {
        try {
            rpcServer.awaitTermination();
        } catch (InterruptedException e) {
            // grakn server stop is called
            close();
            Thread.currentThread().interrupt();
        }
    }

    private static Pair<Boolean, Options> parseCommandLine(Properties properties, String[] args) {
        Options options = new Options();
        boolean proceed;
        CommandLine command = new CommandLine(options);
        command.setDefaultValueProvider(new PropertiesDefaultProvider(properties));

        try {
            command.parseArgs(args);
            if (command.isUsageHelpRequested()) {
                command.usage(command.getOut());
                proceed = false;
            } else if (command.isVersionHelpRequested()) {
                command.printVersionHelp(command.getOut());
                proceed = false;
            } else {
                proceed = true;
            }
        } catch (ParameterException ex) {
            command.getErr().println(ex.getMessage());
            if (!UnmatchedArgumentException.printSuggestions(ex, command.getErr())) {
                ex.getCommandLine().usage(command.getErr());
            }
            proceed = false;
        }

        return new Pair<>(proceed, options);
    }

    private static Properties parseProperties() {
        Properties properties = new Properties();
        boolean error = false;
        File file = Paths.get(Options.DEAFAULT_PROPERTIES_FILE).toFile();

        try {
            properties.load(new FileInputStream(file));
        } catch (IOException e) {
            LOG.error(PROPERTIES_FILE_NOT_AVAILABLE.message(file.toString()));
            error = true;
        }

        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String val = (String) entry.getValue();
            if (val.startsWith("$")) {
                String envVarName = val.substring(1);
                if (System.getenv(envVarName) == null) {
                    LOG.error(ENV_VAR_NOT_EXIST.message(val));
                    error = true;
                } else {
                    properties.put(entry.getKey(), System.getenv(envVarName));
                }
            }
        }

        if (error) throw new GraknException(FAILED_PARSE_PROPERTIES);
        else return properties;
    }

    public static void main(String[] args) {
        try {
            long start = System.nanoTime();
            Pair<Boolean, Options> result = parseCommandLine(parseProperties(), args);
            if (!result.first()) System.exit(0);

            GraknServer server = new GraknServer(result.second());
            server.start();

            long end = System.nanoTime();
            LOG.info("Grakn Core started in {} ms", String.format("%.3f", (end - start) / 1_000_000.00));

            server.serve();
        } catch (Exception e) {
            LOG.error(e.getMessage());
            LOG.error(EXITED_WITH_ERROR.message());
            System.exit(1);
        }

        System.exit(0);
    }
}
