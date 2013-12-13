package org.apache.marmotta.kiwi.loader.generic;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.sf.ehcache.Element;
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import org.apache.marmotta.commons.sesame.model.Namespaces;
import org.apache.marmotta.commons.sesame.tripletable.IntArray;
import org.apache.marmotta.commons.util.DateUtils;
import org.apache.marmotta.kiwi.loader.KiWiLoaderConfiguration;
import org.apache.marmotta.kiwi.model.rdf.*;
import org.apache.marmotta.kiwi.persistence.KiWiConnection;
import org.apache.marmotta.kiwi.persistence.KiWiTripleRegistry;
import org.apache.marmotta.kiwi.sail.KiWiStore;
import org.openrdf.model.*;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Date;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import static org.apache.marmotta.kiwi.loader.util.UnitFormatter.formatSize;

/**
 * A fast-lane RDF import handler that allows bulk-importing triples into a KiWi triplestore. It directly accesses
 * the database using a KiWiConnection. Note that certain configuration options will make the import "unsafe"
 * because they turn off expensive existence checks. If you are not careful and import the same data twice, this
 * might mean duplicate entries in the database.
 *
 * @author Sebastian Schaffert (sschaffert@apache.org)
 */
public class KiWiHandler implements RDFHandler {

    private static Logger log = LoggerFactory.getLogger(KiWiHandler.class);

    protected KiWiConnection connection;
    protected KiWiStore store;

    protected long triples = 0;
    protected long nodes = 0;
    protected long nodesLoaded = 0;

    protected long start = 0;
    protected long previous = 0;

    protected KiWiLoaderConfiguration config;

    protected SelfPopulatingCache literalCache;
    protected SelfPopulatingCache uriCache;
    protected SelfPopulatingCache bnodeCache;
    protected LoadingCache<String,Locale> localeCache;

    // if non-null, all imported statements will have this context (regardless whether they specified a different context)
    private KiWiResource overrideContext;

    private Statistics statistics;

    // only used when statement existance check is enabled
    protected KiWiTripleRegistry registry;


    protected Date importDate;

    protected boolean initialised = false;

    public KiWiHandler(KiWiStore store, KiWiLoaderConfiguration config) {
        this.config     = config;
        this.store      = store;

        this.literalCache = new SelfPopulatingCache(store.getPersistence().getCacheManager().getLoaderCache(), new CacheEntryFactory() {
            @Override
            public Object createEntry(Object key) throws Exception {
                return createLiteral((Literal)key);
            }
        });

        this.uriCache = new SelfPopulatingCache(store.getPersistence().getCacheManager().getLoaderCache(), new CacheEntryFactory() {
            @Override
            public Object createEntry(Object key) throws Exception {
                return createURI(((URI)key).stringValue());
            }
        });

        this.bnodeCache = new SelfPopulatingCache(store.getPersistence().getCacheManager().getLoaderCache(), new CacheEntryFactory() {
            @Override
            public Object createEntry(Object key) throws Exception {
                return createBNode(((BNode)key).stringValue());
            }
        });

        this.localeCache = CacheBuilder.newBuilder()
                .maximumSize(100)
                .build(new CacheLoader<String, Locale>() {
                    @Override
                    public Locale load(String lang) throws Exception {
                        try {
                            Locale.Builder builder = new Locale.Builder();
                            builder.setLanguageTag(lang);
                            return builder.build();
                        } catch (IllformedLocaleException ex) {
                            log.warn("malformed language literal (language: {})", lang);
                            return null;
                        }
                    }
                });


        if(config.isStatementExistanceCheck()) {
            registry = new KiWiTripleRegistry(store);
        }
    }


    /**
     * Perform initialisation, e.g. dropping indexes or other preparations.
     */
    public void initialise() throws RDFHandlerException {
        log.info("KiWiLoader: initialising RDF handler");
        try {
            this.connection = store.getPersistence().getConnection();
        } catch (SQLException e) {
            throw new RDFHandlerException(e);
        }


        if(config.isStatistics()) {
            statistics = new Statistics(this);
            statistics.startSampling();
        }
        initialised = true;
    }


    /**
     * Peform cleanup on shutdown, e.g. re-creating indexes after import completed
     */
    public void shutdown() throws RDFHandlerException {
        log.info("KiWiLoader: shutting down RDF handler");
        try {
            connection.close();
        } catch (SQLException e) {
            throw new RDFHandlerException(e);
        }

        initialised = false;

        if(config.isStatistics() && statistics != null) {
            statistics.stopSampling();
        }
    }

    /**
     * Signals the end of the RDF data. This method is called when all data has
     * been reported.
     *
     * @throws org.openrdf.rio.RDFHandlerException
     *          If the RDF handler has encountered an unrecoverable error.
     */
    @Override
    public void endRDF() throws RDFHandlerException {

        if(registry != null) {
            registry.releaseTransaction(connection.getTransactionId());
        }

        try {
            connection.commit();
        } catch (SQLException e) {
            throw new RDFHandlerException(e);
        }

        log.info("KiWiLoader: RDF bulk import of {} triples finished after {} ms", triples, System.currentTimeMillis() - start);
    }

