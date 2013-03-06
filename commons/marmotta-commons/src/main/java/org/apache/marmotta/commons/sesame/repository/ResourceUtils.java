/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.marmotta.commons.sesame.repository;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;

import org.apache.marmotta.commons.sesame.model.Namespaces;
import org.openrdf.model.*;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Utility methods for simplifying certain common tasks. All methods are static and take as first argument a
 * RepositoryConnection that needs to be managed by the caller (i.e. requested from the repository and closed after use).
 *
 * <p/>
 * Author: Sebastian Schaffert
 */
public class ResourceUtils {

    private static Logger log = LoggerFactory.getLogger(ResourceUtils.class);

    // *****************************************************************************************************
    // methods for retrieving resources
    // *****************************************************************************************************
    
    /**
     * Check whenever the resource actually exists. Since this could be under different
     * interpretations, this implementation only checks for outgoing triples.
     * 
     * @param conn connection with the repository
     * @param uri uri of the resource to check
     * @return resource exists or not
     * @deprecated the name of this method is missleading. use {@link #isSubject(RepositoryConnection, String)}.
     */
    public static boolean existsResource(RepositoryConnection conn, String uri) {
    	return existsStatement(conn, conn.getValueFactory().createURI(uri), null, null, null);
    }
    
    /**
     * Check whether the uri is ever used as subject. 
     * @param conn connection with the repository
     * @param uri uri of the resource to check
     * @return true if the uri is ever used as subject.
     */
    public static boolean isSubject(RepositoryConnection conn, String uri) {
    	return existsStatement(conn, conn.getValueFactory().createURI(uri), null, null, null);
    }

    /**
     * Check whether the uri is ever used as context. 
     * @param conn connection with the repository
     * @param uri uri of the resource to check
     * @return true if the uri is ever used as context.
     */
    public static boolean isContext(RepositoryConnection conn, String uri) {
    	return existsStatement(conn, null, null, null, conn.getValueFactory().createURI(uri));
    }
    
    public static boolean existsStatement(RepositoryConnection conn, Resource subj, URI pred, Value object, Resource ctx) {
    	try {
    		RepositoryResult<Statement> stmts = conn.getStatements(subj, pred, object, true, ctx);
    		try {
    			return stmts.hasNext();
    		} finally {
    			stmts.close();
    		}
    	} catch (RepositoryException e) {
    		log.error(e.getMessage());
    		return false;
    	}
    }

    /**
     * Retrieve the KiWiUriResource with the given URI if it exists, or return null if it doesn't exist.
     * A Resource exists if and only if it is used in a Statement, i.e. it is uses either as Subject, Context, Predicate or Object.
     * @param uri
     * @return the URI or null if the Resource is not used.
     * @deprecated this method does not work as promised. <b>DO NOT USE IT</b>
     */
    @Deprecated
    public static URI getUriResource(RepositoryConnection con, String uri) {
    	URI r = con.getValueFactory().createURI(uri);
    	if (isSubject(con, uri) || isContext(con, uri) || existsStatement(con, null, r, null, null) || existsStatement(con, null, null, r, null)) {
    		return r;
    	} else {
    		return null;
    	}
//        if(con instanceof SailRepositoryConnection && ((SailRepositoryConnection)con).getSailConnection() instanceof ResourceConnection) {
//            return ((ResourceConnection) ((SailRepositoryConnection) con).getSailConnection()).getURI(uri);
//        } else {
//            return r;
//        }
    }

    /**
     * Retrieve the KiWiAnonResource with the given ID if it exists, or return null if it doesn't exist.
     * @param id
     * @return
     */
    public static BNode getAnonResource(RepositoryConnection con, String id) {
        if(con instanceof SailRepositoryConnection && ((SailRepositoryConnection)con).getSailConnection() instanceof ResourceConnection) {
            return ((ResourceConnection) ((SailRepositoryConnection) con).getSailConnection()).getBNode(id);
        } else {
            return con.getValueFactory().createBNode(id);
        }
    }

    /**
     * Remove the resource given as argument from the triple store and resource repository. This method will
     * remove all triples where the resource appears as subject, predicate, object or context.
     *
     * @param con
     * @param resource
     */
    public static void removeResource(RepositoryConnection con, Resource resource) throws RepositoryException {
        if(con instanceof SailRepositoryConnection && ((SailRepositoryConnection)con).getSailConnection() instanceof ResourceConnection) {
            ((ResourceConnection) ((SailRepositoryConnection) con).getSailConnection()).removeResource(resource);
        } else {
            con.remove(resource,null,null);
            if(resource instanceof URI) {
                con.remove((Resource)null,(URI)resource,null);
            }
            con.remove((Resource)null,null,resource);
            con.remove((Resource)null,null,null,resource);
        }
    }

