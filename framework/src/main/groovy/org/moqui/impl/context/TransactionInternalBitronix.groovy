/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context

import bitronix.tm.BitronixTransactionManager
import bitronix.tm.TransactionManagerServices
import bitronix.tm.resource.jdbc.PoolingDataSource
import bitronix.tm.utils.ClassLoaderUtils
import bitronix.tm.utils.PropertyUtils
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource
import javax.sql.XADataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction
import java.sql.Connection

@CompileStatic
class TransactionInternalBitronix implements TransactionInternal {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionInternalBitronix.class)

    protected ExecutionContextFactoryImpl ecfi

    protected BitronixTransactionManager btm
    protected UserTransaction ut
    protected TransactionManager tm

    protected List<PoolingDataSource> pdsList = []

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        // NOTE: see the bitronix-default-config.properties file for more config
        checkServerId()

        btm = TransactionManagerServices.getTransactionManager()
        this.ut = btm
        this.tm = btm

        return this
    }

    /** Bitronix stamps every transaction ID with the server ID and, on restart, recovers the unfinished transactions in its
     * journal (runtime/txlog) that carry that ID, so each instance needs an ID that differs from every other instance sharing
     * the database and stays with its own journal. Without one Bitronix falls back to the machine's IP address, which
     * several Aspen servers on one IBM i (or a rebuilt Azure VM or container) share or lose. So no instance needs setup:
     * the first start generates an ID and saves it beside the journal in txlog/server-id, and later starts reuse it. A
     * -Dbitronix.tm.serverId=... JVM option (or bitronix.tm.serverId in the properties file) still overrides it. */
    protected static void checkServerId() {
        bitronix.tm.Configuration btmConfig = TransactionManagerServices.getConfiguration()
        String serverId = btmConfig.getServerId()
        String source = "bitronix.tm.serverId"
        if (!serverId) {
            File idFile = new File(new File(btmConfig.getLogPart1Filename()).getAbsoluteFile().getParentFile(), "server-id")
            try {
                if (idFile.exists()) serverId = idFile.getText("US-ASCII").trim()
                if (!serverId) {
                    serverId = makeServerId()
                    idFile.getParentFile().mkdirs()
                    idFile.setText(serverId + "\n", "US-ASCII")
                    logger.info("Generated a Bitronix server ID for this instance and saved it in ${idFile.path}")
                }
                btmConfig.setServerId(serverId)
                source = idFile.path
            } catch (Exception e) {
                logger.warn("Could not read or save the Bitronix server ID in ${idFile.path}, so Bitronix will use the IP address, which is unsafe " +
                        "when several instances share a machine or the IP changes. Make that directory writable, or set -Dbitronix.tm.serverId " +
                        "for this instance: ${e}")
                return
            }
        }
        boolean ascii = true
        for (int i = 0; i < serverId.length(); i++) if (serverId.charAt(i) > (char) 127) { ascii = false; break }
        if (!ascii || serverId.length() > 51) {
            logger.warn("Bitronix server ID '${serverId}' must be US-ASCII and at most 51 characters; Bitronix will truncate or replace it. Set a shorter ID in ${source}.")
        }
        logger.info("Bitronix transaction manager server ID: ${serverId} (from ${source})")
    }
    /** aspen-<short host name>-<8 random hex digits>: unique per instance even when several share a host, at most 45 characters */
    protected static String makeServerId() {
        String host = ""
        try { host = InetAddress.getLocalHost().getHostName() } catch (Exception e) { logger.trace("No local host name for the Bitronix server ID", e) }
        host = host.split("\\.")[0].replaceAll("[^A-Za-z0-9-]", "").toLowerCase()
        if (host.length() > 30) host = host.substring(0, 30)
        byte[] random = new byte[4]
        new java.security.SecureRandom().nextBytes(random)
        return "aspen-" + (host ?: "host") + "-" + random.encodeHex().toString()
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(EntityFacade ef, MNode datasourceNode) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

        PoolingDataSource pds = new PoolingDataSource()
        pds.setUniqueName(dsi.uniqueName)
        if (dsi.xaDsClass) {
            pds.setClassName(dsi.xaDsClass)
            pds.setDriverProperties(dsi.xaProps)

            Class<?> xaFactoryClass = ClassLoaderUtils.loadClass(dsi.xaDsClass)
            Object xaFactory = xaFactoryClass.newInstance()
            if (!(xaFactory instanceof XADataSource))
                throw new IllegalArgumentException("xa-ds-class " + xaFactory.getClass().getName() + " does not implement XADataSource")
            XADataSource xaDataSource = (XADataSource) xaFactory

            for (Map.Entry<Object, Object> entry : dsi.xaProps.entrySet()) {
                String name = (String) entry.getKey()
                Object value = entry.getValue()

                try {
                    PropertyUtils.setProperty(xaDataSource, name, value)
                } catch (Exception e) {
                    logger.warn("Error setting ${dsi.uniqueName} property ${name}, ignoring: ${e.toString()}")
                }
            }
            pds.setXaDataSource(xaDataSource)
        } else {
            pds.setClassName("bitronix.tm.resource.jdbc.lrc.LrcXADataSource")
            pds.getDriverProperties().setProperty("driverClassName", dsi.jdbcDriver)
            pds.getDriverProperties().setProperty("url", dsi.jdbcUri)
            pds.getDriverProperties().setProperty("user", dsi.jdbcUsername)
            pds.getDriverProperties().setProperty("password", dsi.jdbcPassword)
        }

        String txIsolationLevel = dsi.inlineJdbc.attribute("isolation-level") ?
                dsi.inlineJdbc.attribute("isolation-level") : dsi.database.attribute("default-isolation-level")
        int isolationInt = efi.getTxIsolationFromString(txIsolationLevel)
        if (txIsolationLevel && isolationInt != -1) {
            switch (isolationInt) {
                case Connection.TRANSACTION_SERIALIZABLE: pds.setIsolationLevel("SERIALIZABLE"); break
                case Connection.TRANSACTION_REPEATABLE_READ: pds.setIsolationLevel("REPEATABLE_READ"); break
                case Connection.TRANSACTION_READ_UNCOMMITTED: pds.setIsolationLevel("READ_UNCOMMITTED"); break
                case Connection.TRANSACTION_READ_COMMITTED: pds.setIsolationLevel("READ_COMMITTED"); break
                case Connection.TRANSACTION_NONE: pds.setIsolationLevel("NONE"); break
            }
        }

        // no need for this, just sets min and max sizes: ads.setPoolSize
        pds.setMinPoolSize((dsi.inlineJdbc.attribute("pool-minsize") ?: "5") as int)
        pds.setMaxPoolSize((dsi.inlineJdbc.attribute("pool-maxsize") ?: "50") as int)

        if (dsi.inlineJdbc.attribute("pool-time-idle")) pds.setMaxIdleTime(dsi.inlineJdbc.attribute("pool-time-idle") as int)
        // if (dsi.inlineJdbc."@pool-time-reap") ads.setReapTimeout(dsi.inlineJdbc."@pool-time-reap" as int)
        // if (dsi.inlineJdbc."@pool-time-maint") ads.setMaintenanceInterval(dsi.inlineJdbc."@pool-time-maint" as int)
        if (dsi.inlineJdbc.attribute("pool-time-wait")) pds.setAcquisitionTimeout(dsi.inlineJdbc.attribute("pool-time-wait") as int)
        pds.setAllowLocalTransactions(true) // allow mixing XA and non-XA transactions
        pds.setAutomaticEnlistingEnabled(true) // automatically enlist/delist this resource in the tx
        pds.setShareTransactionConnections(true) // share connections within a transaction
        pds.setDeferConnectionRelease(true) // only one transaction per DB connection (can be false if supported by DB)
        // pds.setShareTransactionConnections(false) // don't share connections in the ACCESSIBLE, needed?
        // pds.setIgnoreRecoveryFailures(false) // something to consider for XA recovery errors, quarantines by default

        pds.setEnableJdbc4ConnectionTest(true) // use faster jdbc4 connection test
        // default is 0, disabled PreparedStatement cache (cache size per Connection)
        // NOTE: make this configurable? value too high or low?
        pds.setPreparedStatementCacheSize(100)

        // use-tm-join defaults to true, so does Bitronix so just set to false if false
        if (dsi.database.attribute("use-tm-join") == "false") pds.setUseTmJoin(false)

        if (dsi.inlineJdbc.attribute("pool-test-query")) {
            pds.setTestQuery(dsi.inlineJdbc.attribute("pool-test-query"))
        } else if (dsi.database.attribute("default-test-query")) {
            pds.setTestQuery(dsi.database.attribute("default-test-query"))
        }

        logger.info("Initializing DataSource ${dsi.uniqueName} (${dsi.database.attribute('name')}) with properties: ${dsi.dsDetails}")

        // init the DataSource
        pds.init()
        logger.info("Init DataSource ${dsi.uniqueName} (${dsi.database.attribute('name')}) isolation ${pds.getIsolationLevel()} (${isolationInt}), max pool ${pds.getMaxPoolSize()}")

        pdsList.add(pds)

        return pds
    }

    @Override
    void destroy() {
        logger.info("Shutting down Bitronix")
        // close the DataSources
        for (PoolingDataSource pds in pdsList) pds.close()
        // shutdown Bitronix
        btm.shutdown()
    }
}