    /**
     * Signals the start of the RDF data. This method is called before any data
     * is reported.
     *
     * @throws org.openrdf.rio.RDFHandlerException
     *          If the RDF handler has encountered an unrecoverable error.
     */
    @Override
    public void startRDF() throws RDFHandlerException {
        if(!initialised) {
            initialise();
        }
        log.info("KiWiLoader: starting RDF bulk import");

        this.start = System.currentTimeMillis();
        this.previous = System.currentTimeMillis();

        this.importDate = new Date(this.start);

        if(config.getContext() != null) {
            try {
                this.overrideContext = (KiWiResource)convertNode(new URIImpl(config.getContext()));
            } catch (ExecutionException e) {
                log.error("could not create/load resource",e);
            }
        }


    }

    /**
     * Handles a namespace declaration/definition. A namespace declaration
     * associates a (short) prefix string with the namespace's URI. The prefix
     * for default namespaces, which do not have an associated prefix, are
     * represented as empty strings.
     *
     * @param prefix The prefix for the namespace, or an empty string in case of a
     *               default namespace.
     * @param uri    The URI that the prefix maps to.
     * @throws org.openrdf.rio.RDFHandlerException
     *          If the RDF handler has encountered an unrecoverable error.
     */
    @Override
    public void handleNamespace(String prefix, String uri) throws RDFHandlerException {
        try {
            connection.storeNamespace(new KiWiNamespace(prefix,uri));
        } catch (SQLException e) {
            throw new RDFHandlerException(e);
        }
    }

    /**
     * Handles a statement.
     *
     * @param st The statement.
     * @throws org.openrdf.rio.RDFHandlerException
     *          If the RDF handler has encountered an unrecoverable error.
     */
    @Override
    public void handleStatement(Statement st) throws RDFHandlerException {
        try {
            KiWiResource subject = (KiWiResource)convertNode(st.getSubject());
            KiWiUriResource predicate = (KiWiUriResource)convertNode(st.getPredicate());
            KiWiNode object = convertNode(st.getObject());
            KiWiResource context;

            if(this.overrideContext != null) {
                context = this.overrideContext;
            } else {
                context = (KiWiResource)convertNode(st.getContext());
            }

            KiWiTriple result = new KiWiTriple(subject,predicate,object,context, importDate);

            // statement existance check; use the triple registry to lookup if there are any concurrent triple creations
            if(config.isStatementExistanceCheck()) {
                IntArray cacheKey = IntArray.createSPOCKey(subject, predicate, object, context);
                long tripleId = registry.lookupKey(cacheKey);

                if(tripleId >= 0) {
                    // try getting id from registry
                    result.setId(tripleId);

                    registry.registerKey(cacheKey, connection.getTransactionId(), result.getId());
                } else {
                    // not found in registry, try loading from database
                    result.setId(connection.getTripleId(subject,predicate,object,context,true));
                }

                // triple has no id from registry or database, so we create one and flag it for reasoning
                if(result.getId() < 0) {
                    result.setId(connection.getNextSequence("seq.triples"));
                    result.setNewTriple(true);

                    registry.registerKey(cacheKey, connection.getTransactionId(), result.getId());
                }
            } else {
                result.setId(connection.getNextSequence("triples"));
            }

            storeTriple(result);

        } catch (SQLException | ExecutionException e) {
            throw new RDFHandlerException(e);
        }

    }


    private KiWiNode convertNode(Value value) throws ExecutionException {
        if(value == null) {
            return null;
        } else if(value instanceof KiWiNode) {
            return (KiWiNode)value;
        } else if(value instanceof URI) {
            Element e = uriCache.get((URI) value);
            if(e != null) {
                return (KiWiNode) e.getObjectValue();
            }
        } else if(value instanceof BNode) {
            Element e = bnodeCache.get(((BNode)value));
            if(e != null) {
                return (KiWiNode) e.getObjectValue();
            }
        } else if(value instanceof Literal) {
            Literal l = (Literal)value;
            Element e = literalCache.get(l);
            if(e != null) {
                return (KiWiNode) e.getObjectValue();
            }

        } else {
            throw new IllegalArgumentException("the value passed as argument does not have the correct type");
        }
        throw new IllegalStateException("could not construct or load node");

    }