    /**
     * List all resources contained in the KiWi System, regardless of knowledge space or type. Since this
     * operation works directly on the triple store, there is no guarantee the result is free of duplicates.
     * In case the underlying connection does not directly support listing resources (i.e. is not an instance of
     * ResourceConnection), the method will iterate over all triples and return their subjects
     *
     * @return
     */
    public static Iterable<Resource> listResources(final RepositoryConnection con) {
        if(con instanceof SailRepositoryConnection && ((SailRepositoryConnection)con).getSailConnection() instanceof ResourceConnection) {
            return new Iterable<Resource>() {
                @Override
                public Iterator<Resource> iterator() {
                    try {
                        return ResultUtils.unwrap(((ResourceConnection) ((SailRepositoryConnection) con).getSailConnection()).getResources());
                    } catch (RepositoryException e) {
                        ExceptionUtils.handleRepositoryException(e,ResourceUtils.class);
                        return Iterators.emptyIterator();
                    }
                }
            };
        } else {
            return listResourcesInternal(con,null,null,null);
        }
    }

    /**
     * List all resources of a specific type in the KiWi system.
     *
     * @param type the type of the resources to list
     * @return
     */
    public static Iterable<Resource> listResources(final RepositoryConnection con, Resource type) {
        return listResources(con,type,null);
    }

    /**
     * List all resources of a specific type contained in a certain knowledge space in the KiWi system
     *
     * @param context the resource identifying the knowledge space
     * @param type the type of the resources to list
     * @return
     */
    public static Iterable<Resource> listResources(final RepositoryConnection con, final Resource type, final URI context) {
        URI rdf_type = con.getValueFactory().createURI(Namespaces.NS_RDF + "type");

        return listResourcesInternal(con,rdf_type,type,context);
    }

    /**
     * List all resources that have a specific property set to the given value
     *
     * @param propertyUri
     * @param value  the literal value to query for
     * @return
     */
    public static Iterable<Resource> listResourcesByProperty(final RepositoryConnection con, String propertyUri, String value) {
        return listResourcesByProperty(con,propertyUri,value,null);
    }

    /**
     * List all resources that have a specific property set to the given value and context
     *
     * @param propertyUri
     * @param value  the literal value to query for
     * @return
     */
    public static Iterable<Resource> listResourcesByProperty(final RepositoryConnection con, String propertyUri, String value, URI context) {
        URI property = getUriResource(con,propertyUri);
        Value object = con.getValueFactory().createLiteral(value);

        if(property != null) {
            return listResourcesInternal(con,property,object,context);
        } else {
            return Collections.emptySet();
        }

    }

    /**
     * List resources with the given prefix
     *
     * @param prefix the prefix
     * @param offset
     * @param limit
     */
    public static Iterable<URI> listResourcesByPrefix(final RepositoryConnection con, final String prefix, final int offset, final int limit) {
        if(con instanceof SailRepositoryConnection && ((SailRepositoryConnection)con).getSailConnection() instanceof ResourceConnection) {
            return new Iterable<URI>() {
                @Override
                public Iterator<URI> iterator() {
                    try {
                        Iterator<URI> result = ResultUtils.unwrap(((ResourceConnection) ((SailRepositoryConnection) con).getSailConnection()).getResources(prefix));

                        Iterators.advance(result,offset);

                        if(limit > 0) {
                            return Iterators.limit(result,limit);
                        } else {
                            return result;
                        }
                    } catch (RepositoryException e) {
                        ExceptionUtils.handleRepositoryException(e,ResourceUtils.class);
                        return Iterators.emptyIterator();
                    }

                }
            };
        } else {
            // no direct prefix listing support, need to filter the listResources result
            return new Iterable<URI>() {
                @Override
                public Iterator<URI> iterator() {
                    Iterator<URI> result = Iterators.transform(
                            Iterators.filter(
                                    listResources(con).iterator(),
                                    new Predicate<Resource>() {
                                        @Override
                                        public boolean apply(Resource input) {
                                            return input instanceof URI && input.stringValue().startsWith(prefix);
                                        }
                                    }
                                    ),
                                    new Function<Resource, URI>() {
                                @Override
                                public URI apply(Resource input) {
                                    return (URI)input;
                                }
                            }
                            );

                    Iterators.advance(result,offset);

                    if(limit > 0) {
                        return Iterators.limit(result,limit);
                    } else {
                        return result;
                    }
                }
            };
        }
    }

    /**
     * List resources with the given prefix
     *
     * @param prefix the prefix
     */
    public static Iterable<URI> listResourcesByPrefix(final RepositoryConnection con, String prefix) {
        return listResourcesByPrefix(con,prefix,0,0);
    }

    private static Iterable<Resource> listResourcesInternal(final RepositoryConnection con, final URI property, final Value value, final URI context) {
        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }

        return new Iterable<Resource>() {
            @Override
            public Iterator<Resource> iterator() {
                try {
                    return  Iterators.filter(
                            Iterators.transform(
                                    ResultUtils.unwrap(con.getStatements(null, property, value, true, contexts)),
                                    new Function<Statement, Resource>() {
                                        @Override
                                        public Resource apply(Statement input) {
                                            return input.getSubject();
                                        }
                                    }),
                                    new Predicate<Resource>() {
                                // filter duplicates by remembering hash codes of visited resources
                                private HashSet<Integer> visited = new HashSet<Integer>();

                                @Override
                                public boolean apply(Resource input) {
                                    if(!visited.contains(input.hashCode())) {
                                        visited.add(input.hashCode());
                                        return true;
                                    } else {
                                        return false;
                                    }
                                }
                            });
                } catch (RepositoryException e) {
                    ExceptionUtils.handleRepositoryException(e,ResourceUtils.class);
                    return Iterators.emptyIterator();
                }
            }
        };

    }

    // *****************************************************************************************************
    // methods for working with properties
    // *****************************************************************************************************


    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation). Returns only literal
     * values for which no language has been assigned. For all Spaces!!!
     *
     * @param r
     * @param propLabel
     * @return
     */
    public static String getProperty(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return getProperty(con,r,propLabel,null, null);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation). Returns only literal
     * values for which no language has been assigned. Only for properties in the given context.
     *
     * @param r
     * @param propLabel
     * @param context
     * @return
     */
    public static String getProperty(RepositoryConnection con, Resource r, String propLabel, URI context) throws RepositoryException {
        return getProperty(con, r, propLabel, null, context);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale. For all spaces!!!
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    public static String getProperty(RepositoryConnection con, Resource r, String propLabel, Locale loc) throws RepositoryException {
        return getProperty(con, r, propLabel, loc, null);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale. Just for the given context.
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    public static String getProperty(RepositoryConnection con, Resource r, String propLabel, Locale loc, URI context) throws RepositoryException {
        Literal l = getLiteral(con, r,propLabel,loc, context);
        if (l == null) {
            return null;
        } else {
            return l.stringValue();
        }
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale.
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    private static Literal getLiteral(RepositoryConnection con, Resource r, String propLabel, Locale loc, URI context) throws RepositoryException {
        for(Value node : listOutgoingNodes(con,r,propLabel,context)) {
            if(node instanceof Literal) {
                if(loc == null && ((Literal)node).getLanguage() == null) {
                    return (Literal)node;
                } else if(loc != null && ((Literal)node).getLanguage() != null && ((Literal)node).getLanguage().equals(loc.getLanguage().toLowerCase()) ) {
                    return (Literal)node;
                }
            }
        }
        return null;
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale. For all Spaces!!
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @return
     *
     */
    public static Iterable<String> getProperties(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return getProperties(con,r,propLabel,null, null);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale. Just for the given space !!
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @return
     *
     */
    public static Iterable<String> getProperties(RepositoryConnection con, Resource r, String propLabel, URI context) throws RepositoryException {
        return getProperties(con, r, propLabel, null, context);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale.
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long
     *                  form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    public static Iterable<String> getProperties(RepositoryConnection con, Resource r, String propLabel, Locale loc) throws RepositoryException {
        return getProperties(con, r, propLabel, loc, null);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale.
     * Just for the given space !!
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long
     *                  form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    public static Iterable<String> getProperties(RepositoryConnection con, Resource r, String propLabel, Locale loc, URI context) throws RepositoryException {
        return Iterables.transform(listLiterals(con, r,propLabel, loc,context), new Function<Literal, String>() {
            @Override
            public String apply(Literal input) {
                return input.getLabel();
            }
        });
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale.
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @return
     */
    private static Iterable<Literal> listLiterals(RepositoryConnection con, Resource r, String propLabel, final Locale loc, URI context) throws RepositoryException {
        return Iterables.filter(
                Iterables.filter(listOutgoingNodes(con, r, propLabel, context), Literal.class),
                new Predicate<Literal>() {
                    @Override
                    public boolean apply(Literal input) {
                        return input.getLanguage() == null && loc == null ||
                                input.getLanguage() != null && input.getLanguage().equals(loc.getLanguage().toLowerCase());
                    }
                }
                );
    }

    /**
     * Generic method to set the literal value of a property of this resource to the provided
     * value without setting a language. For the given context.
     *
     * @param propLabel  the SeRQL or SPARQL short or long notation for the property
     * @param propValue  the String value of this property
     * @throws NamespaceResolvingException
     */
    //public static void setProperty(RepositoryConnection con, Resource r, String propLabel, String propValue, URI context) throws RepositoryException;

    /**
     * Generic method to set the literal value of a property of this resource to the provided
     * value without setting a language. For all spaces!!!
     *
     * @param propLabel the SeRQL or SPARQL short or long notation for the property
     * @param propValue the String value of this property
     */
    public static void setProperty(RepositoryConnection con, Resource r, String propLabel, String propValue) throws RepositoryException {
        setProperty(con,r,propLabel,propValue,(URI)null);
    }

    /**
     * Generic method to set the literal value of a property of this resource to the provided
     * value in the provided language. For all spaces.
     *
     * @param propLabel the SeRQL or SPARQL short or long notation for the property
     * @param propValue the String value of this property
     * @param context   a context
     */
    public static <T> void setProperty(RepositoryConnection con, Resource r, String propLabel, String propValue, URI context) throws RepositoryException {
        setProperty(con,r,propLabel,propValue,null, context);
    }

    /**
     * Generic method to set the literal value of a property of this resource to the provided
     * value in the provided language. For all spaces.
     *
     * @param propLabel the SeRQL or SPARQL short or long notation for the property
     * @param propValue the String value of this property
     * @param loc       the Locale representing the language of this property
     */
    public static <T> void setProperty(RepositoryConnection con, Resource r, String propLabel, String propValue, Locale loc) throws RepositoryException {
        setProperty(con, r,propLabel,propValue,null, null);
    }

    /**
     * Generic method to query for literal values related to this resource with the property
     * identified by "propLabel" (SeRQL/SPARQL short or long notation) and the given locale.
     * context define the knowledgespace in which this operation is. For the given context.
     *
     * @param propLabel label of the property; either RDF short form (e.g. "foaf:mbox") or long
     *                  form (e.g. <http://xmlns.com/foaf/0.1/mbox>)
     * @param loc
     * @param context   context in which this property will set
     * @return
     */
    public static <T> void setProperty(RepositoryConnection con, Resource r, String propLabel, String propValue, Locale loc, URI context) throws RepositoryException {
        if(propValue != null) {
            Resource[] contexts;
            if(context != null) {
                contexts = new Resource[] { context };
            } else {
                contexts = new Resource[0];
            }

            // remove previous property setting
            removeProperty(con,r,propLabel,loc,context);

            String prop_uri = resolvePropLabel(con, propLabel);

            // then set the new property value
            Literal value = con.getValueFactory().createLiteral(propValue, loc != null ? loc.getLanguage().toLowerCase() : null);
            URI     prop  = con.getValueFactory().createURI(prop_uri);
            con.add(r, prop, value, contexts);

        } else {
            removeProperty(con,r, propLabel,loc,context);

        }
    }

    /**
     * Remove a property from the RepositoryConnection con, Resource.
     *
     * @param propLabel the property label in SeRQL syntax to remove
     * @return true if the property existed and was removed
     */
    public static boolean removeProperty(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return removeProperty(con,r,propLabel,(Locale) null);
    }

    /**
     * Remove a property from the RepositoryConnection con, Resource. Just for the given space!!!
     *
     * @param propLabel the property label in SeRQL syntax to remove
     * @return true if the property existed and was removed
     */
    public static boolean removeProperty(RepositoryConnection con, Resource r, String propLabel, URI context) throws RepositoryException {
        return removeProperty(con,r,propLabel, null, context);
    }

    /**
     * Remove a property from the RepositoryConnection con, Resource. for all spaces !!!
     *
     * @param propLabel the property label in SeRQL syntax to remove
     * @param loc       the locale of the property to remove
     * @return true if the property existed and was removed
     */
    public static boolean removeProperty(RepositoryConnection con, Resource r, String propLabel, Locale loc) throws RepositoryException {
        return removeProperty(con,r, propLabel, loc, null);
    }

    /**
     * Remove a property from the RepositoryConnection con, Resource. Just for the given space!!!
     *
     * @param propLabel the property label in SeRQL syntax to remove
     * @return true if the property existed and was removed
     */
    public static boolean removeProperty(RepositoryConnection con, Resource r, String propLabel, Locale loc, URI context) throws RepositoryException {
        String uri = resolvePropLabel(con, propLabel);

        URI property = con.getValueFactory().createURI(uri);

        if(property != null) {

            // look up triple that corresponds to property and filter by locale
            List<Statement> remove = new ArrayList<Statement>();
            for(RepositoryResult<Statement> triples = con.getStatements(r,property,null,false,context); triples.hasNext(); ) {
                Statement t = triples.next();
                if(t.getObject() instanceof Literal) {
                    if(loc == null || ((Literal)t.getObject()).getLanguage().equals(loc.getLanguage().toLowerCase())) {
                        remove.add(t);
                    }
                }
            }

            // if triple exists, call TripleStore.remove on it
            if(remove.size() > 0) {
                for(Statement triple : remove) {
                    con.remove(triple);
                }
                return true;
            }
        }
        return false;
    }

    /* incoming and outgoing edges (Statement) */

    /**
     * List all outgoing edges from this resource to other resources. Shortcut for listOutgoing(null).
     * For all spaces!!!
     *
     * @return all outgoing edges from this resource
     */
    public static Iterable<? extends Statement> listOutgoing(RepositoryConnection con, Resource r) throws RepositoryException {
        return listOutgoing(con, r, (URI) null);
    }

    /**
     * List all outgoing edges from this resource to other resources. Shortcut for listOutgoing(null).
     * Just for the given space
     *
     * @return all outgoing edges from this resource
     */
    public static Iterable<? extends Statement> listOutgoing(RepositoryConnection con, Resource r, URI context) throws RepositoryException {
        return listOutgoing(con, r,null,context);
    }

    /**
     * List outgoing edges from this resource to other resources, using the property label passed
     * as argument. If limit is bigger than 0, then a maximum of limit triples will be returned.
     * Otherwise, all triples will be returned.
     * <p/>
     * The parameter propLabel is in the form of a SeRQL or SPARQL id. It can take one of the following
     * values:
     * <ul>
     * <li>a URI enclosed in &lt; &gt, e.g. &lt;http://www.example.com/myProp&gt;</li>
     * <li>a uri prefix, followed by a colon and the property name, e.g. ex:myProp</li>
     * <li>the value "null", in which case all outgoing edges are listed regardless of their label
     * (wildcard)</li>
     * </ul>
     * The result will be an iterable that allows to iterate over Statements.
     *
     * @param propLabel the label of the property to be queried, or null for wildcard
     * @return an iterable over the Statements that are outgoing edges of this resource
     */
    public static Iterable<? extends Statement> listOutgoing(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return listOutgoing(con, r, propLabel, null);
    }

    /**
     * List outgoing edges from this resource to other resources, using the property label passed
     * as argument. If limit is bigger than 0, then a maximum of limit triples will be returned.
     * Otherwise, all triples will be returned.
     * <p/>
     * The parameter propLabel is in the form of a SeRQL or SPARQL id. It can take one of the following
     * values:
     * <ul>
     * <li>a URI enclosed in &lt; &gt, e.g. &lt;http://www.example.com/myProp&gt;</li>
     * <li>a uri prefix, followed by a colon and the property name, e.g. ex:myProp</li>
     * <li>the value "null", in which case all outgoing edges are listed regardless of their label
     * (wildcard)</li>
     * </ul>
     * The result will be an iterable that allows to iterate over Statements. The underlying ClosableIteration will be closed when the last element has
     * been consumed.
     *
     * @param propLabel the label of the property to be queried, or null for wildcard
     * @param context   outgoing triples just for the given space
     * @return an iterable over the Statements that are outgoing edges of this resource
     */
    public static Iterable<? extends Statement> listOutgoing(final RepositoryConnection con, final Resource r, final String propLabel, final URI context) throws RepositoryException {
        final URI property;
        if(propLabel != null) {
            String prop_uri = resolvePropLabel(con, propLabel);
            if(prop_uri == null) {
                return Collections.emptySet();
            } else {
                property = con.getValueFactory().createURI(prop_uri);
            }
        } else {
            property = null;
        }

        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }

        return new Iterable<Statement>() {
            @Override
            public Iterator<Statement> iterator() {
                try {
                    return ResultUtils.unwrap(con.getStatements(r, property, null, true, contexts));
                } catch (RepositoryException ex) {
                    ExceptionUtils.handleRepositoryException(ex, ResourceUtils.class);
                    return Iterators.emptyIterator();
                }
            }
        };
    }

    /**
     * List the objects that are related to this resource through a certain property
     *
     * @return a list of all outgoingnodes independent of a space
     */
    public static Iterable<? extends Value> listOutgoingNodes(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return listOutgoingNodes(con, r, propLabel, null);
    }

    /**
     * List the objects that are related to this resource through a certain property
     *
     * @return a list of all outgoingnodes dependent of a space
     */
    public static Iterable<? extends Value> listOutgoingNodes(RepositoryConnection con, Resource r, String propLabel, URI context) throws RepositoryException {
        return Iterables.transform(
                listOutgoing(con,r,propLabel,context),
                new Function<Statement, Value>() {
                    @Override
                    public Value apply(Statement input) {
                        return input.getObject();
                    }
                }
                );
    }


    public static void addOutgoingNode(RepositoryConnection con, Resource r, String propLabel, Value target, URI context) throws RepositoryException {
        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }


        String property_uri = resolvePropLabel(con,propLabel);
        URI prop = con.getValueFactory().createURI(property_uri);
        con.add(r,prop,target,contexts);
    }

    /**
     * Add an outgoing node to the resource in the given context using the given property
     * @param con
     * @param r
     * @param prop
     * @param target
     * @param context
     * @throws RepositoryException
     * @deprecated use con.add directly instead
     */
    @Deprecated
    public static void addOutgoingNode(RepositoryConnection con, Resource r, URI prop, Value target, URI context) throws RepositoryException {
        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }


        con.add(r,prop,target,contexts);
    }

    /**
     * Remove an outgoing node from the resource reachable via the given property label in the given context
     * @param con
     * @param r
     * @param propLabel
     * @param target
     * @param context
     * @throws RepositoryException
     */
    public static void removeOutgoingNode(RepositoryConnection con, Resource r, String propLabel, Resource target, URI context) throws RepositoryException {
        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }


        String property_uri = resolvePropLabel(con,propLabel);
        URI prop = con.getValueFactory().createURI(property_uri);
        con.remove(r, prop, target, contexts);
    }

    /**
     * List all incoming edges from other resources to this resource
     *
     * @return
     */
    public static Iterable<? extends Statement> listIncoming(RepositoryConnection con, Resource r) throws RepositoryException {
        return listIncoming(con, r, (URI) null);
    }

    /**
     * List all incoming edges from other resources to this resource. Just for the given space(context)
     *
     * @return
     */
    public static Iterable<? extends Statement> listIncoming(RepositoryConnection con, Resource r, URI context) throws RepositoryException {
        return listIncoming(con, r, null, context);
    }


    /**
     * List incoming edges from other resources to this resource, using the property label passed
     * as argument. If limit is bigger than 0, then a maximum of limit triples will be returned.
     * Otherwise, all triples will be returned.
     * <p/>
     * The parameter propLabel is in the form of a SeRQL or SPARQL id. It can take one of the following
     * values:
     * <ul>
     * <li>a URI enclosed in &lt; &gt, e.g. &lt;http://www.example.com/myProp&gt;</li>
     * <li>a uri prefix, followed by a colon and the property name, e.g. ex:myProp</li>
     * <li>the value "null", in which case all outgoing edges are listed regardless of their label
     * (wildcard)</li>
     * </ul>
     * The result will be an iterable that allows to iterate over Statements.
     *
     *
     * @param r         the maximum number of triples to retrieve
     * @param propLabel the label of the property to be queried, or null for wildcard
     * @return an iterable over the Statements that are incoming edges of this resource
     */
    public static Iterable<? extends Statement> listIncoming(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return listIncoming(con, r, propLabel, null);
    }

    /**
     * List incoming edges from other resources to this resource, using the property label passed
     * as argument. If limit is bigger than 0, then a maximum of limit triples will be returned.
     * Otherwise, all triples will be returned.
     * <p/>
     * The parameter propLabel is in the form of a SeRQL or SPARQL id. It can take one of the following
     * values:
     * <ul>
     * <li>a URI enclosed in &lt; &gt, e.g. &lt;http://www.example.com/myProp&gt;</li>
     * <li>a uri prefix, followed by a colon and the property name, e.g. ex:myProp</li>
     * <li>the value "null", in which case all outgoing edges are listed regardless of their label
     * (wildcard)</li>
     * </ul>
     * The result will be an iterable that allows to iterate over Statements.
     *
     * @param propLabel the label of the property to be queried, or null for wildcard
     * @param r         the maximum number of triples to retrieve
     * @param context   incoming resources just for the given context/space
     * @return an iterable over the Statements that are incoming edges of this resource
     */
    public static Iterable<? extends Statement> listIncoming(final RepositoryConnection con, final Resource r, final String propLabel, final URI context) throws RepositoryException {
        final URI property;
        if(propLabel != null) {
            String prop_uri = resolvePropLabel(con, propLabel);
            if(prop_uri == null) {
                return Collections.emptySet();
            } else {
                property = con.getValueFactory().createURI(prop_uri);
            }
        } else {
            property = null;
        }

        final Resource[] contexts;
        if(context != null) {
            contexts = new Resource[] { context };
        } else {
            contexts = new Resource[0];
        }

        return new Iterable<Statement>() {
            @Override
            public Iterator<Statement> iterator() {
                try {
                    return ResultUtils.unwrap(con.getStatements(null, property, r, true, contexts));
                } catch (RepositoryException ex) {
                    ExceptionUtils.handleRepositoryException(ex, ResourceUtils.class);
                    return Iterators.emptyIterator();
                }
            }
        };
    }

    /**
     * Return a list of nodes that are the sources for edges with propLabel that have this resource
     * as endpoint. This is mostly a convenience method that wraps listIncoming(propLabel).
     *
     * @param propLabel the label that all edges listed must have, or null for wildcard
     * @return a list of resources that are sources of edges that have this resource as endpoint
     */
    public static Iterable<? extends Resource> listIncomingNodes(RepositoryConnection con, Resource r, String propLabel) throws RepositoryException {
        return listIncomingNodes(con, r, propLabel, null);
    }

    /**
     * Return a list of nodes that are the sources for edges with propLabel that have this resource
     * as endpoint. This is mostly a convenience method that wraps listIncoming(propLabel).
     *
     * @param propLabel the label that all edges listed must have, or null for wildcard
     * @param context   the context of the incoming nodes
     * @return a list of resources that are sources of edges that have this resource as endpoint
     */
    public static Iterable<? extends Resource> listIncomingNodes(RepositoryConnection con, Resource r, String propLabel, URI context) throws RepositoryException {
        return Iterables.transform(
                listIncoming(con, r, propLabel, context),
                new Function<Statement, Resource>() {
                    @Override
                    public Resource apply(Statement input) {
                        return input.getSubject();
                    }
                }
                );
    }


    /* convenience wrappers around common RDF properties */

    /**
     * Return the label of this resource in the language provided as parameter
     * <p/>
     * If no label is available for the given language, returns the identifier.
     *
     * @return
     */
    public static String getLabel(RepositoryConnection con, Resource r) throws RepositoryException {
        return getLabel(con, r,(Locale)null);
    }

    /**
     * Return the label of this resource in the language provided as parameter
     * <p/>
     * If no label is available for the given language, returns the identifier.
     *
     * @param context the space of the label
     * @return
     */
    public static String getLabel(RepositoryConnection con, Resource r, URI context) throws RepositoryException {
        return getLabel(con, r, null,context);
    }


    /**
     * Return the label of this resource in the language provided as parameter
     * within the getTripleStore().knowledge space of this Resource.
     * <p/>
     * If no label is available for the given language, returns the identifier.
     * Looking in all spaces!!!
     *
     * @param loc
     * @return
     */
    public static String getLabel(RepositoryConnection con, Resource r, Locale loc) throws RepositoryException {
        return getLabel(con, r,loc,null);
    }

    /**
     * Return the label of this resource in the language provided as parameter
     * within the getTripleStore().knowledge space of this Resource.
     * <p/>
     * If no label is available for the given language, returns the identifier.
     *
     * @param context space of the given label
     * @param loc
     * @return
     */
    public static String getLabel(RepositoryConnection con, Resource r, Locale loc, URI context) throws RepositoryException {
        String label = null;
        // check kiwi:title, rdfs:label, dc:title in this order ...
        String[] properties = { Namespaces.NS_RDFS+"label", Namespaces.NS_DC+"title", Namespaces.NS_DC_TERMS+"title", Namespaces.NS_SKOS+"prefLabel" };

        for(String property : properties) {
            label = getProperty(con, r,property,loc,context);
            if(label != null) {
                break;
            }
        }

        if(label == null && loc == null) {
            // try some common languages as well
            langloop: for(Locale loc2 : new Locale[] {Locale.ENGLISH, Locale.GERMAN}) {
                for(String property : properties) {
                    label = getProperty(con, r,property,loc2,context);
                    if(label != null) {
                        break langloop;
                    }
                }
            }
        }

        // still no label available, try to get last part from uri
        if(label == null && r instanceof URI) {
            String uri = r.stringValue();
            if(uri.lastIndexOf("#") > 0) {
                label = uri.substring(uri.lastIndexOf("#")+1);
            } else {
                label = uri.substring(uri.lastIndexOf("/")+1);
            }
        } else if(label == null && r instanceof BNode){
            label = r.stringValue();
        }
        return label;
    }


    /**
     * Set the rdfs:label of this Resource in the configured getTripleStore().TripleStore
     * for the given Locale. Looking in all spaces!!!
     *
     * @param label
     */
    public static void setLabel(RepositoryConnection con, Resource r, String label) throws RepositoryException {
        setLabel(con, r,null,label,null);
    }

    /**
     * Set the rdfs:label of this Resource in the configured getTripleStore().TripleStore
     * for the given Locale. Looking in all spaces!!!
     *
     * @param label
     */
    public static void setLabel(RepositoryConnection con, Resource r, String label, URI context) throws RepositoryException {
        setLabel(con, r,null,label,context);
    }

    /**
     * Set the rdfs:label of this Resource in the configured getTripleStore().TripleStore
     * for the given Locale. Looking in all spaces!!!
     *
     * @param loc
     * @param label
     */
    public static void setLabel(RepositoryConnection con, Resource r, Locale loc, String label) throws RepositoryException {
        setLabel(con, r,loc,label,null);
    }

    /**
     * Set the rdfs:label of this Resource in the configured getTripleStore().TripleStore
     * for the given Locale. Just for the given space !!!
     *
     * @param loc
     * @param label
     * @param context
     */
    public static void setLabel(RepositoryConnection con, Resource r, Locale loc, String label, URI context) throws RepositoryException {
        setProperty(con, r,"<"+ Namespaces.NS_RDFS+"label>", label, loc,context);
    }


    /**
     * Return the list of types as Resources that are associated with this resource using the
     * rdf:type RDF property.
     *
     * @return an iterable of Resource instances that represent the RDF types of this resource
     */
    public static Iterable<? extends Resource> getTypes(RepositoryConnection con, Resource r) throws RepositoryException {
        return getTypes(con,r,null);
    }

    /**
     * Return the list of types as Resources that are associated with this resource using the
     * rdf:type RDF property.
     *
     * @return an iterable of Resource instances that represent the RDF types of this resource
     */
    public static Iterable<? extends Resource> getTypes(final RepositoryConnection con, final Resource r, Resource context) throws RepositoryException {
        final URI rdf_type = con.getValueFactory().createURI(Namespaces.NS_RDF+"type");

        if(rdf_type != null) {
            final Resource[] contexts;
            if(context != null) {
                contexts = new Resource[] { context };
            } else {
                contexts = new Resource[0];
            }

            return Iterables.transform(
                    Iterables.filter(
                            new Iterable<Statement>() {
                                @Override
                                public Iterator<Statement> iterator() {
                                    try {
                                        return ResultUtils.unwrap(con.getStatements(r,rdf_type,null,true,contexts));
                                    } catch (RepositoryException e) {
                                        ExceptionUtils.handleRepositoryException(e, ResourceUtils.class);
                                        return Iterators.emptyIterator();
                                    }
                                }
                            },
                            new Predicate<Statement>() {
                                @Override
                                public boolean apply(Statement input) {
                                    return input.getObject() instanceof Resource;
                                }
                            }
                            ),
                            new Function<Statement, Resource>() {
                        @Override
                        public Resource apply(Statement input) {
                            return (Resource)input.getObject();
                        }
                    }
                    );
        } else {
            return Collections.emptyList();
        }
    }


    /**
     * Remove one of the RDF types of this Resource. For all spaces/context
     *
     * @param typeUri a URI resource representing the type of this Resource
     */
    public static boolean hasType(RepositoryConnection con, Resource r, String typeUri) throws RepositoryException {
        return hasType(con,r,con.getValueFactory().createURI(typeUri));
    }


    /**
     * Check whether this Resource has a certain RDF type
     *
     * @param type the resource representing the type to check for
     * @return true if the type is in the list of RDF types of this resource, false otherwise
     */
    public static boolean hasType(RepositoryConnection con, Resource r, URI type) throws RepositoryException {
        return hasType(con,r,type,null);
    }

    /**
     * Check whether this Resource has a certain RDF type in the given context/space
     *
     * @param type the resource representing the type to check for
     * @return true if the type is in the list of RDF types of this resource, false otherwise
     */
    public static boolean hasType(RepositoryConnection con, Resource r, URI type, URI context) throws RepositoryException {
        if(type != null) {
            URI rdf_type = con.getValueFactory().createURI(Namespaces.NS_RDF + "type");

            if(rdf_type != null) {
                return con.hasStatement(r,rdf_type,type,true,context);
            }
        }
        return false;
    }

    /**
     * Add the RDF type to the provided Resource in the given contexts/spaces.
     * <p/>
     * This is basically a shortcut to crate <br>
     * <code>&lt;r&gt; a &lt;type&gt;</code> <br>
     * in all provided contexts.
     * <p/>
     * If no context is provided, the type is added without context information.
     * 
     * @param con the Connection to use
     * @param r the Resource
     * @param type the Type (the Object of the triple)
     * @param context the contexts to store in
     */
    public static void addType(RepositoryConnection con, Resource r, URI type, URI... context) throws RepositoryException {
        if (type != null) {
            URI rdf_type = con.getValueFactory().createURI(Namespaces.NS_RDF + "type");

            if (rdf_type != null) {
                con.add(r, rdf_type, type, context);
            }

        }
    }


    private static String resolvePropLabel(RepositoryConnection con, String propLabel) throws RepositoryException {
        String uri = propLabel;

        // find out which kind of propLabel we got passed
        if(uri.startsWith("<") && uri.endsWith(">")) {
            // uri is a real uri enclosed in < >
            uri = uri.substring(1,uri.length()-1);
        } else if(!uri.contains("://") && uri.contains(":")) {
            // uri is a SeQRQL/SPARQL identifier with abbreviated namespace, we need to lookup the namespace...
            String[] components = uri.split(":");
            if(components.length == 2) {
                String ns_prefix = components[0];
                String ns_local  = components[1];

                String ns = con.getNamespace(ns_prefix);
                if(ns == null) {
                    log.error("could not find namespace with the given prefix");
                } else {
                    uri = ns + ns_local;
                }
            } else {
                log.error("could not properly split property identifier #0, as it contained more than one ':'",uri);
            }
        }
        return uri;
    }

    /**
     * Check whether the provided argument is a Resource (an URI or BNode).
     * <p/>
     * Equivalent to <code>(v instanceof Resource)</code>.
     * 
     * @param v
     *            the Value to check.
     * @return <code>true</code> if it is a {@link Resource}
     */
    public static boolean isResource(Value v) {
        return v instanceof Resource;
    }

    /**
     * Check whether the provided argument is an URI.
     * <p/>
     * Equivalent to <code>(v instanceof URI)</code>.
     * 
     * @param v
     *            the Value to check.
     * @return <code>true</code> if it is a {@link URI}
     */
    public static boolean isURI(Value v) {
        return v instanceof URI;
    }

    /**
     * Check whether the provided argument is a BNode.
     * <p/>
     * Equivalent to <code>(v instanceof BNode)</code>.
     * 
     * @param v
     *            the Value to check.
     * @return <code>true</code> if it is a {@link BNode}
     */
    public static boolean isBNode(Value v) {
        return v instanceof BNode;
    }

    /**
     * Check whether the provided argument is a Literal.
     * <p/>
     * Equivalent to <code>(v instanceof Literal)</code>.
     * 
     * @param v
     *            the Value to check.
     * @return <code>true</code> if it is a {@link Literal}
     */
    public static boolean isLiteral(Value v) {
        return v instanceof Literal;
    }
    
}