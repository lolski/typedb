package grakn.core.server.kb;

import grakn.client.GraknClient;
import graql.lang.Graql;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GskIT {
    public static final Logger LOG = LoggerFactory.getLogger(GskIT.class);

    @Test
    public void limitQuery() {
        LOG.info("hi");
        try (GraknClient graknClient = new GraknClient("localhost:48555")) {
            try (GraknClient.Session session = graknClient.session("ten_milion")) {
                try (GraknClient.Transaction tx = session.transaction().read()) {
                    long start = System.currentTimeMillis();
                    tx.execute(Graql.parse("match $s isa sentence; get; limit 1;").asGet());
                    long elapsed = System.currentTimeMillis() - start;
                    System.out.println("elapsed = " + elapsed + "ms");
                }
            }
        }
    }
}
