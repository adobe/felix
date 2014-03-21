package dm.it;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;

import dm.Component;
import dm.ConfigurationDependency;
import dm.DependencyManager;
import dm.ServiceDependency;


/**
 * This test class simulates a client having many dependencies being registered/unregistered concurrently.
 */
public class ServiceRaceTest extends TestBase {
    volatile ConfigurationAdmin m_cm;
    volatile DependencyManager m_dm;
    final static int STEP_WAIT = 5000;
    final static int DEPENDENCIES = 10;
    final static int LOOPS = 3000;
    final Ensure m_done = new Ensure(true);

    // Executor used to bind/unbind service dependencies.
    ExecutorService m_threadpool;
    // Timestamp used to log the time consumed to execute 100 tests.
    long m_timeStamp;

    public interface Dep {        
    }
    
    public class DepImpl implements Dep {        
    }

    /**
     * Creates many service dependencies, and activate/deactivate them concurrently.  
     */
    public void testCreateParallelComponentRegistgrationUnregistration() {
        m_dm = new DependencyManager(context);
        m_dm.add(m_dm.createComponent()
            .setImplementation(this)
            .setCallbacks(null, "start", null, null)
            .add(m_dm.createServiceDependency().setService(ConfigurationAdmin.class).setRequired(true)));
        m_done.waitForStep(1, 60000);
        m_dm.clear();
        Assert.assertFalse(super.errorsLogged());
    }
    
    void start() {
        new Thread(new Runnable() {
            public void run() {
                doStart();
            }}).start();
    }
    
    void doStart() {
        info("Starting createParallelComponentRegistgrationUnregistration test");
        int cores = Math.max(16, Runtime.getRuntime().availableProcessors());
        info("using " + cores + " cores.");

        m_threadpool = Executors.newFixedThreadPool(Math.max(cores, DEPENDENCIES + 3 /* start/stop/configure */));

        try {
            m_timeStamp = System.currentTimeMillis();
            for (int loop = 0; loop < LOOPS; loop++) {
                doTest(loop);
            }
        }
        catch (Throwable t) {
            error("got unexpected exception", t);
        }
        finally {
            shutdown(m_threadpool);
            m_done.step(1);
        }
    }

    void shutdown(ExecutorService exec) {
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
        }
    }

    void doTest(int loop) throws Throwable {
        debug("loop#%d -------------------------", loop);

        final Ensure step = new Ensure(false);

        // Create one client component, which depends on many service dependencies
        final Component client = m_dm.createComponent();
        final Client clientImpl = new Client(step);
        client.setImplementation(clientImpl);

        // Create client service dependencies
        final ServiceDependency[] dependencies = new ServiceDependency[DEPENDENCIES];
        for (int i = 0; i < DEPENDENCIES; i++) {
            final String filter = "(id=loop" + loop + "." + i + ")";
            dependencies[i] = m_dm.createServiceDependency().setService(Dep.class, filter)
                .setRequired(true)
                .setCallbacks("add", "remove");
            client.add(dependencies[i]);
        }
        String pid = "pid." + loop;
        final ConfigurationDependency confDependency = m_dm.createConfigurationDependency().setPid(pid);
        client.add(confDependency);

        // Create Configuration (concurrently).
        final Configuration conf = m_cm.getConfiguration(pid, null);
        final Hashtable props = new Hashtable();
        props.put("foo", "bar");
        m_threadpool.execute(new Runnable() {
            public void run() {
                try {
                    conf.update(props);
                }
                catch (IOException e) {
                    error("update failed", e);
                }
            }
        });
        
        // Activate the client service dependencies concurrently.
        List<Component> deps = new ArrayList();
        for (int i = 0; i < DEPENDENCIES; i++) {
            Hashtable h = new Hashtable();
            h.put("id", "loop" + loop + "." + i);
            final Component s = m_dm.createComponent()
                .setInterface(Dep.class.getName(), h)
                .setImplementation(new DepImpl());
            deps.add(s);
            m_threadpool.execute(new Runnable() {
                public void run() {
                    m_dm.add(s);
                }
            });
        }

        // Start the client (concurrently)
        m_threadpool.execute(new Runnable() {
            public void run() {
                m_dm.add(client);
            }
        });

        // Ensure that client has been started.
        int expectedStep = 1 /* conf */ + DEPENDENCIES + 1 /* start */;
        step.waitForStep(expectedStep, STEP_WAIT);
        Assert.assertEquals(DEPENDENCIES, clientImpl.getDependencies());
        Assert.assertNotNull(clientImpl.getConfiguration());

        // Stop all dependencies concurrently.
        for (Component dep : deps) {
            final Component dependency = dep;
            m_threadpool.execute(new Runnable() {
                public void run() {
                    m_dm.remove(dependency);
                }
            });
        }
        
        // Stop client concurrently.
        m_threadpool.execute(new Runnable() {
            public void run() {
                m_dm.remove(client);
            }
        });
        
        // Remove configuration (asynchronously)
        m_threadpool.execute(new Runnable() {
            public void run() {
                try {
                    conf.delete();
                }
                catch (IOException e) {
                    warn("error while unconfiguring", e);
                }
            }
        });

        // Ensure that client has been stopped, then destroyed, then unbound from all dependencies
        expectedStep += 2; // stop/destroy
        expectedStep += DEPENDENCIES; // removed all dependencies
        expectedStep += 1; // removed configuration
        step.waitForStep(expectedStep, STEP_WAIT);
        step.ensure();
        Assert.assertEquals(0, clientImpl.getDependencies());
        Assert.assertNull(clientImpl.getConfiguration());                

        if (super.errorsLogged()) {
            throw new IllegalStateException("Race test interrupted (some error occured, see previous logs)");
        }

        debug("finished one test loop");
        if ((loop + 1) % 100 == 0) {
            long duration = System.currentTimeMillis() - m_timeStamp;
            warn("Performed 100 tests (total=%d) in %d ms.", (loop + 1), duration);
            m_timeStamp = System.currentTimeMillis();
        }
    }

    public class Client {
        final Ensure m_step;
        volatile int m_dependencies;
        volatile Dictionary m_conf;
        
        public Client(Ensure step) {
            m_step = step;
        }

        public void updated(Dictionary conf) throws ConfigurationException {
            m_conf = conf;
            if (conf != null) {
                Assert.assertEquals("bar", conf.get("foo"));
                m_step.step(1);
            } else {
                m_step.step();
            }
        }
        
        void add(Dep d) {
            Assert.assertNotNull(d);
            m_step.step();
            m_dependencies ++;
        }
        
        void remove(Dep d) {
            Assert.assertNotNull(d);
            m_step.step();
            m_dependencies --;
        }
                
        void start() {
            m_step.step((DEPENDENCIES + 1) /* deps + conf */ + 1 /* start */);
        }

        void stop() {
            m_step.step((DEPENDENCIES + 1) /* deps + conf */ + 1 /* start */ + 1 /* stop */);
        }
        
        void destroy() {
            m_step.step((DEPENDENCIES + 1) /* deps + conf */ + 1 /* start */ + 1 /* stop */  + 1 /* destroy */);
        }
        
        int getDependencies() {
            return m_dependencies;
        }
        
        Dictionary getConfiguration() {
            return m_conf;
        }
    }
}