    protected KiWiLiteral createLiteral(Literal l) throws ExecutionException {
        String value = l.getLabel();
        String lang  = l.getLanguage() != null ? l.getLanguage().intern() : null;
        URI    type  = l.getDatatype();


        Locale locale;
        if(lang != null) {
            locale = localeCache.get(lang);
        } else {
            locale = null;
        }
        if(locale == null) {
            lang = null;
        }


        KiWiLiteral result;
        final KiWiUriResource rtype = type==null ? null : (KiWiUriResource) convertNode(type);

        try {

            try {
                // differentiate between the different types of the value
                if (type == null) {
                    // FIXME: MARMOTTA-39 (this is to avoid a NullPointerException in the following if-clauses)
                    result = connection.loadLiteral(sanitizeString(value.toString()), lang, rtype);

                    if(result == null) {
                        result = new KiWiStringLiteral(sanitizeString(value.toString()), locale, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                } else if(type.equals(Namespaces.NS_XSD+"dateTime")) {
                    // parse if necessary
                    final Date dvalue = DateUtils.parseDate(value.toString());

                    result = connection.loadLiteral(dvalue);

                    if(result == null) {
                        result= new KiWiDateLiteral(dvalue, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                } else if(type.equals(Namespaces.NS_XSD+"integer") || type.equals(Namespaces.NS_XSD+"long")) {
                    long ivalue = Long.parseLong(value.toString());

                    result = connection.loadLiteral(ivalue);

                    if(result == null) {
                        result= new KiWiIntLiteral(ivalue, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                } else if(type.equals(Namespaces.NS_XSD+"double") || type.equals(Namespaces.NS_XSD+"float")) {
                    double dvalue = Double.parseDouble(value.toString());

                    result = connection.loadLiteral(dvalue);

                    if(result == null) {
                        result= new KiWiDoubleLiteral(dvalue, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                } else if(type.equals(Namespaces.NS_XSD+"boolean")) {
                    boolean bvalue = Boolean.parseBoolean(value.toString());

                    result = connection.loadLiteral(bvalue);

                    if(result == null) {
                        result= new KiWiBooleanLiteral(bvalue, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                } else {
                    result = connection.loadLiteral(sanitizeString(value.toString()), lang, rtype);

                    if(result == null) {
                        result = new KiWiStringLiteral(sanitizeString(value.toString()), locale, rtype, importDate);
                    } else {
                        nodesLoaded++;
                    }
                }
            } catch(IllegalArgumentException ex) {
                // malformed number or date
                log.warn("malformed argument for typed literal of type {}: {}", rtype.stringValue(), value);
                KiWiUriResource mytype = createURI(Namespaces.NS_XSD+"string");

                result = connection.loadLiteral(sanitizeString(value.toString()), lang, mytype);

                if(result == null) {
                    result = new KiWiStringLiteral(sanitizeString(value.toString()), locale, mytype, importDate);
                } else {
                    nodesLoaded++;
                }

            }

            if(result.getId() < 0) {
                storeNode(result);
            }

            return result;


        } catch (SQLException e) {
            log.error("database error, could not load literal",e);
            throw new IllegalStateException("database error, could not load literal",e);
        }
    }

    protected KiWiUriResource createURI(String uri) {
        try {
            // first look in the registry for newly created resources if the resource has already been created and
            // is still volatile
            KiWiUriResource result = connection.loadUriResource(uri);

            if(result == null) {
                result = new KiWiUriResource(uri, importDate);

                storeNode(result);

            } else {
                nodesLoaded++;
            }
            if(result.getId() < 0) {
                log.error("node ID is null!");
            }

            return result;
        } catch (SQLException e) {
            log.error("database error, could not load URI resource",e);
            throw new IllegalStateException("database error, could not load URI resource",e);
        }
    }

    protected KiWiAnonResource createBNode(String nodeID) {
        try {
            // first look in the registry for newly created resources if the resource has already been created and
            // is still volatile
            KiWiAnonResource result = connection.loadAnonResource(nodeID);

            if(result == null) {
                result = new KiWiAnonResource(nodeID, importDate);
                storeNode(result);
            } else {
                nodesLoaded++;
            }
            if(result.getId() < 0) {
                log.error("node ID is null!");
            }

            return result;
        } catch (SQLException e) {
            log.error("database error, could not load anonymous resource",e);
            throw new IllegalStateException("database error, could not load anonymous resource",e);
        }
    }


    protected void storeNode(KiWiNode node) throws SQLException {
        connection.storeNode(node, false);

        nodes++;
    }

    protected void storeTriple(KiWiTriple result) throws SQLException {
        connection.storeTriple(result);

        triples++;

        if(triples % config.getCommitBatchSize() == 0) {
            if(registry != null) {
                registry.releaseTransaction(connection.getTransactionId());
            }

            connection.commit();

            printStatistics();
        }
    }


    protected void printStatistics() {
        if(statistics != null) {
            statistics.printStatistics();
        } else {
            log.info("imported {} triples ({}/sec)", triples, formatSize((config.getCommitBatchSize() * 1000) / (System.currentTimeMillis() - previous)) );
            previous = System.currentTimeMillis();
        }


    }


    /**
     * Handles a comment.
     *
     * @param comment The comment.
     * @throws org.openrdf.rio.RDFHandlerException
     *          If the RDF handler has encountered an unrecoverable error.
     */
    @Override
    public void handleComment(String comment) throws RDFHandlerException {
    }


    private static String sanitizeString(String in) {
        // clean up illegal characters
        return in.replaceAll("[\\00]", "");
    }
}